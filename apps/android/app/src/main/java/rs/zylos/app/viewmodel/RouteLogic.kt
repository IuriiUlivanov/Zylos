package rs.zylos.app.viewmodel

import rs.zylos.app.data.api.LonLat
import rs.zylos.app.data.api.RouteItinerary
import rs.zylos.app.data.api.RouteLeg
import rs.zylos.app.data.api.RouteResponse
import rs.zylos.app.map.GeoJsonText
import rs.zylos.app.map.MapDefaults
import kotlin.math.roundToInt

data class RoutePoint(
    val lon: Double,
    val lat: Double,
    val label: String,
)

enum class BottomTab {
    Search,
    Route,
}

enum class MapRouteMode {
    Idle,
    Planning,
    Result,
}

enum class RouteField {
    From,
    To,
}

enum class RouteUiError {
    OutsideCity,
    Timeout,
    NoRoute,
    Offline,
}

object RouteLogic {
    private val FALLBACK_COLORS = listOf(
        "#E30613",
        "#1565C0",
        "#F9A825",
        "#6A1B9A",
        "#00897B",
        "#EF6C00",
    )

    fun minutes(durationSec: Int): Int {
        return maxOf(1, (durationSec / 60.0).roundToInt()).takeIf { durationSec > 0 } ?: 0
    }

    fun formatDuration(durationSec: Int): String {
        val min = minutes(durationSec)
        return "$min min"
    }

    fun formatSummary(durationSec: Int, transfers: Int): String {
        val time = formatDuration(durationSec)
        val transfer = if (transfers <= 0) "bez presedanja" else "$transfers presedanja"
        return "$time · $transfer"
    }

    fun formatWalkLeg(durationSec: Int): String {
        return "Pešačenje · ${minutes(durationSec)} min"
    }

    fun formatTransitLeg(shortName: String?, durationSec: Int): String {
        val num = shortName?.takeIf { it.isNotBlank() } ?: ""
        val prefix = if (num.isEmpty()) "Autobus" else "Autobus $num"
        return "$prefix · ${minutes(durationSec)} min"
    }

    fun formatLeg(leg: RouteLeg): String {
        return if (leg.mode == "walk") {
            formatWalkLeg(leg.duration_sec)
        } else {
            formatTransitLeg(leg.route_short_name, leg.duration_sec)
        }
    }

    fun walkDuration(itinerary: RouteItinerary): Int {
        if (itinerary.walk_duration_sec > 0) {
            return itinerary.walk_duration_sec
        }
        return itinerary.legs.filter { it.mode == "walk" }.sumOf { it.duration_sec }
    }

    fun itinerariesOf(response: RouteResponse): List<RouteItinerary> {
        val listed = response.itineraries.filter { it.legs.isNotEmpty() }
        if (listed.isNotEmpty()) {
            return listed
        }
        if (response.legs.isEmpty()) {
            return emptyList()
        }
        return listOf(
            RouteItinerary(
                duration_sec = response.duration_sec,
                distance_m = response.distance_m,
                transfers = response.transfers,
                walk_duration_sec = response.legs.filter { it.mode == "walk" }.sumOf { it.duration_sec },
                legs = response.legs,
            ),
        )
    }

    fun sortItineraries(items: List<RouteItinerary>): List<RouteItinerary> {
        return items.mapIndexed { index, item -> item to index }
            .sortedWith(
                compareBy<Pair<RouteItinerary, Int>> { it.first.duration_sec }
                    .thenBy { it.first.transfers }
                    .thenBy { walkDuration(it.first) }
                    .thenBy { it.second },
            )
            .map { it.first }
    }

    fun cssColor(routeColor: String?, index: Int): String {
        val hex = routeColor?.trim()?.removePrefix("#")?.uppercase()
        if (hex != null && hex.matches(Regex("[0-9A-F]{6}"))) {
            return "#$hex"
        }
        return FALLBACK_COLORS[index.mod(FALLBACK_COLORS.size)]
    }

    fun bothPointsReady(from: RoutePoint?, to: RoutePoint?): Boolean {
        return from != null && to != null
    }

    fun boundsPoints(itinerary: RouteItinerary, from: RoutePoint?, to: RoutePoint?): List<LonLat> {
        val points = mutableListOf<LonLat>()
        itinerary.legs.forEach { leg ->
            leg.geometry.coordinates.forEach { pair ->
                if (pair.size >= 2) {
                    points.add(LonLat(pair[0], pair[1]))
                }
            }
        }
        if (from != null) {
            points.add(LonLat(from.lon, from.lat))
        }
        if (to != null) {
            points.add(LonLat(to.lon, to.lat))
        }
        return points
    }

    fun walkCollectionJson(itinerary: RouteItinerary): String {
        val features = itinerary.legs.mapIndexedNotNull { _, leg ->
            if (leg.mode != "walk") {
                return@mapIndexedNotNull null
            }
            lineFeature(leg.geometry.coordinates, MapDefaults.ROUTE_WALK_COLOR, null)
        }
        return featureCollection(features)
    }

    fun transitCollectionJson(itinerary: RouteItinerary): String {
        var transitIndex = 0
        val features = itinerary.legs.mapNotNull { leg ->
            if (leg.mode != "transit") {
                return@mapNotNull null
            }
            val color = cssColor(leg.route_color, transitIndex)
            transitIndex += 1
            lineFeature(leg.geometry.coordinates, color, leg.route_short_name)
        }
        return featureCollection(features)
    }

    fun labelsCollectionJson(itinerary: RouteItinerary): String {
        var transitIndex = 0
        val features = itinerary.legs.mapNotNull { leg ->
            if (leg.mode != "transit") {
                return@mapNotNull null
            }
            val name = leg.route_short_name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val mid = midpoint(leg.geometry.coordinates) ?: return@mapNotNull null
            val color = cssColor(leg.route_color, transitIndex)
            transitIndex += 1
            """{"type":"Feature","properties":{"name":"${GeoJsonText.escape(name)}","color":"$color"},"geometry":{"type":"Point","coordinates":[${mid.first},${mid.second}]}}"""
        }
        return featureCollection(features)
    }

    fun pointCollectionJson(lon: Double, lat: Double): String {
        return """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[$lon,$lat]}}]}"""
    }

    fun emptyCollectionJson(): String = """{"type":"FeatureCollection","features":[]}"""

    private fun midpoint(coords: List<List<Double>>): Pair<Double, Double>? {
        val valid = coords.filter { it.size >= 2 }
        if (valid.isEmpty()) {
            return null
        }
        val mid = valid[valid.size / 2]
        return mid[0] to mid[1]
    }

    private fun lineFeature(coords: List<List<Double>>, color: String, name: String?): String? {
        val valid = coords.filter { it.size >= 2 }
        if (valid.size < 2) {
            return null
        }
        val encoded = valid.joinToString(",") { "[${it[0]},${it[1]}]" }
        val nameProp = name?.let { ",\"name\":\"${GeoJsonText.escape(it)}\"" } ?: ""
        return """{"type":"Feature","properties":{"color":"$color"$nameProp},"geometry":{"type":"LineString","coordinates":[$encoded]}}"""
    }

    private fun featureCollection(features: List<String?>): String {
        val body = features.filterNotNull().joinToString(",")
        return """{"type":"FeatureCollection","features":[$body]}"""
    }
}
