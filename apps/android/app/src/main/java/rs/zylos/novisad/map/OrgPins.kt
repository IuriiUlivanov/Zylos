package rs.zylos.novisad.map

/**
 * GeoJSON for the browse org-pins MapLibre source (`GET /v1/orgs?bbox=`).
 */
object OrgPins {
    const val SOURCE_ID = "org-pins"
    const val CIRCLE_LAYER_ID = "org-pins"
    const val LABEL_LAYER_ID = "org-pins-label"
    const val STROKE_COLOR = "#FFFFFF"
    const val STROKE_WIDTH = 1.2

    fun emptyCollectionJson(): String = """{"type":"FeatureCollection","features":[]}"""

    fun collectionJson(pins: List<RankedOrgPin>): String {
        if (pins.isEmpty()) {
            return emptyCollectionJson()
        }
        val features = pins.joinToString(",") { ranked ->
            val pin = ranked.pin
            val label = if (ranked.labeled) GeoJsonText.escape(pin.name) else ""
            val slug = pin.category_slug?.let { GeoJsonText.escape(it) } ?: ""
            val color = OrgPinLogic.colorForCategory(pin.category_slug)
            """{"type":"Feature","properties":{"id":"${GeoJsonText.escape(pin.id)}","name":"${GeoJsonText.escape(pin.name)}","category_slug":"$slug","display_rank":${ranked.displayRank},"label":"$label","color":"$color"},"geometry":{"type":"Point","coordinates":[${pin.lon},${pin.lat}]}}"""
        }
        return """{"type":"FeatureCollection","features":[$features]}"""
    }
}

object GeoJsonText {
    fun escape(value: String): String {
        return buildString(value.length) {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
    }
}
