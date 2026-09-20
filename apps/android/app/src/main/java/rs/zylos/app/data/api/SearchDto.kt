package rs.zylos.app.data.api

/** Mirrors apps/api/src/types/search.ts — do not change JSON field names. */
enum class SearchKind {
    address,
    organization,
}

data class SearchHit(
    val id: String,
    val kind: SearchKind,
    val label: String,
    val lat: Double,
    val lon: Double,
    val building_id: String?,
    val name: String? = null,
    val category_slug: String? = null,
)

data class SearchResponse(
    val query: String,
    val hits: List<SearchHit>,
    val processingTimeMs: Int,
)

data class SearchErrorBody(
    val error: String,
)
