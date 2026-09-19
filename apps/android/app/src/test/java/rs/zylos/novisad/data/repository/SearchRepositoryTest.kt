package rs.zylos.novisad.data.repository

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rs.zylos.novisad.data.api.ApiClient
import rs.zylos.novisad.data.api.SearchKind
import rs.zylos.novisad.data.api.ZylosApi
import rs.zylos.novisad.map.MapDefaults

class SearchRepositoryTest {
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
    fun searchSendsLimitLatLonAndParsesHits() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "query": "apotek",
                  "hits": [{
                    "id": "org:osm:n1",
                    "kind": "organization",
                    "label": "Apoteka Benu",
                    "lat": 45.25,
                    "lon": 19.84,
                    "building_id": "b1",
                    "name": "Apoteka Benu",
                    "category_slug": "pharmacy"
                  }],
                  "processingTimeMs": 4
                }
                """.trimIndent(),
            ),
        )
        val repo = HttpSearchRepository(api)
        val result = repo.search("apotek", 45.255, 19.845)
        assertTrue(result is SearchResult.Ok)
        val body = (result as SearchResult.Ok).body
        assertEquals(1, body.hits.size)
        assertEquals(SearchKind.organization, body.hits[0].kind)
        assertEquals("pharmacy", body.hits[0].category_slug)
        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/v1/search?"))
        assertTrue(request.path!!.contains("q=apotek"))
        assertTrue(request.path!!.contains("limit=${MapDefaults.SEARCH_LIMIT}"))
        assertTrue(request.path!!.contains("lat=45.255"))
        assertTrue(request.path!!.contains("lon=19.845"))
    }

    @Test
    fun searchMaps503ToUnavailable() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"search_unavailable"}"""))
        val result = HttpSearchRepository(api).search("apotek", 45.25, 19.84)
        assertEquals(SearchResult.Unavailable, result)
    }

    @Test
    fun searchMapsEmptyHits() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"query":"zz","hits":[],"processingTimeMs":1}""",
            ),
        )
        val result = HttpSearchRepository(api).search("zz", 45.25, 19.84)
        assertTrue(result is SearchResult.Ok)
        assertTrue((result as SearchResult.Ok).body.hits.isEmpty())
    }
}
