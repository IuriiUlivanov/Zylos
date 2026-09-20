package rs.zylos.app.feature.route

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.zylos.app.data.api.RouteItinerary
import rs.zylos.app.data.api.RouteLeg
import rs.zylos.app.data.api.RouteLineString
import rs.zylos.app.data.api.RouteResponse
import rs.zylos.app.data.api.SearchHit
import rs.zylos.app.data.api.SearchKind
import rs.zylos.app.data.repository.RouteResult
import rs.zylos.app.testing.FakeRoutes
import rs.zylos.app.testing.FakeSearch
import rs.zylos.app.testing.fakeLocation
import rs.zylos.app.testing.fakeNetwork
import rs.zylos.app.viewmodel.BottomTab
import rs.zylos.app.viewmodel.CameraTracker
import rs.zylos.app.viewmodel.MapEventSink
import rs.zylos.app.viewmodel.MapRouteMode
import rs.zylos.app.viewmodel.RouteField
import rs.zylos.app.viewmodel.RouteUiError

@OptIn(ExperimentalCoroutinesApi::class)
class RouteCoordinatorTest {
    @Test
    fun staleRouteRequestIsIgnored() = runTest {
        val routes = FakeRoutes { from, _ ->
            if (from.lon == 19.845) {
                delay(5_000)
                RouteResult.Ok(sampleResponse("slow"))
            } else {
                RouteResult.Ok(sampleResponse("fast"))
            }
        }
        val route = coordinator(routes)
        route.onMapPicked(RouteField.From, 19.845, 45.255, "A")
        route.onMapPicked(RouteField.To, 19.840, 45.238, "B")
        route.onBuildRoute()
        route.onMapPicked(RouteField.From, 19.862, 45.252, "C")
        route.onBuildRoute()
        advanceUntilIdle()
        assertEquals(MapRouteMode.Result, route.state.value.mode)
        assertEquals(1, route.state.value.itineraries.size)
        assertEquals("C", route.state.value.from?.label)
    }

    @Test
    fun swapInResultRebuildsWithoutCta() = runTest {
        var calls = 0
        val routes = FakeRoutes { _, _ ->
            calls += 1
            RouteResult.Ok(sampleResponse("n$calls"))
        }
        val route = coordinator(routes)
        route.onMapPicked(RouteField.From, 19.845, 45.255, "A")
        route.onMapPicked(RouteField.To, 19.840, 45.238, "B")
        route.onBuildRoute()
        advanceUntilIdle()
        assertEquals(1, calls)
        route.onSwapRoute()
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals("B", route.state.value.from?.label)
        assertEquals("A", route.state.value.to?.label)
        assertEquals(MapRouteMode.Result, route.state.value.mode)
    }

    @Test
    fun offlineDoesNotCallRepository() = runTest {
        var calls = 0
        val routes = FakeRoutes { _, _ ->
            calls += 1
            RouteResult.Ok(sampleResponse("x"))
        }
        val route = coordinator(routes, online = false)
        route.onMapPicked(RouteField.From, 19.845, 45.255, "A")
        route.onMapPicked(RouteField.To, 19.840, 45.238, "B")
        route.onBuildRoute()
        advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals(RouteUiError.Offline, route.state.value.error)
        assertEquals(MapRouteMode.Planning, route.state.value.mode)
    }

    @Test
    fun selectRouteHitUsesSearchFieldAfterFocusLost() = runTest {
        val route = coordinator(FakeRoutes())
        route.onFieldFocus(RouteField.From)
        route.onQueryChange(RouteField.From, "Trg slobode")
        route.onFieldFocus(null)
        route.onSelectHit(sampleHit("Trg slobode"))
        assertEquals("Trg slobode", route.state.value.from?.label)
        assertNull(route.state.value.to)
    }

    @Test
    fun mapPickUsesPickFieldAfterFocusLost() = runTest {
        val route = coordinator(FakeRoutes())
        route.enter(null)
        route.onFieldFocus(RouteField.From)
        route.onNaKartu(RouteField.From)
        route.onFieldFocus(RouteField.To)
        assertTrue(route.handleMapTap(19.845, 45.255))
        assertEquals("45.25500, 19.84500", route.state.value.from?.label)
        assertNull(route.state.value.to)
    }

    @Test
    fun clearRouteReturnsIdleAndClearsLayers() = runTest {
        val route = coordinator(FakeRoutes { _, _ -> RouteResult.Ok(sampleResponse("x")) })
        route.onMapPicked(RouteField.From, 19.845, 45.255, "A")
        route.onMapPicked(RouteField.To, 19.840, 45.238, "B")
        route.onBuildRoute()
        advanceUntilIdle()
        route.onClearRoute()
        assertEquals(MapRouteMode.Idle, route.state.value.mode)
        assertEquals(BottomTab.Search, route.state.value.bottomTab)
        assertTrue(route.state.value.itineraries.isEmpty())
        assertNull(route.state.value.activeItinerary)
    }

    @Test
    fun searchTabLeavesPlanningAndIgnoresMapPick() = runTest {
        val route = coordinator(FakeRoutes())
        route.enter(null)
        assertEquals(MapRouteMode.Planning, route.state.value.mode)
        route.onNaKartu(RouteField.To)
        route.showSearchTab()
        assertEquals(BottomTab.Search, route.state.value.bottomTab)
        assertEquals(MapRouteMode.Idle, route.state.value.mode)
        assertNull(route.state.value.pickField)
        assertFalse(route.handleMapTap(19.845, 45.255))
        assertNull(route.state.value.to)
    }

    @Test
    fun searchTabKeepsResultMode() = runTest {
        val route = coordinator(FakeRoutes { _, _ -> RouteResult.Ok(sampleResponse("x")) })
        route.onMapPicked(RouteField.From, 19.845, 45.255, "A")
        route.onMapPicked(RouteField.To, 19.840, 45.238, "B")
        route.onBuildRoute()
        advanceUntilIdle()
        route.showSearchTab()
        assertEquals(BottomTab.Search, route.state.value.bottomTab)
        assertEquals(MapRouteMode.Result, route.state.value.mode)
        assertEquals(1, route.state.value.itineraries.size)
    }
}

private fun CoroutineScope.coordinator(
    routes: FakeRoutes,
    online: Boolean = true,
    sink: MapEventSink = MapEventSink { },
): RouteCoordinator {
    return RouteCoordinator(
        scope = this,
        routes = routes,
        search = FakeSearch(),
        network = fakeNetwork(online),
        location = fakeLocation(),
        camera = CameraTracker(),
        events = sink,
    )
}

private fun sampleResponse(tag: String) = RouteResponse(
    mode = "transit",
    duration_sec = 1680,
    distance_m = 4200.0,
    transfers = 1,
    legs = listOf(walk(), bus(tag)),
    itineraries = listOf(
        RouteItinerary(1680, 4200.0, 1, 360, listOf(walk(), bus(tag))),
    ),
)

private fun walk() = RouteLeg(
    "walk",
    360,
    280.0,
    RouteLineString("LineString", listOf(listOf(19.845, 45.255), listOf(19.844, 45.254))),
)

private fun sampleHit(label: String) = SearchHit(
    id = "hit-1",
    kind = SearchKind.address,
    label = label,
    lat = 45.255,
    lon = 19.845,
    building_id = null,
)

private fun bus(tag: String) = RouteLeg(
    "transit",
    900,
    3200.0,
    RouteLineString("LineString", listOf(listOf(19.844, 45.254), listOf(19.840, 45.238))),
    route_short_name = "7A",
    route_color = "E30613",
    from_stop_name = tag,
)
