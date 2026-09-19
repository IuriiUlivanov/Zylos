package rs.zylos.novisad.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStyleFactoryTest {
    @Test
    fun patchReplacesMvtAndWaterWithLocalSources() {
        val raw = """
            {
              "sources": {
                "noviSad": {
                  "type": "vector",
                  "tiles": ["/mvt/novi-sad/{z}/{x}/{y}.mvt"],
                  "maxzoom": 14
                },
                "waterFill": { "type": "geojson", "data": "/water-fill.geojson" }
              },
              "layers": [{ "id": "buildings-3d", "type": "fill-extrusion" }]
            }
        """.trimIndent()

        val patched = MapStyleFactory.patch(raw, "mbtiles:///data/novi-sad.mbtiles")

        assertTrue(patched.contains("\"url\": \"mbtiles:///data/novi-sad.mbtiles\""))
        assertFalse(patched.contains("/mvt/novi-sad"))
        assertTrue(patched.contains("asset://water-fill.geojson"))
        assertTrue(patched.contains("fill-extrusion"))
    }

    @Test
    fun mbtilesUriUsesThreeSlashesForAbsolutePath() {
        assertEquals(
            "mbtiles:///data/user/0/rs.zylos.novisad/files/maps/novi-sad.mbtiles",
            MapStyleFactory.mbtilesUri("/data/user/0/rs.zylos.novisad/files/maps/novi-sad.mbtiles"),
        )
    }

    @Test
    fun mobileStyleKeepsPoiRankAndClassFilters() {
        val style = loadMobileStyle()
        assertTrue(style.contains("\"id\": \"poi-dot\""))
        assertTrue(style.contains("\"id\": \"poi-label\""))
        assertTrue(style.contains("""["<=", ["coalesce", ["get", "rank"], 99], 25]"""))
        assertTrue(style.contains("""["<=", ["coalesce", ["get", "rank"], 99], 8]"""))
        assertTrue(style.contains("\"hospital\""))
        assertTrue(style.contains("\"pharmacy\""))
        assertEquals(
            listOf("poi-dot", "poi-dot-16", "poi-dot-17", "poi-dot-18", "poi-dot-19"),
            MapStyleFactory.POI_DOT_LAYER_IDS,
        )
        val patched = MapStyleFactory.patch(style, "mbtiles:///data/novi-sad.mbtiles")
        assertTrue(patched.contains("""["<=", ["coalesce", ["get", "rank"], 99], 25]"""))
        assertTrue(patched.contains("fill-extrusion"))
    }

    private fun loadMobileStyle(): String {
        val candidates = listOf(
            java.io.File("../../../infra/preview/style-mobile.json"),
            java.io.File("../../infra/preview/style-mobile.json"),
            java.io.File("infra/preview/style-mobile.json"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("style-mobile.json not found from ${candidates.map { it.absolutePath }}")
        return file.readText()
    }
}
