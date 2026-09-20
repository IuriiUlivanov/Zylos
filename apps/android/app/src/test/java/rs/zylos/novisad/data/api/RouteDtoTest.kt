package rs.zylos.novisad.data.api

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteDtoTest {
    @Test
    fun requestDefaultsToTransit() {
        val body = RouteRequest(LonLat(19.845, 45.255), LonLat(19.840, 45.238))
        assertEquals("transit", body.mode)
        assertEquals(19.845, body.from.lon, 0.0)
    }
}
