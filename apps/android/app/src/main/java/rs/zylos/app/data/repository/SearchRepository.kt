package rs.zylos.app.data.repository

import rs.zylos.app.data.api.SearchResponse
import rs.zylos.app.data.api.ZylosApi
import rs.zylos.app.map.MapDefaults
import java.io.IOException

sealed class SearchResult {
    data class Ok(val body: SearchResponse) : SearchResult()
    data object Unavailable : SearchResult()
    data object Network : SearchResult()
}

interface SearchRepository {
    suspend fun search(q: String, lat: Double, lon: Double, limit: Int = MapDefaults.SEARCH_LIMIT): SearchResult
}

class HttpSearchRepository(private val api: ZylosApi) : SearchRepository {
    override suspend fun search(
        q: String,
        lat: Double,
        lon: Double,
        limit: Int,
    ): SearchResult {
        return try {
            val response = api.search(q = q, limit = limit, lat = lat, lon = lon)
            when (response.code()) {
                200 -> {
                    val body = response.body()
                    if (body != null) SearchResult.Ok(body) else SearchResult.Network
                }
                503 -> SearchResult.Unavailable
                else -> SearchResult.Network
            }
        } catch (_: IOException) {
            SearchResult.Network
        }
    }
}
