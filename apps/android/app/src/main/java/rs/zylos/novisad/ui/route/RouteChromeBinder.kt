package rs.zylos.novisad.ui.route

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import rs.zylos.novisad.R
import rs.zylos.novisad.databinding.ActivityMapBinding
import rs.zylos.novisad.map.MapDefaults
import rs.zylos.novisad.ui.dock.BottomStackInsets
import rs.zylos.novisad.viewmodel.BottomTab
import rs.zylos.novisad.viewmodel.MapRouteMode
import rs.zylos.novisad.viewmodel.MapUiState
import rs.zylos.novisad.viewmodel.MapViewModel
import rs.zylos.novisad.viewmodel.RouteField

class RouteChromeBinder(
    private val binding: ActivityMapBinding,
    private val viewModel: MapViewModel,
    private val dp: (Int) -> Int,
    private val insetTop: () -> Int,
    private val insetBottom: () -> Int,
    private val requestMyLocation: () -> Unit,
) {
    private var applyingRouteText = false
    private var applyingRoutePager = false
    var routeSheetStep = 2
        private set
    private var lastRouteMode: MapRouteMode = MapRouteMode.Idle

    private val routeAdapter = RouteSheetAdapter()

    fun bind() {
        binding.tabRoute.setOnClickListener {
            viewModel.onSelectTab(BottomTab.Route)
            if (viewModel.state.value.route.from == null) {
                requestMyLocation()
            }
        }
        binding.tabBuildCta.setOnClickListener { viewModel.onBuildCta() }
        binding.routeClose.setOnClickListener { viewModel.onClearRoute() }
        binding.routeChrome.routeSwap.setOnClickListener { viewModel.onSwapRoute() }
        binding.routeChrome.routeFromMap.setOnClickListener { viewModel.onNaKartu(RouteField.From) }
        binding.routeChrome.routeToMap.setOnClickListener { viewModel.onNaKartu(RouteField.To) }
        binding.routeChrome.routeMyLocation.setOnClickListener { requestMyLocation() }
        binding.routeHandle.setOnClickListener { cycleRouteSheetStep() }
        binding.routePager.adapter = routeAdapter
        binding.routePager.registerOnPageChangeCallback(
            object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    if (!applyingRoutePager) {
                        viewModel.onSelectItinerary(position)
                    }
                }
            },
        )
        bindRouteField(binding.routeChrome.routeFromInput, RouteField.From)
        bindRouteField(binding.routeChrome.routeToInput, RouteField.To)
    }

    fun render(state: MapUiState) {
        val routeTab = state.route.bottomTab == BottomTab.Route
        binding.searchChrome.visibility = if (routeTab) View.GONE else View.VISIBLE
        binding.routeChrome.root.visibility = if (routeTab) View.VISIBLE else View.GONE
        binding.routeChrome.routeSpinner.visibility = if (state.route.loading) View.VISIBLE else View.GONE
        binding.routeChrome.routeMyLocation.isEnabled = state.route.gpsEnabled
        binding.routeChrome.routeMyLocation.alpha = if (state.route.gpsEnabled) 1f else 0.4f
        setRouteText(binding.routeChrome.routeFromInput, state.route.fromQuery)
        setRouteText(binding.routeChrome.routeToInput, state.route.toQuery)
        val result = state.route.mode == MapRouteMode.Result
        if (result && lastRouteMode != MapRouteMode.Result) {
            routeSheetStep = 2
        }
        lastRouteMode = state.route.mode
        binding.routeResults.visibility = if (result && routeTab) View.VISIBLE else View.GONE
        if (result && routeTab) {
            routeAdapter.submit(state.route.itineraries)
            if (binding.routePager.currentItem != state.route.activeItineraryIndex) {
                applyingRoutePager = true
                binding.routePager.setCurrentItem(state.route.activeItineraryIndex, false)
                applyingRoutePager = false
            }
            bindRouteDots(state)
            val step1 = routeSheetStep == 1
            binding.routeStep1Summary.visibility = if (step1) View.VISIBLE else View.GONE
            binding.routePager.visibility = if (step1) View.GONE else View.VISIBLE
            val fromLabel = state.route.from?.label.orEmpty()
            val toLabel = state.route.to?.label.orEmpty()
            binding.routeStep1Summary.text = "$fromLabel → $toLabel"
            applyRouteResultsHeight()
        }
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
        val count = state.route.itineraries.size
        if (count <= 1) {
            host.visibility = View.GONE
            return
        }
        host.visibility = View.VISIBLE
        val context = binding.root.context
        repeat(count) { index ->
            val dot = View(context)
            val size = dp(8)
            val params = LinearLayout.LayoutParams(size, size)
            params.marginStart = dp(4)
            params.marginEnd = dp(4)
            dot.layoutParams = params
            dot.background = ContextCompat.getDrawable(
                context,
                if (index == state.route.activeItineraryIndex) R.drawable.bg_dot_on else R.drawable.bg_dot_off,
            )
            host.addView(dot)
        }
    }

    private fun applyRouteResultsHeight() {
        val params = binding.routeResults.layoutParams
        params.height = when (routeSheetStep) {
            1 -> dp(MapDefaults.ROUTE_SHEET_STEP1_DP)
            3 -> BottomStackInsets.routeResultsFullHeightPx(binding, insetTop(), insetBottom(), dp)
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
}
