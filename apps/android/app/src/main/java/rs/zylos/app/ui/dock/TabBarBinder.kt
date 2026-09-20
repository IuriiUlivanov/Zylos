package rs.zylos.app.ui.dock

import android.view.View
import androidx.core.content.ContextCompat
import rs.zylos.app.R
import rs.zylos.app.databinding.ActivityMapBinding
import rs.zylos.app.viewmodel.BottomTab
import rs.zylos.app.viewmodel.MapRouteMode
import rs.zylos.app.viewmodel.MapUiState
import rs.zylos.app.viewmodel.MapViewModel

class TabBarBinder(
    private val binding: ActivityMapBinding,
    private val viewModel: MapViewModel,
    private val requestMyLocation: () -> Unit,
) {
    fun bind() {
        binding.tabSearch.setOnClickListener { viewModel.onSelectTab(BottomTab.Search) }
        binding.tabRoute.setOnClickListener {
            viewModel.onSelectTab(BottomTab.Route)
            if (viewModel.state.value.route.from == null) {
                requestMyLocation()
            }
        }
        binding.tabBuildCta.setOnClickListener { viewModel.onBuildCta() }
    }

    fun render(state: MapUiState) {
        val routeTab = state.route.bottomTab == BottomTab.Route
        val showCta = routeTab && state.route.canBuild && state.route.mode != MapRouteMode.Result
        binding.tabBuildCta.visibility = if (showCta) View.VISIBLE else View.GONE
        binding.tabRoute.visibility = if (showCta) View.GONE else View.VISIBLE
        val context = binding.root.context
        val searchColor = if (!routeTab) R.color.accent else R.color.muted
        val routeColor = if (routeTab && !showCta) R.color.accent else R.color.muted
        binding.tabSearchIcon.imageTintList = ContextCompat.getColorStateList(context, searchColor)
        binding.tabSearchLabel.setTextColor(ContextCompat.getColor(context, searchColor))
        binding.tabRouteIcon.imageTintList = ContextCompat.getColorStateList(context, routeColor)
        binding.tabRouteLabel.setTextColor(ContextCompat.getColor(context, routeColor))
        binding.tabBuildCta.isEnabled = !state.route.loading
        binding.tabBuildCta.text = if (state.route.loading) {
            context.getString(R.string.ucitavam)
        } else {
            context.getString(R.string.ruta_napravi)
        }
    }
}
