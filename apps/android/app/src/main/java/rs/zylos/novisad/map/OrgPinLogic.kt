package rs.zylos.novisad.map

import rs.zylos.novisad.data.api.OrgPin
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class MapBbox(
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double,
) {
    val centerLon: Double get() = (minLon + maxLon) / 2.0
    val centerLat: Double get() = (minLat + maxLat) / 2.0

    fun asQuery(): String = "$minLon,$minLat,$maxLon,$maxLat"
}

data class RankedOrgPin(
    val pin: OrgPin,
    val displayRank: Int,
    val labeled: Boolean,
)

/** Zoom → limit / debounce tables — Docs/design/MOBILE-POI-ZOOM.md §4.3. */
object OrgPinLimits {
    fun debounceMs(zoom: Double): Long {
        val band = zoomBand(zoom)
        return when {
            band <= 0 -> MapDefaults.ORG_PINS_DEBOUNCE_MS
            band <= 17 -> MapDefaults.ORG_PINS_DEBOUNCE_MS
            band == 18 -> MapDefaults.ORG_PINS_DEBOUNCE_MS_Z18
            else -> MapDefaults.ORG_PINS_DEBOUNCE_MS_Z19
        }
    }

    fun limit(zoom: Double): Int {
        return when (zoomBand(zoom)) {
            15 -> 40
            16 -> 80
            17 -> 120
            18 -> 160
            19 -> MapDefaults.ORG_PINS_LIMIT_MAX
            else -> 0
        }
    }

    fun maxDisplayRank(zoom: Double): Int? {
        return when (zoomBand(zoom)) {
            15 -> 20
            16 -> 35
            17 -> 50
            18 -> 70
            19 -> 90
            else -> null
        }
    }

    fun labelTopN(zoom: Double): Int {
        return when (zoomBand(zoom)) {
            17 -> 20
            18 -> 40
            19 -> 60
            else -> 0
        }
    }

    fun shouldRequest(zoom: Double): Boolean {
        return zoom >= MapDefaults.ORG_PINS_MIN_ZOOM
    }

    fun zoomBand(zoom: Double): Int {
        val z = floor(zoom).toInt()
        return when {
            z < MapDefaults.ORG_PINS_MIN_ZOOM -> 0
            z <= 15 -> 15
            z == 16 -> 16
            z == 17 -> 17
            z == 18 -> 18
            else -> 19
        }
    }
}

object OrgPinLogic {
    fun categoryTier(categorySlug: String?): Int {
        val slug = categorySlug?.trim()?.takeIf { it.isNotEmpty() } ?: return 90
        return TIER[slug] ?: 90
    }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2.0 * EARTH_KM * asin(min(1.0, sqrt(a)))
    }

    fun displayRank(
        categorySlug: String?,
        lon: Double,
        lat: Double,
        centerLon: Double,
        centerLat: Double,
    ): Int {
        val tier = categoryTier(categorySlug)
        val distance = distanceKm(lat, lon, centerLat, centerLon)
        return tier + floor(distance * 3.0).toInt()
    }

    fun visiblePins(pins: List<OrgPin>, zoom: Double, bbox: MapBbox): List<RankedOrgPin> {
        val maxRank = OrgPinLimits.maxDisplayRank(zoom) ?: return emptyList()
        val ranked = pins
            .map { pin ->
                RankedOrgPin(
                    pin = pin,
                    displayRank = displayRank(pin.category_slug, pin.lon, pin.lat, bbox.centerLon, bbox.centerLat),
                    labeled = false,
                )
            }
            .sortedBy { it.displayRank }
            .filter { it.displayRank <= maxRank }
        val topN = OrgPinLimits.labelTopN(zoom)
        return ranked.mapIndexed { index, pin -> pin.copy(labeled = topN > 0 && index < topN) }
    }

    fun colorForCategory(categorySlug: String?): String {
        return when (categorySlug) {
            "hospital", "clinic", "doctors", "dentist", "veterinary", "pharmacy" -> "#E23B3B"
            "restaurant", "cafe", "fast_food", "bar", "pub", "marketplace", "food_court" -> "#E86B1A"
            "supermarket", "convenience", "bakery", "butcher", "mall", "shop", "clothes",
            "electronics", "hardware", "beauty", "hairdresser",
            -> "#3DAA4B"
            "bank" -> "#2F7DE1"
            "fuel" -> "#5B6B7A"
            "school", "kindergarten", "university", "college", "library" -> "#C48A3A"
            "parking", "car_wash", "car_rental", "driving_school" -> "#5B6B7A"
            "theatre", "cinema", "place_of_worship", "attraction" -> "#7B5EA7"
            else -> "#5B8FA8"
        }
    }

    private const val EARTH_KM = 6371.0

    private val TIER = mapOf(
        "hospital" to 10,
        "clinic" to 10,
        "doctors" to 10,
        "dentist" to 10,
        "veterinary" to 10,
        "pharmacy" to 15,
        "police" to 15,
        "fire_station" to 15,
        "post_office" to 15,
        "townhall" to 15,
        "embassy" to 15,
        "courthouse" to 15,
        "bank" to 20,
        "fuel" to 20,
        "school" to 20,
        "kindergarten" to 20,
        "university" to 20,
        "college" to 20,
        "library" to 20,
        "theatre" to 20,
        "cinema" to 20,
        "place_of_worship" to 20,
        "restaurant" to 30,
        "cafe" to 30,
        "fast_food" to 30,
        "bar" to 30,
        "pub" to 30,
        "marketplace" to 30,
        "food_court" to 30,
        "supermarket" to 40,
        "convenience" to 40,
        "bakery" to 40,
        "butcher" to 40,
        "mall" to 40,
        "shop" to 50,
        "clothes" to 50,
        "electronics" to 50,
        "hardware" to 50,
        "beauty" to 50,
        "hairdresser" to 50,
        "parking" to 60,
        "car_wash" to 60,
        "car_rental" to 60,
        "driving_school" to 60,
        "office" to 70,
        "coworking_space" to 70,
        "lawyer" to 70,
        "accountant" to 70,
        "other" to 90,
    )
}
