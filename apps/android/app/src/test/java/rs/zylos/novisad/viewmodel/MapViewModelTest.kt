package rs.zylos.novisad.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rs.zylos.novisad.data.api.BuildingAddress
import rs.zylos.novisad.data.api.BuildingAtResponse
import rs.zylos.novisad.data.api.BuildingDetailResponse
import rs.zylos.novisad.data.api.BuildingGeometry
import rs.zylos.novisad.data.api.BuildingOrgListItem
import rs.zylos.novisad.data.api.LonLat
import rs.zylos.novisad.data.api.OrgDetailResponse
import rs.zylos.novisad.data.repository.BuildingAtResult
import rs.zylos.novisad.data.repository.BuildingDetailResult
import rs.zylos.novisad.data.repository.BuildingRepository
import rs.zylos.novisad.data.repository.OrgBboxResult
import rs.zylos.novisad.data.repository.OrgDetailResult
import rs.zylos.novisad.data.repository.OrgRepository

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {
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
    fun secondTapWinsOverStalePick() = runTest(dispatcher) {
        val buildings = FakeBuildings(
            atFn = { lon, _ ->
                if (lon == 1.0) {
                    delay(5_000)
                    BuildingAtResult.Found(BuildingAtResponse("old", "old"))
                } else {
                    BuildingAtResult.Found(BuildingAtResponse("new", "new"))
                }
            },
            byIdFn = { id -> BuildingDetailResult.Found(sampleBuilding(id)) },
        )
        val vm = MapViewModel(buildings, FakeOrgs())
        vm.onMapClick(1.0, 45.0)
        vm.onMapClick(2.0, 45.0)
        advanceUntilIdle()
        val state = vm.state.first()
        assertEquals(SheetMode.Building, state.mode)
        assertEquals("new", state.building?.id)
        assertTrue(state.highlightJson!!.contains("FeatureCollection"))
    }

    @Test
    fun maps404ToNotFoundMessage() = runTest(dispatcher) {
        val buildings = FakeBuildings(
            atFn = { _, _ -> BuildingAtResult.NotFound },
            byIdFn = { BuildingDetailResult.NotFound },
        )
        val vm = MapViewModel(buildings, FakeOrgs())
        vm.onMapClick(19.84, 45.25)
        advanceUntilIdle()
        val state = vm.state.first()
        assertEquals(SheetMode.Idle, state.mode)
        assertEquals(UserMessage.NotFound, state.message)
        assertTrue(state.haptic)
    }

    @Test
    fun orgBackKeepsBuilding() = runTest(dispatcher) {
        val buildings = FakeBuildings(
            atFn = { _, _ -> BuildingAtResult.Found(BuildingAtResponse("b1", "Bulevar")) },
            byIdFn = { BuildingDetailResult.Found(sampleBuilding("b1")) },
        )
        val orgs = FakeOrgs { OrgDetailResult.Found(sampleOrg()) }
        val vm = MapViewModel(buildings, orgs)
        vm.onMapClick(19.84, 45.25)
        advanceUntilIdle()
        vm.onOrgSelected("org:osm:n1")
        advanceUntilIdle()
        assertEquals(SheetMode.Organization, vm.state.value.mode)
        vm.onBackToBuilding()
        assertEquals(SheetMode.Building, vm.state.value.mode)
        assertEquals("b1", vm.state.value.building?.id)
        assertTrue(vm.state.value.highlightJson != null)
    }

    @Test
    fun orgPinClearsHighlightOfOtherBuilding() = runTest(dispatcher) {
        val buildings = FakeBuildings(
            atFn = { _, _ -> BuildingAtResult.Found(BuildingAtResponse("b1", "Bulevar")) },
            byIdFn = { id -> BuildingDetailResult.Found(sampleBuilding(id)) },
        )
        val orgs = FakeOrgs { id ->
            OrgDetailResult.Found(sampleOrg().copy(id = id, building_id = "b2"))
        }
        val vm = MapViewModel(buildings, orgs)
        vm.onMapClick(19.84, 45.25)
        advanceUntilIdle()
        assertEquals("b1", vm.state.value.building?.id)

        vm.onOrgPinClick("org:osm:n99")
        assertEquals(null, vm.state.value.highlightJson)
        advanceUntilIdle()

        assertEquals(SheetMode.Organization, vm.state.value.mode)
        assertEquals("org:osm:n99", vm.state.value.org?.id)
        assertEquals("b2", vm.state.value.building?.id)
        assertTrue(vm.state.value.highlightJson!!.contains("FeatureCollection"))
    }

    @Test
    fun orgWithoutBuildingClearsHighlight() = runTest(dispatcher) {
        val buildings = FakeBuildings(
            atFn = { _, _ -> BuildingAtResult.Found(BuildingAtResponse("b1", "Bulevar")) },
            byIdFn = { BuildingDetailResult.Found(sampleBuilding("b1")) },
        )
        val orgs = FakeOrgs { OrgDetailResult.Found(sampleOrg().copy(building_id = null)) }
        val vm = MapViewModel(buildings, orgs)
        vm.onMapClick(19.84, 45.25)
        advanceUntilIdle()
        vm.onOrgPinClick("org:osm:n1")
        advanceUntilIdle()

        assertEquals(SheetMode.Organization, vm.state.value.mode)
        assertEquals(null, vm.state.value.building)
        assertEquals(null, vm.state.value.highlightJson)
    }

    private class FakeBuildings(
        private val atFn: suspend (Double, Double) -> BuildingAtResult,
        private val byIdFn: suspend (String) -> BuildingDetailResult,
    ) : BuildingRepository {
        override suspend fun at(lon: Double, lat: Double) = atFn(lon, lat)
        override suspend fun byId(id: String) = byIdFn(id)
    }

    private class FakeOrgs(
        private val byIdFn: suspend (String) -> OrgDetailResult = { OrgDetailResult.Network },
    ) : OrgRepository {
        override suspend fun byId(id: String) = byIdFn(id)
        override suspend fun inBbox(
            minLon: Double,
            minLat: Double,
            maxLon: Double,
            maxLat: Double,
            limit: Int,
        ) = OrgBboxResult.Ok(emptyList())
    }

    private fun sampleBuilding(id: String) = BuildingDetailResponse(
        id = id,
        name = null,
        centroid = LonLat(19.84, 45.25),
        geometry = BuildingGeometry(
            type = "Polygon",
            coordinates = null,
            encodedJson = """{"type":"Polygon","coordinates":[[[19.84,45.25],[19.85,45.25],[19.85,45.26],[19.84,45.26],[19.84,45.25]]]}""",
        ),
        addresses = listOf(
            BuildingAddress("a1", "Bulevar 12", "Bulevar", "12", "rgz"),
        ),
        organizations = listOf(
            BuildingOrgListItem("org:osm:n1", "A", "cafe", "Kafić", null),
            BuildingOrgListItem("org:osm:n2", "B", "pharmacy", "Apoteka", null),
        ),
    )

    private fun sampleOrg() = OrgDetailResponse(
        id = "org:osm:n1",
        name = "A",
        source = "osm",
        category_slug = "cafe",
        category_name = "Kafić",
        phones = listOf("123"),
        website = null,
        hours = "08-20",
        floor = null,
        tags = emptyList(),
        address = null,
        building_id = "b1",
        location = LonLat(19.84, 45.25),
    )
}
