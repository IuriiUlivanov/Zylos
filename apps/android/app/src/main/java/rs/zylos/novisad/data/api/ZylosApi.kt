package rs.zylos.novisad.data.api

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface ZylosApi {
    @GET("v1/buildings/at")
    suspend fun buildingAt(
        @Query("lon") lon: Double,
        @Query("lat") lat: Double,
    ): Response<BuildingAtResponse>

    @GET("v1/buildings/{id}")
    suspend fun building(
        @Path("id") id: String,
    ): Response<BuildingDetailResponse>

    @GET("v1/orgs")
    suspend fun orgs(
        @Query("bbox") bbox: String,
        @Query("limit") limit: Int,
    ): Response<@JvmSuppressWildcards List<OrgPin>>

    @GET("v1/orgs/{id}")
    suspend fun org(
        @Path("id") id: String,
    ): Response<OrgDetailResponse>

    @GET("v1/search")
    suspend fun search(
        @Query("q") q: String,
        @Query("limit") limit: Int = 10,
        @Query("lat") lat: Double? = null,
        @Query("lon") lon: Double? = null,
        @Query("kind") kind: String? = null,
    ): Response<SearchResponse>
}
