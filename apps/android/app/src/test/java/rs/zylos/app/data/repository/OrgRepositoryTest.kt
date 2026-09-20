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

class OrgRepositoryTest {
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
    fun inBboxUsesQueryAndParsesPins() = runBlocking {
        val repo = HttpOrgRepository(api)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"id":"org:osm:n1","name":"Apoteka","category_slug":"pharmacy","lon":19.845,"lat":45.255}]""",
            ),
        )
        val result = repo.inBbox(19.83, 45.24, 19.86, 45.26, 40)
        assertTrue(result is OrgBboxResult.Ok)
        val pins = (result as OrgBboxResult.Ok).pins
        assertEquals(1, pins.size)
        assertEquals("org:osm:n1", pins[0].id)
        assertEquals("pharmacy", pins[0].category_slug)
        val request = server.takeRequest()
        assertEquals("/v1/orgs?bbox=19.83%2C45.24%2C19.86%2C45.26&limit=40", request.path)
    }

    @Test
    fun inBboxMapsNetworkAndEmptyBody() = runBlocking {
        val repo = HttpOrgRepository(api)
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_bbox"}"""))
        assertEquals(OrgBboxResult.Network, repo.inBbox(0.0, 0.0, 0.0, 0.0, 40))
        server.takeRequest()

        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        val empty = repo.inBbox(19.83, 45.24, 19.86, 45.26, 40)
        assertTrue(empty is OrgBboxResult.Ok)
        assertTrue((empty as OrgBboxResult.Ok).pins.isEmpty())
    }
}
