package rs.zylos.novisad.map

import rs.zylos.novisad.data.api.SearchHit

/**
 * GeoJSON for Search Multi pins. Color is distinct from browse org-pins.
 */
object SearchPins {
    const val SOURCE_ID = "search-pins"
    const val LAYER_ID = "search-pins"
    const val FILL_COLOR = "#1565C0"
    const val STROKE_COLOR = "#FFFFFF"
    const val RADIUS = 9.0
    const val STROKE_WIDTH = 2.2

    fun emptyCollectionJson(): String = """{"type":"FeatureCollection","features":[]}"""

    fun collectionJson(hits: List<SearchHit>): String {
        val limited = hits.take(MapDefaults.SEARCH_MULTI_MAX_PINS)
        if (limited.isEmpty()) {
            return emptyCollectionJson()
        }
        val features = limited.joinToString(",") { hit ->
            """{"type":"Feature","properties":{"id":"${GeoJsonText.escape(hit.id)}","name":"${GeoJsonText.escape(hit.label)}"},"geometry":{"type":"Point","coordinates":[${hit.lon},${hit.lat}]}}"""
        }
        return """{"type":"FeatureCollection","features":[$features]}"""
    }
}
