package rs.zylos.novisad.testing

import rs.zylos.novisad.data.api.LonLat
import rs.zylos.novisad.data.api.SearchResponse
import rs.zylos.novisad.data.local.SearchHistoryEntity
import rs.zylos.novisad.data.local.SearchHistoryStore
import rs.zylos.novisad.data.repository.BuildingAtResult
import rs.zylos.novisad.data.repository.BuildingDetailResult
import rs.zylos.novisad.data.repository.BuildingRepository
import rs.zylos.novisad.data.repository.OrgBboxResult
import rs.zylos.novisad.data.repository.OrgDetailResult
import rs.zylos.novisad.data.repository.OrgRepository
import rs.zylos.novisad.data.repository.RouteRepository
import rs.zylos.novisad.data.repository.RouteResult
import rs.zylos.novisad.data.repository.SearchRepository
import rs.zylos.novisad.data.repository.SearchResult
import rs.zylos.novisad.platform.LocationProvider
import rs.zylos.novisad.platform.NetworkMonitor

class FakeBuildings(
    private val atFn: suspend (Double, Double) -> BuildingAtResult = { _, _ -> BuildingAtResult.NotFound },
    private val byIdFn: suspend (String) -> BuildingDetailResult = { BuildingDetailResult.NotFound },
) : BuildingRepository {
    var atCalls = 0
    override suspend fun at(lon: Double, lat: Double): BuildingAtResult {
        atCalls += 1
        return atFn(lon, lat)
    }
    override suspend fun byId(id: String) = byIdFn(id)
}

class FakeOrgs(
    private val byIdFn: suspend (String) -> OrgDetailResult = { OrgDetailResult.Network },
    private val bboxFn: suspend (Double, Double, Double, Double, Int) -> OrgBboxResult = { _, _, _, _, _ ->
        OrgBboxResult.Ok(emptyList())
    },
) : OrgRepository {
    data class BboxCall(val minLon: Double, val limit: Int)
    val bboxCalls = mutableListOf<BboxCall>()

    override suspend fun byId(id: String) = byIdFn(id)

    override suspend fun inBbox(
        minLon: Double,
        minLat: Double,
        maxLon: Double,
        maxLat: Double,
        limit: Int,
    ): OrgBboxResult {
        bboxCalls += BboxCall(minLon, limit)
        return bboxFn(minLon, minLat, maxLon, maxLat, limit)
    }
}

class FakeSearch(
    private val handler: suspend (String, Double, Double) -> SearchResult = { q, _, _ ->
        SearchResult.Ok(SearchResponse(q, emptyList(), 0))
    },
) : SearchRepository {
    data class Call(val q: String, val lat: Double, val lon: Double)
    val calls = mutableListOf<Call>()

    override suspend fun search(q: String, lat: Double, lon: Double, limit: Int): SearchResult {
        calls += Call(q, lat, lon)
        return handler(q, lat, lon)
    }
}

class FakeHistory : SearchHistoryStore {
    private val rows = mutableListOf<SearchHistoryEntity>()
    override suspend fun recent(): List<SearchHistoryEntity> = rows.toList()
    override suspend fun save(query: String, hitId: String?, label: String?, kind: String?, categorySlug: String?) {
        rows.removeAll { it.query == query }
        rows.add(
            0,
            SearchHistoryEntity(
                query = query,
                hitId = hitId,
                label = label,
                kind = kind,
                categorySlug = categorySlug,
                timestamp = rows.size.toLong(),
            ),
        )
    }
}

class FakeRoutes(
    private val fn: suspend (LonLat, LonLat) -> RouteResult = { _, _ -> RouteResult.Offline },
) : RouteRepository {
    override suspend fun planTransit(from: LonLat, to: LonLat) = fn(from, to)
}

fun fakeNetwork(online: Boolean = true) = NetworkMonitor { online }

fun fakeLocation(
    available: Boolean = false,
    point: LonLat? = null,
) = object : LocationProvider {
    override fun isAvailable() = available
    override fun lastKnown() = point
}
