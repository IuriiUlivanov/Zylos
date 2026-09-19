package rs.zylos.novisad.map

import rs.zylos.novisad.data.api.BuildingGeometry

/**
 * GeoJSON for the selected-building MapLibre source.
 * Contour comes from GET /v1/buildings/:id (PostGIS), never from MVT.
 */
object BuildingHighlight {
    const val SOURCE_ID = "selected-building"
    const val FILL_LAYER_ID = "selected-building"
    const val OUTLINE_LAYER_ID = "selected-building-outline"
    const val FILL_COLOR = "#00B341"
    const val OUTLINE_COLOR = "#008A32"
    const val FILL_OPACITY = 0.38
    const val OUTLINE_WIDTH = 3.0

    fun emptyCollectionJson(): String = """{"type":"FeatureCollection","features":[]}"""

    fun collectionJson(geometry: BuildingGeometry): String {
        return collectionJson(geometry.toGeoJsonObject())
    }

    fun collectionJson(geometryObjectJson: String): String {
        return """{"type":"FeatureCollection","features":[{"type":"Feature","id":1,"properties":{},"geometry":$geometryObjectJson}]}"""
    }
}
