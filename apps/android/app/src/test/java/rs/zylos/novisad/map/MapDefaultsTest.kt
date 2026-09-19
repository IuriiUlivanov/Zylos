package rs.zylos.novisad.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapDefaultsTest {
    @Test
    fun cameraStartsAtNoviSadCenter() {
        assertEquals(19.845, MapDefaults.LON, 0.0)
        assertEquals(45.255, MapDefaults.LAT, 0.0)
        assertEquals(14.0, MapDefaults.ZOOM, 0.0)
        assertEquals(60.0, MapDefaults.MAX_PITCH, 0.0)
        assertTrue(MapDefaults.SHEET_ANIMATION_MS <= 250)
        assertEquals(48, MapDefaults.SHEET_STEP1_DP)
        assertEquals(0.5f, MapDefaults.SHEET_STEP2_RATIO, 0.0f)
        assertEquals(1.0f, MapDefaults.SHEET_STEP3_RATIO, 0.0f)
        assertEquals(8, MapDefaults.SHEET_SEARCH_GAP_DP)
        assertEquals(36, MapDefaults.SHEET_CLOSE_DP)
        assertEquals(150L, MapDefaults.SEARCH_DEBOUNCE_MS)
        assertEquals(2, MapDefaults.SEARCH_MIN_LENGTH)
        assertEquals(10, MapDefaults.SEARCH_LIMIT)
        assertEquals(800, MapDefaults.FLY_DURATION_MS)
        assertEquals(16.0, MapDefaults.FLY_MIN_ZOOM, 0.0)
        assertEquals(0.75f, MapDefaults.SEARCH_FLYTO_ANCHOR_Y, 0.0f)
        assertEquals(10, MapDefaults.SEARCH_HISTORY_LIMIT)
    }

    @Test
    fun offlineFileNameMatchesBuildScript() {
        assertTrue(MapDefaults.MBTILES_ASSET.endsWith(".mbtiles"))
        assertEquals("novi-sad.mbtiles", MapDefaults.MBTILES_ASSET)
    }
}
