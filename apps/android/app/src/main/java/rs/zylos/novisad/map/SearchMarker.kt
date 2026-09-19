package rs.zylos.novisad.map

/**
 * GeoJSON for the selected-marker MapLibre source (search hit).
 * Distinct from selected-building (PostGIS contour).
 */
object SearchMarker {
    const val SOURCE_ID = "selected-marker"
    const val LAYER_ID = "selected-marker"
    const val FILL_COLOR = "#00B341"
    const val STROKE_COLOR = "#FFFFFF"
    const val RADIUS = 8.0
    const val STROKE_WIDTH = 2.2

    fun emptyCollectionJson(): String = """{"type":"FeatureCollection","features":[]}"""

    fun pointJson(lon: Double, lat: Double): String {
        return """{"type":"FeatureCollection","features":[{"type":"Feature","id":1,"properties":{},"geometry":{"type":"Point","coordinates":[$lon,$lat]}}]}"""
    }
}
