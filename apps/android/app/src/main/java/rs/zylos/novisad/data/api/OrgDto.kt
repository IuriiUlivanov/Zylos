package rs.zylos.novisad.data.api

/** Mirrors apps/api/src/types/org.ts — do not change JSON field names. */
data class OrgPin(
    val id: String,
    val name: String,
    val category_slug: String?,
    val lon: Double,
    val lat: Double,
)

data class OrgAddress(
    val label: String,
    val street: String?,
    val housenumber: String?,
)

data class OrgDetailResponse(
    val id: String,
    val name: String,
    val source: String,
    val category_slug: String?,
    val category_name: String?,
    val phones: List<String>? = null,
    val website: String?,
    val hours: String?,
    val floor: String?,
    val tags: List<String>? = null,
    val address: OrgAddress?,
    val building_id: String?,
    val location: LonLat,
)
