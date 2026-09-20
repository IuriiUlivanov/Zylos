package rs.zylos.app.data.api

import kotlin.jvm.Transient

/** Mirrors apps/api/src/types/building.ts — do not change JSON field names. */
data class BuildingAtResponse(
    val id: String,
    val label: String,
)

data class BuildingAddress(
    val id: String,
    val label: String,
    val street: String?,
    val housenumber: String,
    val source: String,
)

data class BuildingOrgListItem(
    val id: String,
    val name: String,
    val category_slug: String?,
    val category_name: String?,
    val floor: String?,
)

data class BuildingGeometry(
    val type: String,
    val coordinates: Any?,
    @Transient val encodedJson: String = "",
) {
    fun toGeoJsonObject(): String {
        if (encodedJson.isNotBlank()) {
            return encodedJson
        }
        return """{"type":"$type"}"""
    }
}

data class BuildingDetailResponse(
    val id: String,
    val name: String?,
    val centroid: LonLat,
    val geometry: BuildingGeometry,
    val addresses: List<BuildingAddress>,
    val organizations: List<BuildingOrgListItem>,
)

data class LonLat(
    val lon: Double,
    val lat: Double,
)
