package rs.zylos.app

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import rs.zylos.app.data.api.ApiClient
import rs.zylos.app.databinding.ActivityMapBinding
import rs.zylos.app.map.CityConfig
import rs.zylos.app.map.MapDefaults
import rs.zylos.app.map.MapOverlayBinder
import rs.zylos.app.map.MapStyleFactory
import rs.zylos.app.map.MbtilesStore
import rs.zylos.app.platform.AndroidLocationProvider
import rs.zylos.app.platform.AndroidNetworkMonitor
import rs.zylos.app.ui.dock.BottomStackInsets
import rs.zylos.app.ui.dock.TabBarBinder
import rs.zylos.app.ui.route.RouteChromeBinder
import rs.zylos.app.ui.search.SearchDockBinder
import rs.zylos.app.ui.sheet.ObjectSheetBinder
import rs.zylos.app.viewmodel.BottomTab
import rs.zylos.app.viewmodel.MapRouteMode
import rs.zylos.app.viewmodel.MapUiState
import rs.zylos.app.viewmodel.MapViewModel
import rs.zylos.app.viewmodel.RouteUiError
import rs.zylos.app.viewmodel.SheetMode
import rs.zylos.app.viewmodel.UserMessage

class MapActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMapBinding
    private lateinit var mapView: MapView
    private lateinit var searchDock: SearchDockBinder
    private lateinit var tabBar: TabBarBinder
    private lateinit var objectSheet: ObjectSheetBinder
    private lateinit var routeChrome: RouteChromeBinder
    private lateinit var overlay: MapOverlayBinder
    private var insetBottom = 0
    private var insetTop = 0

    private val viewModel: MapViewModel by viewModels {
        val app = application as ZylosApp
        val url = BuildConfig.API_URL
        MapViewModel.factory(
            ApiClient.create(url),
            app.database.searchHistoryDao(),
            ApiClient.createRoute(url),
            AndroidNetworkMonitor(this),
            AndroidLocationProvider(this),
        )
    }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refreshGpsAvailability()
        viewModel.onFillMyLocation()
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val state = viewModel.state.value
            when {
                state.dropdownOpen -> {
                    viewModel.onSearchFocusChanged(false)
                    viewModel.onRouteFieldFocus(null)
                    searchDock.clearSearchFocus()
                    hideKeyboard()
                }
                state.route.mode == MapRouteMode.Result -> viewModel.onClearRoute()
                state.sheet.mode == SheetMode.Organization && state.sheet.building != null ->
                    viewModel.onBackToBuilding()
                state.sheet.mode == SheetMode.Organization ||
                    state.sheet.mode == SheetMode.Building ||
                    state.sheet.mode == SheetMode.Loading ||
                    state.sheet.mode == SheetMode.Peek ||
                    state.sheet.mode == SheetMode.SearchList -> viewModel.onSheetClosed()
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        mapView = binding.mapView
        mapView.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backCallback)

        searchDock = SearchDockBinder(binding, viewModel, ::hideKeyboard)
        tabBar = TabBarBinder(binding, viewModel, ::requestMyLocation)
        routeChrome = RouteChromeBinder(
            binding,
            viewModel,
            ::dp,
            { insetTop },
            { insetBottom },
            ::requestMyLocation,
        )
        objectSheet = ObjectSheetBinder(
            binding,
            viewModel,
            ::dp,
            ::updateMapControls,
            { overlay.resetSearchCameraPadding() },
        )
        overlay = MapOverlayBinder(
            mapView,
            viewModel,
            ::dp,
            { insetTop },
            ::dockReservePx,
            ::hideKeyboard,
        )
        searchDock.bind()
        tabBar.bind()
        routeChrome.bind()
        objectSheet.bind()

        ViewCompat.setOnApplyWindowInsetsListener(binding.coordinator) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            insetBottom = maxOf(bars.bottom, ime.bottom)
            insetTop = bars.top
            val dockParams = binding.searchDock.layoutParams as CoordinatorLayout.LayoutParams
            dockParams.bottomMargin = insetBottom
            binding.searchDock.layoutParams = dockParams
            updateBottomStackInsets()
            insets
        }
        binding.searchDock.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateBottomStackInsets()
        }
        binding.zoomIn.setOnClickListener { overlay.bumpZoom(MapDefaults.ZOOM_STEP) }
        binding.zoomOut.setOnClickListener { overlay.bumpZoom(-MapDefaults.ZOOM_STEP) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
        loadMap()
    }

    private fun render(state: MapUiState) {
        backCallback.isEnabled = state.sheet.mode != SheetMode.Idle || state.dropdownOpen ||
            state.route.mode == MapRouteMode.Result || state.route.bottomTab == BottomTab.Route
        searchDock.render(state)
        tabBar.render(state)
        routeChrome.render(state)
        objectSheet.render(state)
        overlay.render(state)
        binding.searchDock.post { updateBottomStackInsets() }
        state.message?.let { message ->
            val text = when (message) {
                UserMessage.NotFound -> getString(R.string.nema_zgrade)
                UserMessage.OutsideCity -> getString(R.string.van_grada)
                UserMessage.Network -> getString(R.string.nema_veze)
            }
            if (state.haptic) {
                hapticReject()
            }
            showMapToast(text)
            viewModel.consumeMessage()
        }
        state.route.error?.let { error ->
            val text = when (error) {
                RouteUiError.OutsideCity -> getString(R.string.ruta_van_grada)
                RouteUiError.Timeout -> getString(R.string.ruta_timeout)
                RouteUiError.NoRoute -> getString(R.string.ruta_nema)
                RouteUiError.Offline -> getString(R.string.ruta_offline)
            }
            showMapToast(text)
            viewModel.consumeRouteError()
        }
    }

    private fun requestMyLocation() {
        val provider = AndroidLocationProvider(this)
        if (provider.isAvailable()) {
            viewModel.onFillMyLocation()
        } else {
            locationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }

    private fun showMapToast(text: String) {
        val snackbar = Snackbar.make(binding.coordinator, text, MapDefaults.TOAST_DURATION_MS)
        snackbar.anchorView = binding.searchDock
        val view = snackbar.view
        view.background = ContextCompat.getDrawable(this, R.drawable.bg_toast_pill)
        view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).apply {
            setTextColor(ContextCompat.getColor(this@MapActivity, android.R.color.white))
            textSize = 13f
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
        snackbar.show()
    }

    private fun updateBottomStackInsets() {
        val hostParams = binding.sheetHost.layoutParams as CoordinatorLayout.LayoutParams
        hostParams.topMargin = insetTop
        val reserve = dockReservePx()
        if (hostParams.bottomMargin != reserve) {
            hostParams.bottomMargin = reserve
            binding.sheetHost.layoutParams = hostParams
        }
        binding.searchDropdown.maxHeightPx =
            BottomStackInsets.dropdownMaxHeightPx(binding, insetTop, insetBottom, ::dp)
        updateMapControls()
    }

    private fun updateMapControls() {
        val params = binding.mapControls.layoutParams as CoordinatorLayout.LayoutParams
        params.bottomMargin = controlsBottomMarginPx()
        params.marginEnd = resources.getDimensionPixelSize(R.dimen.margin_screen)
        binding.mapControls.layoutParams = params
        val attributionBottom = (controlsBottomMarginPx() - dp(MapDefaults.CONTROL_GAP_DP)).coerceAtLeast(dp(8))
        overlay.applyAttributionMargins(attributionBottom)
    }

    private fun dockReservePx(): Int = BottomStackInsets.dockReservePx(binding, ::dp)

    private fun controlsBottomMarginPx(): Int {
        val gap = dp(MapDefaults.CONTROL_GAP_DP)
        if (objectSheet.isHidden) {
            return dockReservePx() + gap
        }
        val sheetLoc = IntArray(2)
        val parentLoc = IntArray(2)
        binding.buildingSheet.getLocationOnScreen(sheetLoc)
        binding.coordinator.getLocationOnScreen(parentLoc)
        val sheetTopInParent = sheetLoc[1] - parentLoc[1]
        val fromBottom = (binding.coordinator.height - sheetTopInParent).coerceAtLeast(0)
        return fromBottom + gap
    }

    private fun hapticReject() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            binding.root.performHapticFeedback(HapticFeedbackConstants.REJECT)
        } else {
            binding.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        binding.searchInput.clearFocus()
        binding.routeChrome.routeFromInput.clearFocus()
        binding.routeChrome.routeToInput.clearFocus()
    }

    private fun loadMap() {
        val store = MbtilesStore(this)
        if (!store.hasAsset()) {
            binding.mapError.visibility = View.VISIBLE
            binding.mapError.setText(R.string.map_missing_tiles)
            return
        }
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { store.ensureLocalFile() }
                val raw = withContext(Dispatchers.IO) {
                    assets.open(MapDefaults.STYLE_ASSET).bufferedReader().use { it.readText() }
                }
                val styleJson = MapStyleFactory.patch(raw, MapStyleFactory.mbtilesUri(file.absolutePath))
                mapView.getMapAsync { mapLibre ->
                    mapLibre.setMaxPitchPreference(MapDefaults.MAX_PITCH)
                    mapLibre.uiSettings.isRotateGesturesEnabled = true
                    mapLibre.uiSettings.isTiltGesturesEnabled = true
                    mapLibre.uiSettings.isZoomGesturesEnabled = true
                    mapLibre.uiSettings.isScrollGesturesEnabled = true
                    mapLibre.uiSettings.isAttributionEnabled = true
                    mapLibre.uiSettings.isLogoEnabled = false
                    mapLibre.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(CityConfig.LAT, CityConfig.LON))
                        .zoom(CityConfig.INITIAL_ZOOM)
                        .build()
                    mapLibre.addOnCameraIdleListener {
                        val pos = mapLibre.cameraPosition
                        val target = pos.target ?: return@addOnCameraIdleListener
                        val bounds = mapLibre.projection.visibleRegion.latLngBounds
                        viewModel.onCameraIdle(
                            target.latitude,
                            target.longitude,
                            pos.zoom,
                            bounds.longitudeWest,
                            bounds.latitudeSouth,
                            bounds.longitudeEast,
                            bounds.latitudeNorth,
                        )
                    }
                    mapLibre.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                        overlay.attach(mapLibre, style)
                    }
                }
            } catch (error: Exception) {
                binding.mapError.visibility = View.VISIBLE
                binding.mapError.text = error.message ?: getString(R.string.map_missing_tiles)
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        viewModel.refreshGpsAvailability()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
