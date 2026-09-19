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
}
