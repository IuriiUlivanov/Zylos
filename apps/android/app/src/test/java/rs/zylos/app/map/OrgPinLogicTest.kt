package rs.zylos.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.zylos.app.data.api.OrgPin

class OrgPinLogicTest {
    private val center = MapBbox(19.83, 45.24, 19.86, 45.26)

    @Test
    fun limitAndDebounceFollowZoomTable() {
        assertEquals(0, OrgPinLimits.limit(14.9))
        assertEquals(40, OrgPinLimits.limit(15.0))
        assertEquals(40, OrgPinLimits.limit(15.9))
        assertEquals(80, OrgPinLimits.limit(16.2))
        assertEquals(120, OrgPinLimits.limit(17.0))
        assertEquals(160, OrgPinLimits.limit(18.4))
        assertEquals(200, OrgPinLimits.limit(19.0))
        assertEquals(200, OrgPinLimits.limit(21.0))

        assertEquals(300L, OrgPinLimits.debounceMs(15.0))
        assertEquals(300L, OrgPinLimits.debounceMs(17.9))
        assertEquals(250L, OrgPinLimits.debounceMs(18.0))
        assertEquals(200L, OrgPinLimits.debounceMs(19.2))
        assertEquals(false, OrgPinLimits.shouldRequest(14.99))
        assertEquals(true, OrgPinLimits.shouldRequest(15.0))
    }

    @Test
    fun categoryTierAndUnmarkedDefault() {
        assertEquals(10, OrgPinLogic.categoryTier("hospital"))
        assertEquals(15, OrgPinLogic.categoryTier("pharmacy"))
        assertEquals(20, OrgPinLogic.categoryTier("bank"))
        assertEquals(30, OrgPinLogic.categoryTier("cafe"))
        assertEquals(40, OrgPinLogic.categoryTier("supermarket"))
        assertEquals(50, OrgPinLogic.categoryTier("shop"))
        assertEquals(60, OrgPinLogic.categoryTier("parking"))
        assertEquals(70, OrgPinLogic.categoryTier("office"))
        assertEquals(90, OrgPinLogic.categoryTier(null))
        assertEquals(90, OrgPinLogic.categoryTier("unknown_slug"))
        assertEquals(90, OrgPinLogic.categoryTier("other"))
    }

    @Test
    fun displayRankAddsDistanceToTier() {
        val near = OrgPinLogic.displayRank("pharmacy", center.centerLon, center.centerLat, center.centerLon, center.centerLat)
        assertEquals(15, near)
        val far = OrgPinLogic.displayRank("pharmacy", 19.90, 45.32, center.centerLon, center.centerLat)
        assertTrue(far > near)
        assertEquals(15 + kotlin.math.floor(OrgPinLogic.distanceKm(45.32, 19.90, center.centerLat, center.centerLon) * 3).toInt(), far)
    }

    @Test
    fun visiblePinsFilterByRankAndLabelTopN() {
        val pins = listOf(
            OrgPin("h", "Hospital", "hospital", center.centerLon, center.centerLat),
            OrgPin("p", "Apoteka", "pharmacy", center.centerLon, center.centerLat),
            OrgPin("c", "Kafić", "cafe", center.centerLon, center.centerLat),
            OrgPin("s", "Shop", "shop", center.centerLon, center.centerLat),
            OrgPin("k", "Parking", "parking", center.centerLon, center.centerLat),
        )
        val z15 = OrgPinLogic.visiblePins(pins, 15.0, center)
        assertEquals(listOf("h", "p"), z15.map { it.pin.id })
        assertTrue(z15.none { it.labeled })

        val z17 = OrgPinLogic.visiblePins(pins, 17.0, center)
        assertEquals(listOf("h", "p", "c", "s"), z17.map { it.pin.id })
        assertTrue(z17.all { it.labeled })

        val z19 = OrgPinLogic.visiblePins(pins, 19.0, center)
        assertEquals(5, z19.size)
        assertTrue(z19.all { it.labeled })
    }

    @Test
    fun geoJsonIncludesDisplayRankAndEscapesName() {
        val json = OrgPins.collectionJson(
            listOf(
                RankedOrgPin(
                    OrgPin("org:osm:n1", "Apoteka \"Benu\"", "pharmacy", 19.84, 45.25),
                    displayRank = 15,
                    labeled = true,
                ),
            ),
        )
        assertTrue(json.contains("\"id\":\"org:osm:n1\""))
        assertTrue(json.contains("\"display_rank\":15"))
        assertTrue(json.contains("Apoteka \\\"Benu\\\""))
        assertEquals("org-pins", OrgPins.SOURCE_ID)
        assertEquals("org-pins", OrgPins.CIRCLE_LAYER_ID)
    }
}
