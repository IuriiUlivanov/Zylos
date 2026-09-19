package rs.zylos.novisad.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMarkerTest {
    @Test
    fun pointCollectionUsesLonLatOrder() {
        val json = SearchMarker.pointJson(19.84, 45.25)
        assertTrue(json.contains("\"type\":\"FeatureCollection\""))
        assertTrue(json.contains("\"type\":\"Point\""))
        assertTrue(json.contains("[19.84,45.25]"))
        assertEquals("selected-marker", SearchMarker.SOURCE_ID)
        assertEquals("selected-marker", SearchMarker.LAYER_ID)
        assertEquals("#00B341", SearchMarker.FILL_COLOR)
    }

    @Test
    fun emptyCollectionClearsMarker() {
        val json = SearchMarker.emptyCollectionJson()
        assertEquals("""{"type":"FeatureCollection","features":[]}""", json)
        assertFalse(json.contains("Point"))
    }
}
