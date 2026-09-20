package rs.zylos.app.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rs.zylos.app.data.api.OrgPin
import rs.zylos.app.data.api.SearchHit
import rs.zylos.app.data.api.SearchKind
import rs.zylos.app.data.api.SearchResponse
import rs.zylos.app.data.repository.OrgBboxResult
import rs.zylos.app.data.repository.SearchResult
import rs.zylos.app.map.MapDefaults
import rs.zylos.app.map.OrgPinLimits
import rs.zylos.app.map.SearchPins
import rs.zylos.app.testing.FakeBuildings
import rs.zylos.app.testing.FakeHistory
import rs.zylos.app.testing.FakeOrgs
import rs.zylos.app.testing.FakeSearch

@OptIn(ExperimentalCoroutinesApi::class)
class SearchMultiLogicTest {
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
    fun multiEligibleRequiresThreeOrgsOfOneCategory() {
        assertFalse(SearchLogic.isMultiEligible(emptyList()))
        assertFalse(SearchLogic.isMultiEligible(listOf(orgHit("1"), orgHit("2"))))
        assertTrue(SearchLogic.isMultiEligible(listOf(orgHit("1"), orgHit("2"), orgHit("3"))))
        assertFalse(
            SearchLogic.isMultiEligible(
                listOf(orgHit("1"), orgHit("2", slug = "cafe"), orgHit("3")),
            ),
        )
        assertTrue(
            SearchLogic.isMultiEligible(
                listOf(orgHit("1"), orgHit("2"), orgHit("3"), addrHit("a1")),
            ),
        )
        assertEquals(15, SearchLogic.multiPins(List(20) { orgHit("n$it") }).size)
    }

    @Test
    fun autoMultiFromCategoryHitsAndCapsPins() = runTest(dispatcher) {
        val hits = List(16) { orgHit("org:$it") }
        val vm = MapViewModel(
            FakeBuildings(),
            FakeOrgs(),
            FakeSearch { _, _, _ -> SearchResult.Ok(SearchResponse("apotek", hits, 1)) },
            FakeHistory(),
        )
        vm.onQueryChange("apotek")
        advanceTimeBy(MapDefaults.SEARCH_DEBOUNCE_MS)
        advanceUntilIdle()
        assertEquals(MapPinMode.SearchMulti, vm.state.value.overlay.pinMode)
        assertEquals(SheetMode.SearchList, vm.state.value.sheet.mode)
        assertTrue(vm.state.value.overlay.searchPins.any { it.id == "org:0" })
        assertEquals(15, vm.state.value.overlay.searchPins.size)
        assertEquals(null, vm.state.value.overlay.marker)
        assertEquals(16, vm.state.value.search.hits.size)
        assertEquals("search-pins", SearchPins.SOURCE_ID)
    }

    @Test
    fun showAllOnMapUsesCurrentHits() = runTest(dispatcher) {
        val hits = listOf(orgHit("1", slug = "cafe"), orgHit("2"), addrHit("a"))
        val vm = MapViewModel(
            FakeBuildings(),
            FakeOrgs(),
            FakeSearch { _, _, _ -> SearchResult.Ok(SearchResponse("x", hits, 1)) },
            FakeHistory(),
        )
        vm.onQueryChange("xx")
        advanceTimeBy(MapDefaults.SEARCH_DEBOUNCE_MS)
        advanceUntilIdle()
        assertEquals(MapPinMode.Browse, vm.state.value.overlay.pinMode)
        vm.onShowAllOnMap()
        assertEquals(MapPinMode.SearchMulti, vm.state.value.overlay.pinMode)
        assertEquals(3, vm.state.value.overlay.searchPins.size)
        assertEquals(SheetMode.SearchList, vm.state.value.sheet.mode)
    }

    @Test
    fun clearSearchLeavesBrowseAndDropsMultiPins() = runTest(dispatcher) {
        val hits = listOf(orgHit("1"), orgHit("2"), orgHit("3"))
        val vm = MapViewModel(
            FakeBuildings(),
            FakeOrgs(),
            FakeSearch { _, _, _ -> SearchResult.Ok(SearchResponse("apotek", hits, 1)) },
            FakeHistory(),
        )
        vm.onQueryChange("apotek")
        advanceTimeBy(MapDefaults.SEARCH_DEBOUNCE_MS)
        advanceUntilIdle()
        vm.onClearSearch()
        assertEquals(MapPinMode.Browse, vm.state.value.overlay.pinMode)
        assertTrue(vm.state.value.overlay.searchPins.isEmpty())
        assertEquals("", vm.state.value.search.query)
        assertEquals(SheetMode.Idle, vm.state.value.sheet.mode)
    }

    @Test
    fun twoFastCameraIdlesCauseOneBboxRequest() = runTest(dispatcher) {
        val orgs = FakeOrgs()
        val vm = MapViewModel(FakeBuildings(), orgs, FakeSearch(), FakeHistory())
        vm.onCameraIdle(45.255, 19.845, 16.0, 19.83, 45.24, 19.86, 45.26)
        vm.onCameraIdle(45.256, 19.846, 16.0, 19.831, 45.241, 19.861, 45.261)
        advanceTimeBy(OrgPinLimits.debounceMs(16.0) - 1)
        assertEquals(0, orgs.bboxCalls.size)
        advanceTimeBy(1)
        advanceUntilIdle()
        assertEquals(1, orgs.bboxCalls.size)
        assertEquals(80, orgs.bboxCalls.single().limit)
    }

    @Test
    fun zoomFourteenDoesNotHitBboxApi() = runTest(dispatcher) {
        val orgs = FakeOrgs()
        val vm = MapViewModel(FakeBuildings(), orgs, FakeSearch(), FakeHistory())
        vm.onCameraIdle(45.255, 19.845, 14.0, 19.83, 45.24, 19.86, 45.26)
        advanceTimeBy(400)
        advanceUntilIdle()
        assertEquals(0, orgs.bboxCalls.size)
        assertTrue(vm.state.value.overlay.orgPins.isEmpty())
    }

    @Test
    fun staleBboxResponseIsIgnored() = runTest(dispatcher) {
        val orgs = FakeOrgs(
            bboxFn = { minLon, _, _, _, _ ->
                if (minLon == 19.83) {
                    delay(5_000)
                    OrgBboxResult.Ok(listOf(OrgPin("old", "Old", "hospital", 19.84, 45.25)))
                } else {
                    OrgBboxResult.Ok(listOf(OrgPin("new", "New", "hospital", 19.85, 45.26)))
                }
            },
        )
        val vm = MapViewModel(FakeBuildings(), orgs, FakeSearch(), FakeHistory())
        vm.onCameraIdle(45.255, 19.845, 16.0, 19.83, 45.24, 19.86, 45.26)
        advanceTimeBy(OrgPinLimits.debounceMs(16.0))
        vm.onCameraIdle(45.255, 19.845, 16.0, 19.84, 45.24, 19.87, 45.26)
        advanceTimeBy(OrgPinLimits.debounceMs(16.0))
        advanceUntilIdle()
        assertTrue(vm.state.value.overlay.orgPins.any { it.pin.id == "new" })
        assertFalse(vm.state.value.overlay.orgPins.any { it.pin.id == "old" })
    }

    @Test
    fun searchModeHidesBrowseFetch() = runTest(dispatcher) {
        val orgs = FakeOrgs()
        val hits = listOf(orgHit("1"), orgHit("2"), orgHit("3"))
        val vm = MapViewModel(
            FakeBuildings(),
            orgs,
            FakeSearch { _, _, _ -> SearchResult.Ok(SearchResponse("apotek", hits, 1)) },
            FakeHistory(),
        )
        vm.onQueryChange("apotek")
        advanceTimeBy(MapDefaults.SEARCH_DEBOUNCE_MS)
        advanceUntilIdle()
        val before = orgs.bboxCalls.size
        vm.onCameraIdle(45.255, 19.845, 16.0, 19.83, 45.24, 19.86, 45.26)
        advanceTimeBy(400)
        advanceUntilIdle()
        assertEquals(before, orgs.bboxCalls.size)
        assertEquals(MapPinMode.SearchMulti, vm.state.value.overlay.pinMode)
    }
}

private fun orgHit(id: String, slug: String = "pharmacy") = SearchHit(
    id = id,
    kind = SearchKind.organization,
    label = "Hit $id",
    lat = 45.25,
    lon = 19.84,
    building_id = null,
    name = "Hit $id",
    category_slug = slug,
)

private fun addrHit(id: String) = SearchHit(
    id = id,
    kind = SearchKind.address,
    label = "Adresa $id",
    lat = 45.25,
    lon = 19.84,
    building_id = null,
)
