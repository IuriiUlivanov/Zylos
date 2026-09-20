package rs.zylos.novisad.ui.dock

import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import rs.zylos.novisad.databinding.ActivityMapBinding
import rs.zylos.novisad.map.MapDefaults
import kotlin.math.min

/**
 * Measures the bottom UI stack: tab bar (fixed) → search/route panel → dynamic overlays.
 * Each layer is flush with the one below; only [tabBar] has a fixed height.
 */
object BottomStackInsets {

    fun dockReservePx(binding: ActivityMapBinding, dp: (Int) -> Int): Int {
        val dockHeight = measuredDockHeightPx(binding, dp)
        val dockMargin = (binding.searchDock.layoutParams as CoordinatorLayout.LayoutParams).bottomMargin
        return dockHeight + dockMargin
    }

    /** Tab bar + active search or route panel (excludes dropdown / route results). */
    fun panelStackHeightPx(binding: ActivityMapBinding, dp: (Int) -> Int): Int {
        var height = binding.tabBar.height
        if (height <= 0) {
            height = dp(MapDefaults.BOTTOM_TAB_HEIGHT_DP)
        }
        if (binding.searchChrome.visibility == View.VISIBLE) {
            height += binding.searchChrome.height.takeIf { it > 0 }
                ?: dp(MapDefaults.SHEET_STEP1_DP) + dp(1)
        }
        if (binding.routeChrome.root.visibility == View.VISIBLE) {
            height += binding.routeChrome.root.height.takeIf { it > 0 }
                ?: dp(MapDefaults.ROUTE_PANEL_HEIGHT_DP)
        }
        return height
    }

    fun dropdownMaxHeightPx(
        binding: ActivityMapBinding,
        insetTop: Int,
        insetBottom: Int,
        dp: (Int) -> Int,
    ): Int {
        val parentHeight = binding.coordinator.height.takeIf { it > 0 }
            ?: binding.root.resources.displayMetrics.heightPixels
        val dockMargin = (binding.searchDock.layoutParams as CoordinatorLayout.LayoutParams).bottomMargin
        val panelHeight = panelStackHeightPx(binding, dp)
        val available = parentHeight - insetTop - panelHeight - dockMargin
        return min(dp(360), available.coerceAtLeast(dp(120)))
    }

    fun routeResultsFullHeightPx(
        binding: ActivityMapBinding,
        insetTop: Int,
        insetBottom: Int,
        dp: (Int) -> Int,
    ): Int {
        val parentHeight = binding.coordinator.height.takeIf { it > 0 }
            ?: binding.root.resources.displayMetrics.heightPixels
        return (parentHeight - insetTop - panelStackHeightPx(binding, dp) -
            (binding.searchDock.layoutParams as CoordinatorLayout.LayoutParams).bottomMargin)
            .coerceAtLeast(dp(MapDefaults.ROUTE_SHEET_STEP2_DP))
    }

    private fun measuredDockHeightPx(binding: ActivityMapBinding, dp: (Int) -> Int): Int {
        return binding.searchDock.height.takeIf { it > 0 } ?: fallbackDockHeightPx(binding, dp)
    }

    private fun fallbackDockHeightPx(binding: ActivityMapBinding, dp: (Int) -> Int): Int {
        return panelStackHeightPx(binding, dp)
    }
}
