package rs.zylos.novisad.viewmodel

import kotlin.math.roundToInt
import rs.zylos.novisad.data.api.SearchHit
import rs.zylos.novisad.data.api.SearchKind
import rs.zylos.novisad.map.MapDefaults
import rs.zylos.novisad.ui.CategoryLabels

enum class HitSheet {
    Building,
    Organization,
    Peek,
}

object SearchLogic {
    fun shouldRequest(query: String): Boolean {
        return query.length >= MapDefaults.SEARCH_MIN_LENGTH
    }

    fun destination(
        hit: SearchHit,
        buildingLoaded: Boolean,
        orgLoaded: Boolean,
    ): HitSheet {
        if (hit.kind == SearchKind.organization && orgLoaded) {
            return HitSheet.Organization
        }
        if (buildingLoaded) {
            return HitSheet.Building
        }
        return HitSheet.Peek
    }

    fun peekSubtitle(hit: SearchHit, addressLabel: String, orgFallback: String): String {
        return if (hit.kind == SearchKind.organization) {
            CategoryLabels.label(hit.category_slug) ?: orgFallback
        } else {
            addressLabel
        }
    }

    fun flyZoom(currentZoom: Double): Double {
        return maxOf(currentZoom, MapDefaults.FLY_MIN_ZOOM)
    }

    /**
     * Bottom camera padding so the target sits at [anchorYFromBottom] of map height
     * (from the bottom). Default 0.75 = centre of the remaining top half when the
     * sheet covers the bottom half — Docs/design/object-card/README.md.
     */
    fun flyBottomPaddingPx(
        mapHeightPx: Int,
        anchorYFromBottom: Float = MapDefaults.SEARCH_FLYTO_ANCHOR_Y,
    ): Int {
        if (mapHeightPx <= 0) {
            return 0
        }
        return (mapHeightPx * (2.0 * anchorYFromBottom - 1.0)).roundToInt().coerceAtLeast(0)
    }
}
