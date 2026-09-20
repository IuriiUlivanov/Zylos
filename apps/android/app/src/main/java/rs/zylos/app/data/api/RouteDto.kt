package rs.zylos.app.data.api

/** Mirrors apps/api/src/types/route.ts — do not change JSON field names. */
data class RouteRequest(
    val from: LonLat,
    val to: LonLat,
    val mode: String = "transit",
)

data class RouteLineString(
    val type: String = "LineString",
    val coordinates: List<List<Double>> = emptyList(),
)

data class RouteLeg(
    val mode: String,
    val duration_sec: Int,
    val distance_m: Double,
    val geometry: RouteLineString,
    val route_short_name: String? = null,
    val route_color: String? = null,
    val from_stop_name: String? = null,
    val to_stop_name: String? = null,
    val headsign: String? = null,
)

data class RouteItinerary(
    val duration_sec: Int,
    val distance_m: Double,
    val transfers: Int,
    val walk_duration_sec: Int = 0,
    val legs: List<RouteLeg> = emptyList(),
)

data class RouteResponse(
    val mode: String,
    val duration_sec: Int,
    val distance_m: Double,
    val transfers: Int,
    val legs: List<RouteLeg>,
    val itineraries: List<RouteItinerary> = emptyList(),
)

data class RouteErrorBody(
    val error: String,
)
