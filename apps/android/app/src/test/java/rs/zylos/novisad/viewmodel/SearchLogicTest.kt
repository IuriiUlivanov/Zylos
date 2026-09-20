package rs.zylos.novisad.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rs.zylos.novisad.data.api.BuildingAddress
import rs.zylos.novisad.data.api.BuildingDetailResponse
import rs.zylos.novisad.data.api.BuildingGeometry
import rs.zylos.novisad.data.api.BuildingOrgListItem
import rs.zylos.novisad.data.api.LonLat
import rs.zylos.novisad.data.api.OrgDetailResponse
import rs.zylos.novisad.data.api.SearchHit
import rs.zylos.novisad.data.api.SearchKind
import rs.zylos.novisad.data.api.SearchResponse
import rs.zylos.novisad.data.repository.BuildingDetailResult
import rs.zylos.novisad.data.repository.OrgDetailResult
import rs.zylos.novisad.data.repository.SearchResult
import rs.zylos.novisad.map.MapDefaults
import rs.zylos.novisad.map.SearchMarker
import rs.zylos.novisad.testing.FakeBuildings
import rs.zylos.novisad.testing.FakeHistory
import rs.zylos.novisad.testing.FakeOrgs
import rs.zylos.novisad.testing.FakeSearch

@OptIn(ExperimentalCoroutinesApi::class)
class SearchLogicTest {
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
    fun minLengthSkipsHttp() = runTest(dispatcher) {
        val search = FakeSearch()
        val vm = MapViewModel(FakeBuildings(), FakeOrgs(), search, FakeHistory())
        vm.onQueryChange("a")
        advanceTimeBy(300)
        advanceUntilIdle()
        assertEquals(0, search.calls.size)
        assertTrue(vm.state.value.search.hits.isEmpty())
        assertFalse(SearchLogic.shouldRequest("a"))
        assertTrue(SearchLogic.shouldRequest("ap"))
    }

    @Test
    fun twoFastCharsCauseOneDebouncedRequest() = runTest(dispatcher) {
        val search = FakeSearch()
        val vm = MapViewModel(FakeBuildings(), FakeOrgs(), search, FakeHistory())
        vm.onCameraIdle(45.255, 19.845, 14.0)
        vm.onQueryChange("a")
        vm.onQueryChange("ap")
        advanceTimeBy(MapDefaults.SEARCH_DEBOUNCE_MS - 1)
        assertEquals(0, search.calls.size)
        advanceTimeBy(1)
        advanceUntilIdle()
        assertEquals(1, search.calls.size)
        assertEquals("ap", search.calls[0].q)
        assertEquals(45.255, search.calls[0].lat, 0.0)
        assertEquals(19.845, search.calls[0].lon, 0.0)
    }

    @Test
    fun staleResponseDoesNotReplaceNewerHits() = runTest(dispatcher) {
        val releaseStale = CompletableDeferred<Unit>()
        val search = FakeSearch { q, _, _ ->
            if (q == "ap") {
                releaseStale.await()
                SearchResult.Ok(SearchResponse("ap", listOf(sampleHit(id = "stale")), 1))
            } else {
                SearchResult.Ok(SearchResponse("apotek", listOf(sampleHit(id = "fresh")), 1))
            }
        }
        val vm = MapViewModel(FakeBuildings(), FakeOrgs(), search, FakeHistory())
        vm.onQueryChange("ap")
        advanceTimeBy(MapDefaults.SEARCH_DEBOUNCE_MS)
        vm.onQueryChange("apotek")
        advanceTimeBy(MapDefaults.SEARCH_DEBOUNCE_MS)
        advanceUntilIdle()
        releaseStale.complete(Unit)
        advanceUntilIdle()
        assertEquals("fresh", vm.state.value.search.hits.single().id)
    }

    @Test
    fun geoBiasComesFromCameraIdle() = runTest(dispatcher) {
        val search = FakeSearch()
        val vm = MapViewModel(FakeBuildings(), FakeOrgs(), search, FakeHistory())
        vm.onCameraIdle(45.2, 19.8, 15.0)
        vm.onQueryChange("ap")
        advanceTimeBy(MapDefaults.SEARCH_DEBOUNCE_MS)
        advanceUntilIdle()
        assertEquals(45.2, search.calls.single().lat, 0.0)
        assertEquals(19.8, search.calls.single().lon, 0.0)
    }

    @Test
    fun addressHitWithoutBuildingOpensPeek() = runTest(dispatcher) {
        val vm = MapViewModel(FakeBuildings(), FakeOrgs(), FakeSearch(), FakeHistory())
        vm.onSelectHit(sampleHit(id = "addr:1", kind = SearchKind.address, buildingId = null, label = "Bulevar 47"))
        advanceUntilIdle()
        assertEquals(SheetMode.Peek, vm.state.value.sheet.mode)
        assertEquals("Bulevar 47", vm.state.value.sheet.peek?.title)
        assertEquals("Adresa", vm.state.value.sheet.peek?.subtitle)
        assertEquals(19.84, vm.state.value.overlay.marker?.lon ?: 0.0, 0.0)
        assertEquals(MapDefaults.FLY_MIN_ZOOM, vm.state.value.camera?.zoom ?: MapDefaults.FLY_MIN_ZOOM, 0.0)
        assertEquals(MapDefaults.SEARCH_FLYTO_ANCHOR_Y, vm.state.value.camera?.anchorYFromBottom ?: 0f, 0.0f)
        assertEquals(HitSheet.Peek, SearchLogic.destination(sampleHit(kind = SearchKind.address, buildingId = null), false, false))
    }

    @Test
    fun orgHitWithBuildingOpensOrgSheet() = runTest(dispatcher) {
        val buildings = FakeBuildings(byIdFn = { BuildingDetailResult.Found(sampleBuilding(it)) })
        val orgs = FakeOrgs(byIdFn = { OrgDetailResult.Found(sampleOrg()) })
        val vm = MapViewModel(buildings, orgs, FakeSearch(), FakeHistory())
        vm.onSelectHit(sampleHit(id = "org:osm:n1", kind = SearchKind.organization, buildingId = "b1"))
        advanceUntilIdle()
        assertEquals(SheetMode.Organization, vm.state.value.sheet.mode)
        assertEquals("org:osm:n1", vm.state.value.sheet.org?.id)
        assertEquals("b1", vm.state.value.sheet.building?.id)
        assertEquals("Polygon", vm.state.value.overlay.highlight?.type)
        assertEquals(
            HitSheet.Organization,
            SearchLogic.destination(sampleHit(buildingId = "b1"), true, true),
        )
    }

    @Test
    fun orgHitFallsBackToBuildingWhenOrgMissing() = runTest(dispatcher) {
        val buildings = FakeBuildings(byIdFn = { BuildingDetailResult.Found(sampleBuilding(it)) })
        val vm = MapViewModel(buildings, FakeOrgs(), FakeSearch(), FakeHistory())
        vm.onSelectHit(sampleHit(id = "org:1", kind = SearchKind.organization, buildingId = "b1"))
        advanceUntilIdle()
        assertEquals(SheetMode.Building, vm.state.value.sheet.mode)
        assertEquals("b1", vm.state.value.sheet.building?.id)
        assertEquals(
            HitSheet.Building,
            SearchLogic.destination(sampleHit(buildingId = "b1"), true, false),
        )
    }

    @Test
    fun orgHitFallsBackToOrgSheetWhenBuildingMissing() = runTest(dispatcher) {
        val buildings = FakeBuildings(byIdFn = { BuildingDetailResult.NotFound })
        val orgs = FakeOrgs(byIdFn = { OrgDetailResult.Found(sampleOrg()) })
        val vm = MapViewModel(buildings, orgs, FakeSearch(), FakeHistory())
        vm.onSelectHit(sampleHit(id = "org:osm:n1", kind = SearchKind.organization, buildingId = "missing"))
        advanceUntilIdle()
        assertEquals(SheetMode.Organization, vm.state.value.sheet.mode)
        assertEquals("org:osm:n1", vm.state.value.sheet.org?.id)
        assertEquals(
            HitSheet.Organization,
            SearchLogic.destination(sampleHit(kind = SearchKind.organization), false, true),
        )
    }

    @Test
    fun orgHitWithoutBuildingLoadsOrgSheet() = runTest(dispatcher) {
        val orgs = FakeOrgs(byIdFn = { OrgDetailResult.Found(sampleOrg()) })
        val vm = MapViewModel(FakeBuildings(), orgs, FakeSearch(), FakeHistory())
        vm.onSelectHit(sampleHit(id = "org:osm:n1", kind = SearchKind.organization, buildingId = null))
        advanceUntilIdle()
        assertEquals(SheetMode.Organization, vm.state.value.sheet.mode)
        assertNull(vm.state.value.sheet.building)
    }

    @Test
    fun clearSearchRemovesMarkerAndClosesSheet() = runTest(dispatcher) {
        val vm = MapViewModel(FakeBuildings(), FakeOrgs(), FakeSearch(), FakeHistory())
        vm.onSelectHit(sampleHit(kind = SearchKind.address, buildingId = null))
        advanceUntilIdle()
        vm.onClearSearch()
        assertEquals(SheetMode.Idle, vm.state.value.sheet.mode)
        assertNull(vm.state.value.overlay.marker)
        assertEquals("", vm.state.value.search.query)
        assertTrue(vm.state.value.search.hits.isEmpty())
    }

    @Test
    fun dropdownOpenSkipsBuildingAt() = runTest(dispatcher) {
        val buildings = FakeBuildings()
        val search = FakeSearch()
        val vm = MapViewModel(buildings, FakeOrgs(), search, FakeHistory())
        vm.onSearchFocusChanged(true)
        vm.onQueryChange("ap")
        advanceTimeBy(MapDefaults.SEARCH_DEBOUNCE_MS)
        advanceUntilIdle()
        assertTrue(vm.state.value.dropdownOpen)
        vm.onMapClick(19.84, 45.25)
        advanceUntilIdle()
        assertEquals(0, buildings.atCalls)
        assertFalse(vm.state.value.search.focused)
    }

    @Test
    fun flyZoomNeverBelowSixteen() {
        assertEquals(16.0, SearchLogic.flyZoom(14.0), 0.0)
        assertEquals(17.0, SearchLogic.flyZoom(17.0), 0.0)
    }

    @Test
    fun searchFlyPaddingPutsTargetAtThreeQuartersFromBottom() {
        assertEquals(0, SearchLogic.flyBottomPaddingPx(0))
        assertEquals(400, SearchLogic.flyBottomPaddingPx(800))
        assertEquals(0, SearchLogic.flyBottomPaddingPx(800, 0.5f))
        assertEquals(400, SearchLogic.flyBottomPaddingPx(800, 0.75f))
    }

    @Test
    fun peekSubtitleUsesHumanCategory() {
        assertEquals(
            "Apoteka",
            SearchLogic.peekSubtitle(sampleHit(kind = SearchKind.organization), "Adresa", "Organizacija"),
        )
        assertEquals(
            "Adresa",
            SearchLogic.peekSubtitle(
                sampleHit(kind = SearchKind.address, buildingId = null),
                "Adresa",
                "Organizacija",
            ),
        )
    }

    @Test
    fun selectedMarkerSourceId() {
        assertEquals("selected-marker", SearchMarker.SOURCE_ID)
        assertEquals("selected-marker", SearchMarker.LAYER_ID)
    }

}

private fun sampleHit(
    id: String = "n1",
    kind: SearchKind = SearchKind.organization,
    buildingId: String? = "b1",
    label: String = "Apoteka",
) = SearchHit(
    id = id,
    kind = kind,
    label = label,
    lat = 45.25,
    lon = 19.84,
    building_id = buildingId,
    name = label,
    category_slug = if (kind == SearchKind.organization) "pharmacy" else null,
)

private fun sampleBuilding(id: String) = BuildingDetailResponse(
    id = id,
    name = null,
    centroid = LonLat(19.84, 45.25),
    geometry = BuildingGeometry(
        type = "Polygon",
        coordinates = null,
        encodedJson = """{"type":"Polygon","coordinates":[[[19.84,45.25],[19.85,45.25],[19.85,45.26],[19.84,45.26],[19.84,45.25]]]}""",
    ),
    addresses = listOf(BuildingAddress("a1", "Bulevar 12", "Bulevar", "12", "rgz")),
    organizations = listOf(BuildingOrgListItem("org:osm:n1", "A", "cafe", "Kafić", null)),
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
