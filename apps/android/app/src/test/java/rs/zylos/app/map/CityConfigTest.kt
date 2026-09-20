package rs.zylos.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CityConfigTest {
    @Test
    fun bootstrapMatchesNoviSadExtract() {
        assertEquals("novi-sad", CityConfig.ID)
        assertEquals(19.845, CityConfig.LON, 0.0)
        assertEquals(45.255, CityConfig.LAT, 0.0)
        assertEquals(14.0, CityConfig.INITIAL_ZOOM, 0.0)
    }

    @Test
    fun offlineFileNameMatchesBuildScript() {
        assertTrue(CityConfig.MBTILES_ASSET.endsWith(".mbtiles"))
        assertEquals("novi-sad.mbtiles", CityConfig.MBTILES_ASSET)
    }
}
