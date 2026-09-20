package rs.zylos.novisad.viewmodel

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
import rs.zylos.novisad.data.api.LonLat
import rs.zylos.novisad.data.api.RouteItinerary
import rs.zylos.novisad.data.api.RouteLeg
import rs.zylos.novisad.data.api.RouteLineString
import rs.zylos.novisad.data.api.RouteResponse
import rs.zylos.novisad.data.api.SearchHit
import rs.zylos.novisad.data.api.SearchKind
import rs.zylos.novisad.data.repository.BuildingAtResult
import rs.zylos.novisad.data.repository.BuildingDetailResult
import rs.zylos.novisad.data.repository.BuildingRepository
import rs.zylos.novisad.data.repository.OrgBboxResult
import rs.zylos.novisad.data.repository.OrgDetailResult
import rs.zylos.novisad.data.repository.OrgRepository
import rs.zylos.novisad.data.repository.RouteRepository
import rs.zylos.novisad.data.repository.RouteResult

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
        val vm = MapViewModel(IdleBuildings, IdleOrgs, routes = routes)
        vm.onMapPicked(RouteField.From, 19.845, 45.255, "A")
        vm.onMapPicked(RouteField.To, 19.840, 45.238, "B")
        vm.onBuildRoute(online = true)
        vm.onMapPicked(RouteField.From, 19.862, 45.252, "C")
        vm.onBuildRoute(online = true)
        advanceUntilIdle()
        assertEquals(MapRouteMode.Result, vm.state.value.routeMode)
        assertTrue(vm.state.value.routeWalkJson!!.contains("FeatureCollection"))
        assertEquals("C", vm.state.value.routeFrom?.label)
    }

    @Test
    fun swapInResultRebuildsWithoutCta() = runTest(dispatcher) {
        var calls = 0
        val routes = FakeRoutes { _, _ ->
            calls += 1
            RouteResult.Ok(sampleResponse("n$calls"))
        }
        val vm = MapViewModel(IdleBuildings, IdleOrgs, routes = routes)
        vm.onMapPicked(RouteField.From, 19.845, 45.255, "A")
        vm.onMapPicked(RouteField.To, 19.840, 45.238, "B")
        vm.onBuildRoute(online = true)
        advanceUntilIdle()
        assertEquals(1, calls)
        vm.onSwapRoute(online = true)
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals("B", vm.state.value.routeFrom?.label)
        assertEquals("A", vm.state.value.routeTo?.label)
        assertEquals(MapRouteMode.Result, vm.state.value.routeMode)
    }

    @Test
    fun offlineDoesNotCallRepository() = runTest(dispatcher) {
        var calls = 0
        val routes = FakeRoutes { _, _ ->
            calls += 1
            RouteResult.Ok(sampleResponse("x"))
        }
        val vm = MapViewModel(IdleBuildings, IdleOrgs, routes = routes)
        vm.onMapPicked(RouteField.From, 19.845, 45.255, "A")
        vm.onMapPicked(RouteField.To, 19.840, 45.238, "B")
        vm.onBuildRoute(online = false)
        advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals(RouteUiError.Offline, vm.state.value.routeError)
        assertEquals(MapRouteMode.Planning, vm.state.value.routeMode)
    }

    @Test
    fun selectRouteHitUsesSearchFieldAfterFocusLost() = runTest(dispatcher) {
        val vm = MapViewModel(IdleBuildings, IdleOrgs)
        vm.onRouteFieldFocus(RouteField.From)
        vm.onRouteQueryChange(RouteField.From, "Trg slobode")
        vm.onRouteFieldFocus(null)
        vm.onSelectRouteHit(sampleHit("Trg slobode"))
        assertEquals("Trg slobode", vm.state.value.routeFrom?.label)
        assertNull(vm.state.value.routeTo)
    }

    @Test
    fun mapPickUsesPickFieldAfterFocusLost() = runTest(dispatcher) {
        val vm = MapViewModel(IdleBuildings, IdleOrgs)
        vm.onRouteFieldFocus(RouteField.From)
        vm.onNaKartu(RouteField.From)
        vm.onRouteFieldFocus(RouteField.To)
        vm.onMapClick(19.845, 45.255)
        assertEquals("45.25500, 19.84500", vm.state.value.routeFrom?.label)
        assertNull(vm.state.value.routeTo)
    }

    @Test
    fun clearRouteReturnsIdleAndClearsLayers() = runTest(dispatcher) {
        val vm = MapViewModel(IdleBuildings, IdleOrgs, routes = FakeRoutes { _, _ -> RouteResult.Ok(sampleResponse("x")) })
        vm.onMapPicked(RouteField.From, 19.845, 45.255, "A")
        vm.onMapPicked(RouteField.To, 19.840, 45.238, "B")
        vm.onBuildRoute(online = true)
        advanceUntilIdle()
        vm.onClearRoute()
        assertEquals(MapRouteMode.Idle, vm.state.value.routeMode)
        assertEquals(BottomTab.Search, vm.state.value.bottomTab)
        assertNull(vm.state.value.routeWalkJson)
        assertNull(vm.state.value.routeTransitJson)
    }

    private class FakeRoutes(
        private val fn: suspend (LonLat, LonLat) -> RouteResult,
    ) : RouteRepository {
        override suspend fun planTransit(from: LonLat, to: LonLat) = fn(from, to)
    }

    private object IdleBuildings : BuildingRepository {
        override suspend fun at(lon: Double, lat: Double) = BuildingAtResult.NotFound
        override suspend fun byId(id: String) = BuildingDetailResult.NotFound
    }

    private object IdleOrgs : OrgRepository {
        override suspend fun byId(id: String) = OrgDetailResult.Network
        override suspend fun inBbox(
            minLon: Double,
            minLat: Double,
            maxLon: Double,
            maxLat: Double,
            limit: Int,
        ) = OrgBboxResult.Ok(emptyList())
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
