package rs.zylos.novisad.data.repository

import rs.zylos.novisad.data.api.BuildingAtResponse
import rs.zylos.novisad.data.api.BuildingDetailResponse
import rs.zylos.novisad.data.api.ZylosApi
import java.io.IOException

sealed class BuildingAtResult {
    data class Found(val body: BuildingAtResponse) : BuildingAtResult()
    data object NotFound : BuildingAtResult()
    data object OutsideCity : BuildingAtResult()
    data object Network : BuildingAtResult()
}

sealed class BuildingDetailResult {
    data class Found(val body: BuildingDetailResponse) : BuildingDetailResult()
    data object NotFound : BuildingDetailResult()
    data object Network : BuildingDetailResult()
}

interface BuildingRepository {
    suspend fun at(lon: Double, lat: Double): BuildingAtResult
    suspend fun byId(id: String): BuildingDetailResult
}

class HttpBuildingRepository(private val api: ZylosApi) : BuildingRepository {
    override suspend fun at(lon: Double, lat: Double): BuildingAtResult {
        return try {
            val response = api.buildingAt(lon, lat)
            when (response.code()) {
                200 -> {
                    val body = response.body()
                    if (body != null) BuildingAtResult.Found(body) else BuildingAtResult.Network
                }
                404 -> BuildingAtResult.NotFound
                422 -> BuildingAtResult.OutsideCity
                else -> BuildingAtResult.Network
            }
        } catch (_: IOException) {
            BuildingAtResult.Network
        }
    }

    override suspend fun byId(id: String): BuildingDetailResult {
        return try {
            val response = api.building(id)
            when (response.code()) {
                200 -> {
                    val body = response.body()
                    if (body != null) BuildingDetailResult.Found(body) else BuildingDetailResult.Network
                }
                404 -> BuildingDetailResult.NotFound
                else -> BuildingDetailResult.Network
            }
        } catch (_: IOException) {
            BuildingDetailResult.Network
        }
    }
}
