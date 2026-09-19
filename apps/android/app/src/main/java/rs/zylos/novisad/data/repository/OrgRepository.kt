package rs.zylos.novisad.data.repository

import rs.zylos.novisad.data.api.OrgDetailResponse
import rs.zylos.novisad.data.api.ZylosApi
import java.io.IOException

sealed class OrgDetailResult {
    data class Found(val body: OrgDetailResponse) : OrgDetailResult()
    data object NotFound : OrgDetailResult()
    data object Network : OrgDetailResult()
}

interface OrgRepository {
    suspend fun byId(id: String): OrgDetailResult
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
}
