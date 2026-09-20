package rs.zylos.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.zylos.app.data.api.BuildingGeometry

class BuildingHighlightTest {
    @Test
    fun wrapsPolygonInFeatureCollection() {
        val geometry = BuildingGeometry(
            type = "Polygon",
            coordinates = null,
            encodedJson = """{"type":"Polygon","coordinates":[[[19.84,45.25],[19.85,45.25],[19.85,45.26],[19.84,45.26],[19.84,45.25]]]}""",
        )
        val json = BuildingHighlight.collectionJson(geometry)
        assertTrue(json.contains("\"type\":\"FeatureCollection\""))
        assertTrue(json.contains("\"id\":1"))
        assertTrue(json.contains("\"type\":\"Polygon\""))
        assertTrue(json.contains("19.84"))
        assertEquals("selected-building", BuildingHighlight.SOURCE_ID)
        assertEquals("selected-building", BuildingHighlight.FILL_LAYER_ID)
        assertEquals("selected-building-outline", BuildingHighlight.OUTLINE_LAYER_ID)
        assertEquals("#00B341", BuildingHighlight.FILL_COLOR)
        assertEquals("#008A32", BuildingHighlight.OUTLINE_COLOR)
    }

    @Test
    fun emptyCollectionClearsHighlight() {
        val json = BuildingHighlight.emptyCollectionJson()
        assertEquals("""{"type":"FeatureCollection","features":[]}""", json)
        assertFalse(json.contains("Polygon"))
    }

    @Test
    fun geometryObjectRoundTripFromEncodedJson() {
        val raw = """{"type":"MultiPolygon","coordinates":[]}"""
        val geometry = BuildingGeometry(type = "MultiPolygon", coordinates = emptyList<Any>(), encodedJson = raw)
        assertEquals(raw, geometry.toGeoJsonObject())
        assertTrue(BuildingHighlight.collectionJson(geometry).contains("MultiPolygon"))
    }
}
