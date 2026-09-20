package rs.zylos.app.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rs.zylos.app.data.api.LonLat
import rs.zylos.app.data.api.RouteItinerary
import rs.zylos.app.data.api.RouteLeg
import rs.zylos.app.data.api.RouteLineString
import rs.zylos.app.data.api.RouteResponse
import rs.zylos.app.data.api.SearchHit
import rs.zylos.app.data.api.SearchKind
import rs.zylos.app.data.repository.RouteResult
import rs.zylos.app.testing.FakeBuildings
import rs.zylos.app.testing.FakeOrgs
import rs.zylos.app.testing.FakeRoutes
import rs.zylos.app.testing.fakeNetwork

@OptIn(ExperimentalCoroutinesApi::class)
class RouteModeTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setMain() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun reset() {
        Dispatchers.resetMain()
    }

    @Test
    fun staleRouteRequestIsIgnored() = runTest(dispatcher) {
        val routes = FakeRoutes { from, _ ->
            if (from.lon == 19.845) {
                delay(5_000)
                RouteResult.Ok(sampleResponse("slow"))
            } else {
                RouteResult.Ok(sampleResponse("fast"))
            }
        }
        val vm = MapViewModel(FakeBuildings(), FakeOrgs(), routes = routes)
        vm.onMapPicked(RouteField.From, 19.845, 45.255, "A")
        vm.onMapPicked(RouteField.To, 19.840, 45.238, "B")
        vm.onBuildRoute()
        vm.onMapPicked(RouteField.From, 19.862, 45.252, "C")
        vm.onBuildRoute()
        advanceUntilIdle()
        assertEquals(MapRouteMode.Result, vm.state.value.route.mode)
        assertEquals(1, vm.state.value.route.itineraries.size)
        assertEquals("C", vm.state.value.route.from?.label)
    }

    @Test
    fun swapInResultRebuildsWithoutCta() = runTest(dispatcher) {
        var calls = 0
        val routes = FakeRoutes { _, _ ->
            calls += 1
            RouteResult.Ok(sampleResponse("n$calls"))
        }
        val vm = MapViewModel(FakeBuildings(), FakeOrgs(), routes = routes)
        vm.onMapPicked(RouteField.From, 19.845, 45.255, "A")
        vm.onMapPicked(RouteField.To, 19.840, 45.238, "B")
        vm.onBuildRoute()
        advanceUntilIdle()
        assertEquals(1, calls)
        vm.onSwapRoute()
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals("B", vm.state.value.route.from?.label)
        assertEquals("A", vm.state.value.route.to?.label)
        assertEquals(MapRouteMode.Result, vm.state.value.route.mode)
    }

    @Test
    fun offlineDoesNotCallRepository() = runTest(dispatcher) {
        var calls = 0
        val routes = FakeRoutes { _, _ ->
            calls += 1
            RouteResult.Ok(sampleResponse("x"))
        }
        val vm = MapViewModel(FakeBuildings(), FakeOrgs(), routes = routes, network = fakeNetwork(false))
        vm.onMapPicked(RouteField.From, 19.845, 45.255, "A")
        vm.onMapPicked(RouteField.To, 19.840, 45.238, "B")
        vm.onBuildRoute()
        advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals(RouteUiError.Offline, vm.state.value.route.error)
        assertEquals(MapRouteMode.Planning, vm.state.value.route.mode)
    }

    @Test
    fun selectRouteHitUsesSearchFieldAfterFocusLost() = runTest(dispatcher) {
        val vm = MapViewModel(FakeBuildings(), FakeOrgs())
        vm.onRouteFieldFocus(RouteField.From)
        vm.onRouteQueryChange(RouteField.From, "Trg slobode")
        vm.onRouteFieldFocus(null)
        vm.onSelectRouteHit(sampleHit("Trg slobode"))
        assertEquals("Trg slobode", vm.state.value.route.from?.label)
        assertNull(vm.state.value.route.to)
    }

    @Test
    fun mapPickUsesPickFieldAfterFocusLost() = runTest(dispatcher) {
        val vm = MapViewModel(FakeBuildings(), FakeOrgs())
        vm.onRouteFieldFocus(RouteField.From)
        vm.onNaKartu(RouteField.From)
        vm.onRouteFieldFocus(RouteField.To)
        vm.onMapClick(19.845, 45.255)
        assertEquals("45.25500, 19.84500", vm.state.value.route.from?.label)
        assertNull(vm.state.value.route.to)
    }

    @Test
    fun clearRouteReturnsIdleAndClearsLayers() = runTest(dispatcher) {
        val vm = MapViewModel(
            FakeBuildings(),
            FakeOrgs(),
            routes = FakeRoutes { _, _ -> RouteResult.Ok(sampleResponse("x")) },
        )
        vm.onMapPicked(RouteField.From, 19.845, 45.255, "A")
        vm.onMapPicked(RouteField.To, 19.840, 45.238, "B")
        vm.onBuildRoute()
        advanceUntilIdle()
        vm.onClearRoute()
        assertEquals(MapRouteMode.Idle, vm.state.value.route.mode)
        assertEquals(BottomTab.Search, vm.state.value.route.bottomTab)
        assertTrue(vm.state.value.route.itineraries.isEmpty())
        assertNull(vm.state.value.route.activeItinerary)
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
}
