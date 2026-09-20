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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import rs.zylos.novisad.data.repository.OrgDetailResult
import rs.zylos.novisad.testing.FakeBuildings
import rs.zylos.novisad.testing.FakeOrgs

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
        assertEquals(SheetMode.Building, state.sheet.mode)
        assertEquals("new", state.sheet.building?.id)
        assertEquals("Polygon", state.overlay.highlight?.type)
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
        assertEquals(SheetMode.Idle, state.sheet.mode)
        assertEquals(UserMessage.NotFound, state.message)
        assertTrue(state.haptic)
    }

    @Test
    fun orgBackKeepsBuilding() = runTest(dispatcher) {
        val buildings = FakeBuildings(
            atFn = { _, _ -> BuildingAtResult.Found(BuildingAtResponse("b1", "Bulevar")) },
            byIdFn = { BuildingDetailResult.Found(sampleBuilding("b1")) },
        )
        val orgs = FakeOrgs(byIdFn = { OrgDetailResult.Found(sampleOrg()) })
        val vm = MapViewModel(buildings, orgs)
        vm.onMapClick(19.84, 45.25)
        advanceUntilIdle()
        vm.onOrgSelected("org:osm:n1")
        advanceUntilIdle()
        assertEquals(SheetMode.Organization, vm.state.value.sheet.mode)
        vm.onBackToBuilding()
        assertEquals(SheetMode.Building, vm.state.value.sheet.mode)
        assertEquals("b1", vm.state.value.sheet.building?.id)
        assertNotNull(vm.state.value.overlay.highlight)
    }

    @Test
    fun orgPinClearsHighlightOfOtherBuilding() = runTest(dispatcher) {
        val buildings = FakeBuildings(
            atFn = { _, _ -> BuildingAtResult.Found(BuildingAtResponse("b1", "Bulevar")) },
            byIdFn = { id -> BuildingDetailResult.Found(sampleBuilding(id)) },
        )
        val orgs = FakeOrgs(byIdFn = { id ->
            OrgDetailResult.Found(sampleOrg().copy(id = id, building_id = "b2"))
        })
        val vm = MapViewModel(buildings, orgs)
        vm.onMapClick(19.84, 45.25)
        advanceUntilIdle()
        assertEquals("b1", vm.state.value.sheet.building?.id)

        vm.onOrgPinClick("org:osm:n99")
        assertNull(vm.state.value.overlay.highlight)
        advanceUntilIdle()

        assertEquals(SheetMode.Organization, vm.state.value.sheet.mode)
        assertEquals("org:osm:n99", vm.state.value.sheet.org?.id)
        assertEquals("b2", vm.state.value.sheet.building?.id)
        assertEquals("Polygon", vm.state.value.overlay.highlight?.type)
    }

    @Test
    fun orgWithoutBuildingClearsHighlight() = runTest(dispatcher) {
        val buildings = FakeBuildings(
            atFn = { _, _ -> BuildingAtResult.Found(BuildingAtResponse("b1", "Bulevar")) },
            byIdFn = { BuildingDetailResult.Found(sampleBuilding("b1")) },
        )
        val orgs = FakeOrgs(byIdFn = { OrgDetailResult.Found(sampleOrg().copy(building_id = null)) })
        val vm = MapViewModel(buildings, orgs)
        vm.onMapClick(19.84, 45.25)
        advanceUntilIdle()
        vm.onOrgPinClick("org:osm:n1")
        advanceUntilIdle()

        assertEquals(SheetMode.Organization, vm.state.value.sheet.mode)
        assertEquals(null, vm.state.value.sheet.building)
        assertEquals(null, vm.state.value.overlay.highlight)
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
