package rs.zylos.novisad.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.zylos.novisad.data.api.BuildingAddress
import rs.zylos.novisad.data.api.BuildingDetailResponse
import rs.zylos.novisad.data.api.BuildingGeometry
import rs.zylos.novisad.data.api.BuildingOrgListItem
import rs.zylos.novisad.data.api.LonLat
import rs.zylos.novisad.data.api.OrgDetailResponse

class SheetLogicTest {
    @Test
    fun titleUsesFirstAddressThenNameThenFallback() {
        val withAddress = sampleBuilding(addresses = listOf(sampleAddress("Bulevar 12")))
        assertEquals("Bulevar 12", SheetLogic.title(MapUiState(building = withAddress), "Zgrada"))

        val named = sampleBuilding(name = "Big Fashion", addresses = emptyList())
        assertEquals("Big Fashion", SheetLogic.title(MapUiState(building = named), "Zgrada"))

        val empty = sampleBuilding(name = null, addresses = emptyList())
        assertEquals("Zgrada", SheetLogic.title(MapUiState(building = empty), "Zgrada"))
    }

    @Test
    fun notFoundClearsSheetAndAsksHaptic() {
        val next = SheetLogic.reduceNotFound(3)
        assertEquals(SheetMode.Idle, next.mode)
        assertNull(next.building)
        assertNull(next.highlightJson)
        assertEquals(UserMessage.NotFound, next.message)
        assertTrue(next.haptic)
        assertEquals(3, next.generation)
    }

    @Test
    fun outsideCityDoesNotHaptic() {
        val next = SheetLogic.reduceOutsideCity(1)
        assertEquals(UserMessage.OutsideCity, next.message)
        assertEquals(false, next.haptic)
        assertEquals(SheetMode.Idle, next.mode)
    }

    @Test
    fun backFromOrgKeepsBuildingAndHighlight() {
        val building = sampleBuilding()
        val org = sampleOrg()
        val current = MapUiState(
            mode = SheetMode.Organization,
            building = building,
            org = org,
            highlightJson = "{\"type\":\"FeatureCollection\",\"features\":[]}",
            generation = 4,
        )
        val next = SheetLogic.reduceBackToBuilding(current)
        assertEquals(SheetMode.Building, next.mode)
        assertEquals(building.id, next.building?.id)
        assertNull(next.org)
        assertEquals(current.highlightJson, next.highlightJson)
    }

    @Test
    fun orgSubtitlePrefersCategoryNameThenSlugLabel() {
        val named = sampleOrg()
        assertEquals("Kafić", SheetLogic.orgSubtitle(named))
        val slugged = sampleOrg().copy(category_name = null, category_slug = "pharmacy")
        assertEquals("Apoteka", SheetLogic.orgSubtitle(slugged))
    }

    @Test
    fun closeClearsHighlight() {
        val current = MapUiState(
            mode = SheetMode.Building,
            building = sampleBuilding(),
            highlightJson = "{}",
            generation = 2,
        )
        val next = SheetLogic.reduceClose(current)
        assertEquals(SheetMode.Idle, next.mode)
        assertNull(next.building)
        assertNull(next.highlightJson)
    }

    @Test
    fun networkKeepsPreviousBuildingSheet() {
        val current = MapUiState(mode = SheetMode.Loading, building = sampleBuilding(), generation = 1)
        val next = SheetLogic.reduceNetwork(2, current)
        assertEquals(SheetMode.Building, next.mode)
        assertEquals(UserMessage.Network, next.message)
        assertEquals(sampleBuilding().id, next.building?.id)
    }

    private fun sampleAddress(label: String) = BuildingAddress(
        id = "a1",
        label = label,
        street = "Bulevar",
        housenumber = "12",
        source = "rgz",
    )

    private fun sampleBuilding(
        id: String = "b1",
        name: String? = null,
        addresses: List<BuildingAddress> = listOf(sampleAddress("Bulevar 12")),
    ) = BuildingDetailResponse(
        id = id,
        name = name,
        centroid = LonLat(19.84, 45.25),
        geometry = BuildingGeometry(type = "Polygon", coordinates = null),
        addresses = addresses,
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
        hours = null,
        floor = null,
        tags = emptyList(),
        address = null,
        building_id = "b1",
        location = LonLat(19.84, 45.25),
    )
}
