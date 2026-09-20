package rs.zylos.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.zylos.app.data.api.RouteItinerary
import rs.zylos.app.data.api.RouteLeg
import rs.zylos.app.data.api.RouteLineString
import rs.zylos.app.data.api.RouteResponse
import rs.zylos.app.map.MapDefaults

class RouteLogicTest {
    @Test
    fun formatsSummaryWithoutTransfers() {
        assertEquals("12 min · bez presedanja", RouteLogic.formatSummary(720, 0))
        assertEquals("Pešačenje · 4 min", RouteLogic.formatWalkLeg(240))
        assertEquals("Autobus 7A · 15 min", RouteLogic.formatTransitLeg("7A", 900))
    }

    @Test
    fun fallbackColorWhenMissingOrInvalid() {
        assertEquals("#E30613", RouteLogic.cssColor("E30613", 0))
        assertEquals("#E30613", RouteLogic.cssColor("#e30613", 0))
        assertTrue(RouteLogic.cssColor("nope", 1).startsWith("#"))
        assertTrue(RouteLogic.cssColor(null, 0).startsWith("#"))
    }

    @Test
    fun sortsDurationThenTransfersThenWalkThenIndex() {
        val a = sample(duration = 2000, transfers = 0, walk = 100)
        val b = sample(duration = 1000, transfers = 2, walk = 50)
        val c = sample(duration = 1000, transfers = 0, walk = 80)
        val d = sample(duration = 1000, transfers = 0, walk = 40)
        val sorted = RouteLogic.sortItineraries(listOf(a, b, c, d))
        assertEquals(1000, sorted[0].duration_sec)
        assertEquals(0, sorted[0].transfers)
        assertEquals(40, sorted[0].walk_duration_sec)
        assertEquals(80, sorted[1].walk_duration_sec)
        assertEquals(2, sorted[2].transfers)
        assertEquals(2000, sorted[3].duration_sec)
    }

    @Test
    fun walkCollectionIsDashedSourceAndTransitHasColor() {
        val itinerary = sample(duration = 1680, transfers = 1, walk = 360)
        val walk = RouteLogic.walkCollectionJson(itinerary)
        val transit = RouteLogic.transitCollectionJson(itinerary)
        val labels = RouteLogic.labelsCollectionJson(itinerary)
        assertTrue(walk.contains("LineString"))
        assertTrue(transit.contains("#E30613") || transit.contains("E30613") || transit.contains("#"))
        assertTrue(labels.contains("7A"))
        assertEquals(MapDefaults.ROUTE_WALK_COLOR, "#5B6B7A")
    }

    @Test
    fun wrapsTopLevelResponseWhenItinerariesMissing() {
        val response = RouteResponse(
            mode = "transit",
            duration_sec = 100,
            distance_m = 10.0,
            transfers = 0,
            legs = listOf(walkLeg(), transitLeg()),
            itineraries = emptyList(),
        )
        val items = RouteLogic.itinerariesOf(response)
        assertEquals(1, items.size)
        assertEquals(2, items[0].legs.size)
    }

    private fun sample(duration: Int, transfers: Int, walk: Int) = RouteItinerary(
        duration_sec = duration,
        distance_m = 1000.0,
        transfers = transfers,
        walk_duration_sec = walk,
        legs = listOf(walkLeg(), transitLeg()),
    )

    private fun walkLeg() = RouteLeg(
        mode = "walk",
        duration_sec = 120,
        distance_m = 80.0,
        geometry = RouteLineString("LineString", listOf(listOf(19.845, 45.255), listOf(19.844, 45.254))),
    )

    private fun transitLeg() = RouteLeg(
        mode = "transit",
        duration_sec = 900,
        distance_m = 3000.0,
        geometry = RouteLineString("LineString", listOf(listOf(19.844, 45.254), listOf(19.840, 45.238))),
        route_short_name = "7A",
        route_color = "E30613",
    )
}
