package rs.zylos.app.viewmodel

import kotlin.math.roundToInt
import rs.zylos.app.data.api.SearchHit
import rs.zylos.app.data.api.SearchKind
import rs.zylos.app.map.MapDefaults
import rs.zylos.app.ui.CategoryLabels

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

    fun isMultiEligible(hits: List<SearchHit>): Boolean {
        if (hits.size < MapDefaults.SEARCH_MULTI_MIN_HITS) {
            return false
        }
        val orgs = hits.filter { it.kind == SearchKind.organization }
        if (orgs.size < MapDefaults.SEARCH_MULTI_MIN_HITS) {
            return false
        }
        val slugs = orgs.mapNotNull { it.category_slug?.trim()?.takeIf { slug -> slug.isNotEmpty() } }.distinct()
        return slugs.size == 1
    }

    fun multiPins(hits: List<SearchHit>): List<SearchHit> {
        return hits.take(MapDefaults.SEARCH_MULTI_MAX_PINS)
    }
}
