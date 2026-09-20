package rs.zylos.app.data.repository

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rs.zylos.app.data.api.ApiClient
import rs.zylos.app.data.api.ZylosApi

class BuildingRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ZylosApi

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        api = ApiClient.create(server.url("/").toString())
    }

    @After
    fun stop() {
        server.shutdown()
    }

    @Test
    fun buildingAtMapsStatusCodes() = runBlocking {
        val repo = HttpBuildingRepository(api)

        server.enqueue(MockResponse().setBody("""{"id":"b1","label":"Zgrada"}""").setResponseCode(200))
        val found = repo.at(19.84, 45.25)
        assertTrue(found is BuildingAtResult.Found)
        assertEquals("b1", (found as BuildingAtResult.Found).body.id)
        val atRequest = server.takeRequest()
        assertEquals("/v1/buildings/at?lon=19.84&lat=45.25", atRequest.path)

        server.enqueue(MockResponse().setBody("""{"error":"building_not_found"}""").setResponseCode(404))
        assertEquals(BuildingAtResult.NotFound, repo.at(19.84, 45.25))

        server.enqueue(MockResponse().setBody("""{"error":"outside_city"}""").setResponseCode(422))
        assertEquals(BuildingAtResult.OutsideCity, repo.at(20.46, 44.817))
    }

    @Test
    fun buildingByIdAndOrgById() = runBlocking {
        val buildings = HttpBuildingRepository(api)
        val orgs = HttpOrgRepository(api)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "id": "b1",
                  "name": null,
                  "centroid": {"lon": 19.84, "lat": 45.25},
                  "geometry": {"type": "Polygon", "coordinates": []},
                  "addresses": [],
                  "organizations": []
                }
                """.trimIndent(),
            ),
        )
        val detail = buildings.byId("b1")
        assertTrue(detail is BuildingDetailResult.Found)
        assertTrue((detail as BuildingDetailResult.Found).body.organizations.isEmpty())
        assertEquals("/v1/buildings/b1", server.takeRequest().path)

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "id": "org:osm:n123",
                  "name": "A",
                  "source": "osm",
                  "category_slug": "cafe",
                  "category_name": "Kafić",
                  "phones": [],
                  "website": null,
                  "hours": null,
                  "floor": null,
                  "tags": [],
                  "address": null,
                  "building_id": "b1",
                  "location": {"lon": 19.84, "lat": 45.25}
                }
                """.trimIndent(),
            ),
        )
        val org = orgs.byId("org:osm:n123")
        assertTrue(org is OrgDetailResult.Found)
        val orgRequest = server.takeRequest()
        assertEquals("/v1/orgs/org:osm:n123", orgRequest.path)
    }
}
