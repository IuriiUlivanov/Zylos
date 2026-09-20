package rs.zylos.novisad

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.RectF
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import rs.zylos.novisad.data.api.ApiClient
import rs.zylos.novisad.data.api.OrgDetailResponse
import rs.zylos.novisad.databinding.ActivityMapBinding
import rs.zylos.novisad.databinding.ItemOrgFieldBinding
import rs.zylos.novisad.map.BuildingHighlightLayers
import rs.zylos.novisad.map.MapDefaults
import rs.zylos.novisad.map.MapStyleFactory
import rs.zylos.novisad.map.MbtilesStore
import rs.zylos.novisad.map.OrgPins
import rs.zylos.novisad.map.OrgPinsLayers
import rs.zylos.novisad.map.RouteLayers
import rs.zylos.novisad.map.SearchMarker
import rs.zylos.novisad.map.SearchMarkerLayers
import rs.zylos.novisad.map.SearchPins
import rs.zylos.novisad.map.SearchPinsLayers
import rs.zylos.novisad.ui.route.RouteSheetAdapter
import rs.zylos.novisad.ui.search.SearchDropdownAdapter
import rs.zylos.novisad.ui.search.SearchRow
import rs.zylos.novisad.ui.sheet.OrgListAdapter
import rs.zylos.novisad.ui.sheet.SheetAnchors
import rs.zylos.novisad.ui.sheet.SheetStep
import rs.zylos.novisad.viewmodel.BottomTab
import rs.zylos.novisad.viewmodel.MapPinMode
import rs.zylos.novisad.viewmodel.MapRouteMode
import rs.zylos.novisad.viewmodel.MapUiState
import rs.zylos.novisad.viewmodel.MapViewModel
import rs.zylos.novisad.viewmodel.RouteField
import rs.zylos.novisad.viewmodel.RouteUiError
import rs.zylos.novisad.viewmodel.SearchLogic
import rs.zylos.novisad.viewmodel.SearchUiError
import rs.zylos.novisad.viewmodel.SheetLogic
import rs.zylos.novisad.viewmodel.SheetMode
import rs.zylos.novisad.viewmodel.UserMessage
import kotlin.math.min

class MapActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMapBinding
    private lateinit var mapView: MapView
    private lateinit var sheetBehavior: BottomSheetBehavior<LinearLayout>
    private var map: MapLibreMap? = null
    private var mapStyle: Style? = null
    private var applyingSheetState = false
    private var applyingSearchText = false
    private var applyingRouteText = false
    private var applyingRoutePager = false
    private var lastCameraNonce = 0
    private var lastBoundsNonce = 0
    private var lastSheetMode: SheetMode = SheetMode.Idle
    private var lastPinMode: MapPinMode = MapPinMode.Browse
    private var lastRouteMode: MapRouteMode = MapRouteMode.Idle
    private var sheetStep: SheetStep = SheetStep.Minimal
    private var routeSheetStep = 2
    private var insetBottom = 0
    private var insetTop = 0
    private var searchFlyPaddingActive = false

    private val viewModel: MapViewModel by viewModels {
        val app = application as ZylosApp
        val url = BuildConfig.API_URL
        MapViewModel.factory(
            ApiClient.create(url),
            app.database.searchHistoryDao(),
            ApiClient.createRoute(url),
        )
    }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) {
            applyGpsAvailability()
            fillMyLocation()
        } else {
            applyGpsAvailability()
        }
    }

    private val orgAdapter = OrgListAdapter { item -> viewModel.onOrgSelected(item.id) }
    private val routeAdapter = RouteSheetAdapter()
    private val searchAdapter = SearchDropdownAdapter(
        onHit = { hit ->
            hideKeyboard()
            if (viewModel.state.value.bottomTab == BottomTab.Route) {
                viewModel.onSelectRouteHit(hit)
            } else {
                viewModel.onSelectHit(hit)
            }
        },
        onHistory = { item ->
            applyingSearchText = true
            binding.searchInput.setText(item.query)
            binding.searchInput.setSelection(item.query.length)
            applyingSearchText = false
            viewModel.onHistoryQuery(item.query)
        },
    )
    private val searchSheetAdapter = SearchDropdownAdapter(
        onHit = { hit -> viewModel.onSelectHit(hit) },
        onHistory = { },
    )

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val state = viewModel.state.value
            when {
                state.dropdownOpen -> {
                    viewModel.onSearchFocusChanged(false)
                    viewModel.onRouteFieldFocus(null)
                    binding.searchInput.clearFocus()
                    hideKeyboard()
                }
                state.routeMode == MapRouteMode.Result -> viewModel.onClearRoute()
                state.mode == SheetMode.Organization && state.building != null -> viewModel.onBackToBuilding()
                state.mode == SheetMode.Organization ||
                    state.mode == SheetMode.Building ||
                    state.mode == SheetMode.Loading ||
                    state.mode == SheetMode.Peek ||
                    state.mode == SheetMode.SearchList -> viewModel.onSheetClosed()
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

        ViewCompat.setOnApplyWindowInsetsListener(binding.coordinator) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            insetBottom = maxOf(bars.bottom, ime.bottom)
            insetTop = bars.top
            val dockParams = binding.searchDock.layoutParams as CoordinatorLayout.LayoutParams
            dockParams.bottomMargin = insetBottom
            binding.searchDock.layoutParams = dockParams
            val hostParams = binding.sheetHost.layoutParams as CoordinatorLayout.LayoutParams
            hostParams.topMargin = insetTop
            hostParams.bottomMargin = dockReservePx()
            binding.sheetHost.layoutParams = hostParams
            updateDropdownMaxHeight()
            updateMapControls()
            insets
        }

        binding.orgList.layoutManager = LinearLayoutManager(this)
        binding.orgList.adapter = orgAdapter
        binding.searchDropdown.layoutManager = LinearLayoutManager(this)
        binding.searchDropdown.adapter = searchAdapter
        binding.searchResultList.layoutManager = LinearLayoutManager(this)
        binding.searchResultList.adapter = searchSheetAdapter
        binding.sheetClose.setOnClickListener { viewModel.onSheetClosed() }
        binding.showAllOnMap.setOnClickListener {
            hideKeyboard()
            viewModel.onShowAllOnMap()
        }
        val handleTap = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    expandSheetByOne()
                    return true
                }
            },
        )
        binding.sheetHandleHit.setOnTouchListener { _, event ->
            handleTap.onTouchEvent(event)
            false
        }
        binding.searchClear.setOnClickListener {
            viewModel.onClearSearch()
            binding.searchInput.requestFocus()
        }
        binding.tabSearch.setOnClickListener { viewModel.onSelectTab(BottomTab.Search) }
        binding.tabRoute.setOnClickListener {
            viewModel.onSelectTab(BottomTab.Route)
            if (viewModel.state.value.routeFrom == null) {
                fillMyLocation()
            }
        }
        binding.tabBuildCta.setOnClickListener { viewModel.onBuildCta(isOnline()) }
        binding.routeClose.setOnClickListener { viewModel.onClearRoute() }
        binding.routeChrome.routeSwap.setOnClickListener { viewModel.onSwapRoute(isOnline()) }
        binding.routeChrome.routeFromMap.setOnClickListener { viewModel.onNaKartu(RouteField.From) }
        binding.routeChrome.routeToMap.setOnClickListener { viewModel.onNaKartu(RouteField.To) }
        binding.routeChrome.routeMyLocation.setOnClickListener {
            if (hasLocationPermission()) fillMyLocation() else {
                locationPermission.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            }
        }
        binding.routeHandle.setOnClickListener { cycleRouteSheetStep() }
        binding.routePager.adapter = routeAdapter
        binding.routePager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (!applyingRoutePager) {
                    viewModel.onSelectItinerary(position)
                }
            }
        })
        bindRouteField(binding.routeChrome.routeFromInput, RouteField.From)
        bindRouteField(binding.routeChrome.routeToInput, RouteField.To)
        applyGpsAvailability()
        binding.zoomIn.setOnClickListener { bumpZoom(MapDefaults.ZOOM_STEP) }
        binding.zoomOut.setOnClickListener { bumpZoom(-MapDefaults.ZOOM_STEP) }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (applyingSearchText) {
                    return
                }
                viewModel.onQueryChange(s?.toString().orEmpty())
            }
        })
        binding.searchInput.setOnFocusChangeListener { _, hasFocus ->
            viewModel.onSearchFocusChanged(hasFocus)
        }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val first = viewModel.state.value.hits.firstOrNull()
                if (first != null) {
                    hideKeyboard()
                    viewModel.onSelectHit(first)
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }

        sheetBehavior = BottomSheetBehavior.from(binding.buildingSheet)
        sheetBehavior.isFitToContents = false
        sheetBehavior.halfExpandedRatio = MapDefaults.SHEET_STEP2_RATIO
        sheetBehavior.peekHeight = dp(MapDefaults.SHEET_STEP1_DP)
        sheetBehavior.isHideable = true
        sheetBehavior.skipCollapsed = false
        sheetBehavior.isDraggable = true
        sheetBehavior.isGestureInsetBottomIgnored = true
        sheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        sheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                stepFromBehavior(newState)?.let { settled ->
                    sheetStep = settled
                    applySheetStepContent()
                }
                if (applyingSheetState) {
                    return
                }
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    viewModel.onSheetClosed()
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                applySheetStepContent(slideOffset)
                updateMapControls()
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }

        loadMap()
    }

    private fun render(state: MapUiState) {
        backCallback.isEnabled = state.mode != SheetMode.Idle || state.dropdownOpen ||
            state.routeMode == MapRouteMode.Result || state.bottomTab == BottomTab.Route
        renderTabs(state)
        renderSearch(state)
        renderRoute(state)
        renderSheet(state)
        renderHighlight(state.highlightJson)
        renderOrgPins(state)
        renderSearchPins(state)
        renderMarker(state.markerJson)
        renderRouteLayers(state)
        renderCamera(state)
        renderBounds(state)
        applyPoiSearchDim(state.pinMode != MapPinMode.Browse || state.routeMode != MapRouteMode.Idle)
        if (state.pinMode == MapPinMode.SearchMulti && lastPinMode != MapPinMode.SearchMulti) {
            hideKeyboard()
        }
        lastPinMode = state.pinMode
        lastRouteMode = state.routeMode
        updateMapControls()
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
        state.routeError?.let { error ->
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

    private fun renderSearch(state: MapUiState) {
        if (binding.searchInput.text.toString() != state.query) {
            applyingSearchText = true
            binding.searchInput.setText(state.query)
            binding.searchInput.setSelection(state.query.length)
            applyingSearchText = false
        }
        val loading = state.searchLoading && state.query.length >= MapDefaults.SEARCH_MIN_LENGTH
        binding.searchClear.visibility =
            if (state.query.isNotEmpty() && !loading) View.VISIBLE else View.GONE
        binding.searchSpinner.visibility = if (loading) View.VISIBLE else View.GONE

        val showingHistory = state.bottomTab == BottomTab.Search &&
            state.searchFocused && state.query.isEmpty() && state.history.isNotEmpty()
        val showingLive = if (state.bottomTab == BottomTab.Route && state.routeInputField != null) {
            val q = if (state.routeInputField == RouteField.From) state.routeFromQuery else state.routeToQuery
            q.length >= MapDefaults.SEARCH_MIN_LENGTH && !state.routeSearchLoading
        } else {
            state.searchFocused && state.query.length >= MapDefaults.SEARCH_MIN_LENGTH && !loading
        }
        val liveHits = if (state.bottomTab == BottomTab.Route) state.routeHits else state.hits
        val showingHits = showingLive && liveHits.isNotEmpty() &&
            (state.bottomTab == BottomTab.Route || state.searchError == null)
        val showingError = state.bottomTab == BottomTab.Search && showingLive && state.searchError != null
        val open = showingHistory || showingHits || showingError
        binding.searchDropdownCard.visibility = if (open) View.VISIBLE else View.GONE
        binding.searchHistoryHeader.visibility = if (showingHistory) View.VISIBLE else View.GONE

        when {
            showingHistory -> {
                binding.searchStatus.visibility = View.GONE
                searchAdapter.submit(state.history.map { SearchRow.History(it) })
            }
            showingError && state.searchError == SearchUiError.Empty -> {
                binding.searchStatus.visibility = View.VISIBLE
                binding.searchStatus.setTextColor(getColor(R.color.muted))
                binding.searchStatus.setText(R.string.nista_nadjeno)
                searchAdapter.submit(emptyList())
            }
            showingError && state.searchError == SearchUiError.Unavailable -> {
                binding.searchStatus.visibility = View.VISIBLE
                binding.searchStatus.setTextColor(getColor(R.color.danger))
                binding.searchStatus.setText(R.string.pretraga_nedostupna)
                searchAdapter.submit(emptyList())
            }
            showingError && state.searchError == SearchUiError.Network -> {
                binding.searchStatus.visibility = View.VISIBLE
                binding.searchStatus.setTextColor(getColor(R.color.danger))
                binding.searchStatus.setText(R.string.nema_veze)
                searchAdapter.submit(emptyList())
            }
            showingHits -> {
                binding.searchStatus.visibility = View.GONE
                val rows = liveHits.map { SearchRow.Hit(it) }
                searchAdapter.submit(rows)
            }
            else -> {
                binding.searchStatus.visibility = View.GONE
                searchAdapter.submit(emptyList())
            }
        }
        binding.showAllOnMap.visibility =
            if (showingHits && state.bottomTab == BottomTab.Search) View.VISIBLE else View.GONE
        updateDropdownMaxHeight()
    }

    private fun renderTabs(state: MapUiState) {
        val routeTab = state.bottomTab == BottomTab.Route
        val showCta = routeTab && state.canBuildRoute && state.routeMode != MapRouteMode.Result
        binding.tabBuildCta.visibility = if (showCta) View.VISIBLE else View.GONE
        binding.tabRoute.visibility = if (showCta) View.GONE else View.VISIBLE
        val searchColor = if (!routeTab) R.color.accent else R.color.muted
        val routeColor = if (routeTab && !showCta) R.color.accent else R.color.muted
        binding.tabSearchIcon.imageTintList = ContextCompat.getColorStateList(this, searchColor)
        binding.tabSearchLabel.setTextColor(ContextCompat.getColor(this, searchColor))
        binding.tabRouteIcon.imageTintList = ContextCompat.getColorStateList(this, routeColor)
        binding.tabRouteLabel.setTextColor(ContextCompat.getColor(this, routeColor))
        binding.tabBuildCta.isEnabled = !state.routeLoading
        binding.tabBuildCta.text = if (state.routeLoading) getString(R.string.ucitavam) else getString(R.string.ruta_napravi)
    }

    private fun renderRoute(state: MapUiState) {
        val routeTab = state.bottomTab == BottomTab.Route
        binding.searchChrome.visibility = if (routeTab) View.GONE else View.VISIBLE
        binding.routeChrome.root.visibility = if (routeTab) View.VISIBLE else View.GONE
        binding.routeChrome.routeSpinner.visibility = if (state.routeLoading) View.VISIBLE else View.GONE
        binding.routeChrome.routeMyLocation.isEnabled = state.gpsEnabled
        binding.routeChrome.routeMyLocation.alpha = if (state.gpsEnabled) 1f else 0.4f
        setRouteText(binding.routeChrome.routeFromInput, state.routeFromQuery)
        setRouteText(binding.routeChrome.routeToInput, state.routeToQuery)
        val result = state.routeMode == MapRouteMode.Result
        if (result && lastRouteMode != MapRouteMode.Result) {
            routeSheetStep = 2
        }
        binding.routeResults.visibility = if (result && routeTab) View.VISIBLE else View.GONE
        if (result && routeTab) {
            if (routeAdapter.itemCount != state.routeItineraries.size) {
                routeAdapter.submit(state.routeItineraries)
            } else {
                routeAdapter.submit(state.routeItineraries)
            }
            if (binding.routePager.currentItem != state.activeItineraryIndex) {
                applyingRoutePager = true
                binding.routePager.setCurrentItem(state.activeItineraryIndex, false)
                applyingRoutePager = false
            }
            bindRouteDots(state)
            val step1 = routeSheetStep == 1
            binding.routeStep1Summary.visibility = if (step1) View.VISIBLE else View.GONE
            binding.routePager.visibility = if (step1) View.GONE else View.VISIBLE
            val fromLabel = state.routeFrom?.label.orEmpty()
            val toLabel = state.routeTo?.label.orEmpty()
            binding.routeStep1Summary.text = "$fromLabel → $toLabel"
            applyRouteResultsHeight()
        }
        binding.searchDock.post { updateMapControls() }
    }

    private fun setRouteText(input: EditText, value: String) {
        if (input.text.toString() == value) {
            return
        }
        applyingRouteText = true
        input.setText(value)
        input.setSelection(value.length)
        applyingRouteText = false
    }

    private fun bindRouteDots(state: MapUiState) {
        val host = binding.routeDots
        host.removeAllViews()
        val count = state.routeItineraries.size
        if (count <= 1) {
            host.visibility = View.GONE
            return
        }
        host.visibility = View.VISIBLE
        repeat(count) { index ->
            val dot = View(this)
            val size = dp(8)
            val params = LinearLayout.LayoutParams(size, size)
            params.marginStart = dp(4)
            params.marginEnd = dp(4)
            dot.layoutParams = params
            dot.background = ContextCompat.getDrawable(
                this,
                if (index == state.activeItineraryIndex) R.drawable.bg_dot_on else R.drawable.bg_dot_off,
            )
            host.addView(dot)
        }
    }

    private fun applyRouteResultsHeight() {
        val params = binding.routeResults.layoutParams
        params.height = when (routeSheetStep) {
            1 -> dp(MapDefaults.ROUTE_SHEET_STEP1_DP)
            3 -> (binding.coordinator.height - insetTop - dp(MapDefaults.ROUTE_PANEL_HEIGHT_DP) -
                dp(MapDefaults.BOTTOM_TAB_HEIGHT_DP) - insetBottom).coerceAtLeast(dp(MapDefaults.ROUTE_SHEET_STEP2_DP))
            else -> dp(MapDefaults.ROUTE_SHEET_STEP2_DP)
        }
        binding.routeResults.layoutParams = params
    }

    private fun cycleRouteSheetStep() {
        routeSheetStep = when (routeSheetStep) {
            1 -> 2
            2 -> 3
            else -> 1
        }
        applyRouteResultsHeight()
    }

    private fun bindRouteField(input: EditText, field: RouteField) {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (applyingRouteText) {
                    return
                }
                viewModel.onRouteQueryChange(field, s?.toString().orEmpty())
            }
        })
        input.setOnFocusChangeListener { _, hasFocus ->
            viewModel.onRouteFieldFocus(if (hasFocus) field else null)
        }
    }

    private fun renderRouteLayers(state: MapUiState) {
        val style = mapStyle ?: return
        if (state.routeMode == MapRouteMode.Result) {
            RouteLayers.setGeometry(
                style,
                state.routeWalkJson,
                state.routeTransitJson,
                state.routeLabelsJson,
                state.routeFromJson,
                state.routeToJson,
            )
        } else {
            RouteLayers.clear(style)
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun applyGpsAvailability() {
        val lm = getSystemService(LocationManager::class.java)
        val enabled = hasLocationPermission() && lm != null && (
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            )
        viewModel.onGpsAvailability(enabled)
    }

    @SuppressLint("MissingPermission")
    private fun fillMyLocation() {
        if (!hasLocationPermission()) {
            return
        }
        val lm = getSystemService(LocationManager::class.java) ?: return
        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (loc != null) {
            viewModel.onMyLocation(loc.longitude, loc.latitude)
        }
    }

    private fun renderSheet(state: MapUiState) {
        if (state.routeMode == MapRouteMode.Result) {
            setSheetState(BottomSheetBehavior.STATE_HIDDEN)
            lastSheetMode = state.mode
            return
        }
        val modeChanged = lastSheetMode != state.mode
        val loading = state.mode == SheetMode.Loading
        when (state.mode) {
            SheetMode.Idle -> {
                sheetStep = SheetStep.Minimal
                setSheetState(BottomSheetBehavior.STATE_HIDDEN)
                resetSearchCameraPadding()
                binding.searchResultList.visibility = View.GONE
            }
            SheetMode.Loading -> {
                binding.sheetTitle.setText(R.string.ucitavam)
                binding.sheetSubtitle.text = ""
                binding.buildingContent.visibility = View.VISIBLE
                binding.orgContent.visibility = View.GONE
                binding.searchResultList.visibility = View.GONE
            }
            SheetMode.Building -> {
                binding.sheetTitle.text = SheetLogic.title(state, getString(R.string.zgrada))
                binding.sheetSubtitle.text = buildingCountSubtitle(SheetLogic.buildingSubtitleCount(state))
                binding.buildingContent.visibility = View.VISIBLE
                binding.orgContent.visibility = View.GONE
                binding.searchResultList.visibility = View.GONE
                val orgs = state.building?.organizations.orEmpty()
                orgAdapter.submit(orgs)
                val empty = orgs.isEmpty() && !loading
                binding.orgEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                binding.orgList.visibility = if (empty) View.GONE else View.VISIBLE
            }
            SheetMode.Organization -> {
                val org = state.org
                binding.sheetTitle.text = org?.name ?: getString(R.string.zgrada)
                binding.sheetSubtitle.text = org?.let { SheetLogic.orgSubtitle(it) } ?: ""
                binding.buildingContent.visibility = View.GONE
                binding.orgContent.visibility = View.VISIBLE
                binding.searchResultList.visibility = View.GONE
                if (org != null) {
                    bindOrg(org)
                }
            }
            SheetMode.Peek -> {
                binding.sheetTitle.text = state.peek?.title ?: ""
                binding.sheetSubtitle.text = state.peek?.subtitle ?: ""
                binding.buildingContent.visibility = View.GONE
                binding.orgContent.visibility = View.GONE
                binding.searchResultList.visibility = View.GONE
            }
            SheetMode.SearchList -> {
                binding.sheetTitle.setText(R.string.rezultati_pretrage)
                binding.sheetSubtitle.text = getString(R.string.rezultati_count, state.hits.size)
                binding.buildingContent.visibility = View.GONE
                binding.orgContent.visibility = View.GONE
                binding.searchResultList.visibility = View.VISIBLE
                searchSheetAdapter.submit(state.hits.map { SearchRow.Hit(it) })
            }
        }
        if (modeChanged && state.mode != SheetMode.Idle) {
            sheetStep = SheetAnchors.initialStep()
            setSheetState(behaviorState(sheetStep))
        }
        applySheetStepContent()
        lastSheetMode = state.mode
        binding.buildingSheet.post { updateMapControls() }
    }

    private fun buildingCountSubtitle(count: Int): String {
        return when {
            count <= 0 -> getString(R.string.nema_org)
            count == 1 -> getString(R.string.org_count_one, count)
            count in 2..4 -> getString(R.string.org_count_few, count)
            else -> getString(R.string.org_count_many, count)
        }
    }

    private fun applySheetStepContent(slideOffset: Float? = null) {
        val mode = viewModel.state.value.mode
        val loading = mode == SheetMode.Loading
        val atMinimal = if (slideOffset != null) {
            slideOffset <= 0.01f
        } else {
            sheetStep == SheetStep.Minimal &&
                sheetBehavior.state != BottomSheetBehavior.STATE_DRAGGING &&
                sheetBehavior.state != BottomSheetBehavior.STATE_SETTLING
        }
        val step = if (atMinimal) SheetStep.Minimal else {
            if (sheetStep == SheetStep.Minimal) SheetStep.Half else sheetStep
        }
        val subtitleOn = SheetAnchors.headerSubtitleVisible(mode, step) &&
            binding.sheetSubtitle.text.isNotBlank()
        binding.sheetSubtitle.visibility = if (subtitleOn) View.VISIBLE else View.GONE
        binding.sheetBody.visibility = if (SheetAnchors.bodyVisible(mode, step)) View.VISIBLE else View.GONE
        binding.sheetLoading.visibility = if (loading && step != SheetStep.Minimal) View.VISIBLE else View.GONE
    }

    private fun expandSheetByOne() {
        if (viewModel.state.value.mode == SheetMode.Idle) {
            return
        }
        val next = SheetAnchors.next(sheetStep)
        if (next == sheetStep) {
            return
        }
        sheetStep = next
        applySheetStepContent()
        setSheetState(behaviorState(next))
    }

    private fun behaviorState(step: SheetStep): Int {
        return when (step) {
            SheetStep.Minimal -> BottomSheetBehavior.STATE_COLLAPSED
            SheetStep.Half -> BottomSheetBehavior.STATE_HALF_EXPANDED
            SheetStep.Full -> BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun stepFromBehavior(state: Int): SheetStep? {
        return when (state) {
            BottomSheetBehavior.STATE_COLLAPSED -> SheetStep.Minimal
            BottomSheetBehavior.STATE_HALF_EXPANDED -> SheetStep.Half
            BottomSheetBehavior.STATE_EXPANDED -> SheetStep.Full
            else -> null
        }
    }

    private fun bindOrg(org: OrgDetailResponse) {
        bindOrgField(binding.orgAddressRow, R.string.org_field_address, org.address?.label, link = false)
        bindOrgField(binding.orgFloorRow, R.string.org_field_floor, org.floor, link = false)
        val phones = org.phones.orEmpty().filter { it.isNotBlank() }
        bindOrgField(
            binding.orgPhoneRow,
            R.string.org_field_phone,
            phones.joinToString("\n").ifBlank { null },
            link = true,
            phone = true,
        )
        bindOrgField(binding.orgHoursRow, R.string.org_field_hours, org.hours, link = false)
        bindOrgField(binding.orgWebsiteRow, R.string.org_field_website, org.website, link = true, phone = false)
    }

    private fun bindOrgField(
        row: ItemOrgFieldBinding,
        labelRes: Int,
        value: String?,
        link: Boolean,
        phone: Boolean = false,
    ) {
        row.orgFieldLabel.setText(labelRes)
        if (value.isNullOrBlank()) {
            row.root.visibility = View.GONE
            return
        }
        row.root.visibility = View.VISIBLE
        row.orgFieldValue.text = value
        row.orgFieldValue.linksClickable = link
        if (link) {
            row.orgFieldValue.setTextColor(ContextCompat.getColor(this, R.color.accent))
            row.orgFieldValue.autoLinkMask = if (phone) Linkify.PHONE_NUMBERS else Linkify.WEB_URLS
            Linkify.addLinks(row.orgFieldValue, row.orgFieldValue.autoLinkMask)
            row.orgFieldValue.movementMethod = LinkMovementMethod.getInstance()
        } else {
            row.orgFieldValue.setTextColor(ContextCompat.getColor(this, R.color.ink))
            row.orgFieldValue.autoLinkMask = 0
            row.orgFieldValue.movementMethod = null
        }
    }

    private fun setSheetState(target: Int) {
        if (target == BottomSheetBehavior.STATE_HIDDEN) {
            sheetBehavior.isHideable = true
        } else {
            binding.buildingSheet.visibility = View.VISIBLE
            if (sheetBehavior.state == BottomSheetBehavior.STATE_HIDDEN) {
                sheetBehavior.isHideable = true
            }
        }
        if (sheetBehavior.state == target) {
            if (target != BottomSheetBehavior.STATE_HIDDEN) {
                sheetBehavior.isHideable = false
            }
            return
        }
        applyingSheetState = true
        sheetBehavior.state = target
        binding.buildingSheet.postDelayed({
            applyingSheetState = false
            if (target != BottomSheetBehavior.STATE_HIDDEN) {
                sheetBehavior.isHideable = false
            }
        }, MapDefaults.SHEET_ANIMATION_MS.toLong())
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

    private fun bumpZoom(delta: Double) {
        val mapLibre = map ?: return
        val next = (mapLibre.cameraPosition.zoom + delta).coerceIn(0.0, 22.0)
        mapLibre.easeCamera(CameraUpdateFactory.zoomTo(next), MapDefaults.SHEET_ANIMATION_MS)
    }

    private fun updateDropdownMaxHeight() {
        val half = (resources.displayMetrics.heightPixels * 0.5).toInt()
        val dock = resources.getDimensionPixelSize(R.dimen.search_dock_height) + dp(12) + insetBottom
        binding.searchDropdown.maxHeightPx = min(dp(360), (half - dock).coerceAtLeast(dp(120)))
    }

    private fun updateMapControls() {
        val params = binding.mapControls.layoutParams as CoordinatorLayout.LayoutParams
        params.bottomMargin = controlsBottomMarginPx()
        params.marginEnd = resources.getDimensionPixelSize(R.dimen.margin_screen)
        binding.mapControls.layoutParams = params
        val attributionBottom = (controlsBottomMarginPx() - dp(MapDefaults.CONTROL_GAP_DP)).coerceAtLeast(dp(8))
        map?.uiSettings?.setAttributionGravity(Gravity.BOTTOM or Gravity.START)
        map?.uiSettings?.setAttributionMargins(dp(12), 0, 0, attributionBottom)
    }

    private fun dockReservePx(): Int {
        val gap = resources.getDimensionPixelSize(R.dimen.sheet_search_gap)
        val dockHeight = if (binding.searchDock.height > 0) {
            binding.searchDock.height
        } else {
            dp(MapDefaults.BOTTOM_TAB_HEIGHT_DP + 60)
        }
        val dockMargin = (binding.searchDock.layoutParams as CoordinatorLayout.LayoutParams).bottomMargin
        return dockHeight + dockMargin + gap
    }

    private fun controlsBottomMarginPx(): Int {
        val gap = dp(MapDefaults.CONTROL_GAP_DP)
        if (!::sheetBehavior.isInitialized || sheetBehavior.state == BottomSheetBehavior.STATE_HIDDEN) {
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

    private fun renderHighlight(featureCollectionJson: String?) {
        val style = mapStyle ?: return
        BuildingHighlightLayers.setGeometry(style, featureCollectionJson)
    }

    private fun renderOrgPins(state: MapUiState) {
        val style = mapStyle ?: return
        val json = if (state.pinMode == MapPinMode.Browse && state.routeMode == MapRouteMode.Idle) {
            state.orgPinsJson
        } else {
            null
        }
        OrgPinsLayers.setGeometry(style, json)
    }

    private fun renderSearchPins(state: MapUiState) {
        val style = mapStyle ?: return
        val json = if (state.pinMode == MapPinMode.SearchMulti && state.routeMode == MapRouteMode.Idle) {
            state.searchPinsJson
        } else {
            null
        }
        SearchPinsLayers.setGeometry(style, json)
    }

    private fun renderMarker(featureCollectionJson: String?) {
        val style = mapStyle ?: return
        SearchMarkerLayers.setGeometry(style, featureCollectionJson)
    }

    private fun applyPoiSearchDim(search: Boolean) {
        val style = mapStyle ?: return
        val opacity = if (search) 0.5f else 1.0f
        MapStyleFactory.POI_DOT_LAYER_IDS.forEach { id ->
            style.getLayer(id)?.setProperties(PropertyFactory.circleOpacity(opacity))
        }
        MapStyleFactory.POI_LABEL_LAYER_IDS.forEach { id ->
            style.getLayer(id)?.setProperties(PropertyFactory.textOpacity(opacity))
        }
    }

    private fun renderCamera(state: MapUiState) {
        val target = state.camera ?: return
        if (target.nonce == lastCameraNonce) {
            return
        }
        lastCameraNonce = target.nonce
        val mapLibre = map ?: return
        val height = if (mapView.height > 0) mapView.height else resources.displayMetrics.heightPixels
        val bottomPadding = SearchLogic.flyBottomPaddingPx(height, target.anchorYFromBottom).toDouble()
        val current = mapLibre.cameraPosition
        mapLibre.easeCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(target.lat, target.lon))
                    .zoom(target.zoom)
                    .tilt(current.tilt)
                    .bearing(current.bearing)
                    .padding(0.0, 0.0, 0.0, bottomPadding)
                    .build(),
            ),
            target.durationMs,
        )
        searchFlyPaddingActive = bottomPadding > 0.0
        viewModel.consumeCamera()
    }

    private fun renderBounds(state: MapUiState) {
        val target = state.bounds ?: return
        if (target.nonce == lastBoundsNonce) {
            return
        }
        lastBoundsNonce = target.nonce
        val mapLibre = map ?: return
        val points = target.points
        if (points.isEmpty()) {
            viewModel.consumeBounds()
            return
        }
        val padLeft = dp(32)
        val padRight = dp(32)
        val padTop = insetTop + dp(48)
        val padBottom = if (target.topHalf) {
            (mapView.height * MapDefaults.ROUTE_FIT_TOP_RATIO).toInt().coerceAtLeast(dockReservePx())
        } else {
            dockReservePx() + dp(MapDefaults.SHEET_STEP1_DP) + dp(24)
        }
        if (points.size == 1) {
            mapLibre.easeCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(points[0].lat, points[0].lon),
                    MapDefaults.FLY_MIN_ZOOM,
                ),
                target.durationMs,
            )
        } else {
            val builder = LatLngBounds.Builder()
            points.forEach { builder.include(LatLng(it.lat, it.lon)) }
            try {
                mapLibre.easeCamera(
                    CameraUpdateFactory.newLatLngBounds(
                        builder.build(),
                        padLeft,
                        padTop,
                        padRight,
                        padBottom,
                    ),
                    target.durationMs,
                )
            } catch (_: Exception) {
                val first = points.first()
                mapLibre.easeCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(first.lat, first.lon),
                        MapDefaults.FLY_MIN_ZOOM,
                    ),
                    target.durationMs,
                )
            }
        }
        viewModel.consumeBounds()
    }

    private fun resetSearchCameraPadding() {
        if (!searchFlyPaddingActive) {
            return
        }
        searchFlyPaddingActive = false
        val mapLibre = map ?: return
        mapLibre.easeCamera(
            CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0),
            MapDefaults.SHEET_ANIMATION_MS,
        )
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
                    map = mapLibre
                    mapLibre.setMaxPitchPreference(MapDefaults.MAX_PITCH)
                    mapLibre.uiSettings.isRotateGesturesEnabled = true
                    mapLibre.uiSettings.isTiltGesturesEnabled = true
                    mapLibre.uiSettings.isZoomGesturesEnabled = true
                    mapLibre.uiSettings.isScrollGesturesEnabled = true
                    mapLibre.uiSettings.isAttributionEnabled = true
                    mapLibre.uiSettings.isLogoEnabled = false
                    mapLibre.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(MapDefaults.LAT, MapDefaults.LON))
                        .zoom(MapDefaults.ZOOM)
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
                        mapStyle = style
                        BuildingHighlightLayers.ensure(style)
                        OrgPinsLayers.ensure(style)
                        SearchPinsLayers.ensure(style)
                        SearchMarkerLayers.ensure(style)
                        RouteLayers.ensure(style)
                        renderHighlight(viewModel.state.value.highlightJson)
                        renderOrgPins(viewModel.state.value)
                        renderSearchPins(viewModel.state.value)
                        renderMarker(viewModel.state.value.markerJson)
                        renderRouteLayers(viewModel.state.value)
                        applyPoiSearchDim(
                            viewModel.state.value.pinMode != MapPinMode.Browse ||
                                viewModel.state.value.routeMode != MapRouteMode.Idle,
                        )
                        mapLibre.addOnMapLongClickListener { latLng ->
                            viewModel.onMapLongClick(latLng.longitude, latLng.latitude)
                            true
                        }
                        mapLibre.addOnMapClickListener { latLng ->
                            hideKeyboard()
                            if (viewModel.state.value.dropdownOpen &&
                                viewModel.state.value.routePickField == null
                            ) {
                                viewModel.onSearchFocusChanged(false)
                                viewModel.onRouteFieldFocus(null)
                                binding.searchInput.clearFocus()
                                return@addOnMapClickListener true
                            }
                            val screen = mapLibre.projection.toScreenLocation(latLng)
                            val slop = dp(16).toFloat()
                            val box = RectF(screen.x - slop, screen.y - slop, screen.x + slop, screen.y + slop)
                            val routeHits = mapLibre.queryRenderedFeatures(
                                box,
                                RouteLayers.FROM_LAYER_ID,
                                RouteLayers.TO_LAYER_ID,
                            )
                            if (routeHits.isNotEmpty()) {
                                return@addOnMapClickListener true
                            }
                            val markerHits = mapLibre.queryRenderedFeatures(box, SearchMarker.LAYER_ID)
                            if (markerHits.isNotEmpty()) {
                                viewModel.onSearchMarkerClick()
                                return@addOnMapClickListener true
                            }
                            val searchPinHits = mapLibre.queryRenderedFeatures(box, SearchPins.LAYER_ID)
                            if (searchPinHits.isNotEmpty()) {
                                viewModel.onSearchPinClick(searchPinHits[0].getStringProperty("id"))
                                return@addOnMapClickListener true
                            }
                            val orgHits = mapLibre.queryRenderedFeatures(
                                box,
                                OrgPins.CIRCLE_LAYER_ID,
                                OrgPins.LABEL_LAYER_ID,
                            )
                            if (orgHits.isNotEmpty() && viewModel.state.value.routeMode == MapRouteMode.Idle) {
                                viewModel.onOrgPinClick(orgHits[0].getStringProperty("id"))
                                return@addOnMapClickListener true
                            }
                            // TODO(stage-5) transit UI — POST /v1/route
                            viewModel.onMapClick(latLng.longitude, latLng.latitude)
                            true
                        }
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
