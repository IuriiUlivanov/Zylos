package rs.zylos.app.data.repository

import rs.zylos.app.data.api.OrgDetailResponse
import rs.zylos.app.data.api.OrgPin
import rs.zylos.app.data.api.ZylosApi
import java.io.IOException

sealed class OrgDetailResult {
    data class Found(val body: OrgDetailResponse) : OrgDetailResult()
    data object NotFound : OrgDetailResult()
    data object Network : OrgDetailResult()
}

sealed class OrgBboxResult {
    data class Ok(val pins: List<OrgPin>) : OrgBboxResult()
    data object Network : OrgBboxResult()
}

interface OrgRepository {
    suspend fun byId(id: String): OrgDetailResult
    suspend fun inBbox(
        minLon: Double,
        minLat: Double,
        maxLon: Double,
        maxLat: Double,
        limit: Int,
    ): OrgBboxResult
}

class HttpOrgRepository(private val api: ZylosApi) : OrgRepository {
    override suspend fun byId(id: String): OrgDetailResult {
        return try {
            val response = api.org(id)
            when (response.code()) {
                200 -> {
                    val body = response.body()
                    if (body != null) OrgDetailResult.Found(body) else OrgDetailResult.Network
                }
                404 -> OrgDetailResult.NotFound
                else -> OrgDetailResult.Network
            }
        } catch (_: IOException) {
            OrgDetailResult.Network
        }
    }

    override suspend fun inBbox(
        minLon: Double,
        minLat: Double,
        maxLon: Double,
        maxLat: Double,
        limit: Int,
    ): OrgBboxResult {
        return try {
            val bbox = "$minLon,$minLat,$maxLon,$maxLat"
            val response = api.orgs(bbox, limit)
            when (response.code()) {
                200 -> OrgBboxResult.Ok(response.body().orEmpty())
                else -> OrgBboxResult.Network
            }
        } catch (_: IOException) {
            OrgBboxResult.Network
        }
    }
}
