package rs.zylos.novisad.map

object MapStyleFactory {
    val POI_DOT_LAYER_IDS = listOf(
        "poi-dot",
        "poi-dot-16",
        "poi-dot-17",
        "poi-dot-18",
        "poi-dot-19",
    )
    val POI_LABEL_LAYER_IDS = listOf(
        "poi-label",
        "poi-label-18",
        "poi-label-19",
    )

    private val mvtTiles = Regex(
        """"tiles"\s*:\s*\[\s*"/mvt/novi-sad/\{z\}/\{x\}/\{y\}\.mvt"\s*\]""",
    )
    private val waterData = Regex(""""data"\s*:\s*"/water-fill\.geojson"""")

    fun patch(rawStyle: String, mbtilesUri: String): String {
        require(rawStyle.contains("fill-extrusion")) {
            "Style JSON must include fill-extrusion (buildings-3d)"
        }
        val withTiles = mvtTiles.replace(rawStyle, """"url": "$mbtilesUri"""")
        require(withTiles != rawStyle) { "Could not patch noviSad vector source to mbtiles" }
        val withWater = waterData.replace(withTiles, """"data": "asset://${MapDefaults.WATER_ASSET}"""")
        require(withWater != withTiles) { "Could not patch waterFill GeoJSON to asset://" }
        return withWater
    }

    fun mbtilesUri(absolutePath: String): String {
        val path = if (absolutePath.startsWith("/")) absolutePath else "/$absolutePath"
        return "mbtiles://$path"
    }
}
