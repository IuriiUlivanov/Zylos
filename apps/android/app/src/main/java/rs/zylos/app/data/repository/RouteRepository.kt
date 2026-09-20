package rs.zylos.app.data.repository

import rs.zylos.app.data.api.LonLat
import rs.zylos.app.data.api.RouteErrorBody
import rs.zylos.app.data.api.RouteRequest
import rs.zylos.app.data.api.RouteResponse
import rs.zylos.app.data.api.ZylosApi
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive

sealed class RouteResult {
    data class Ok(val body: RouteResponse) : RouteResult()
    data object OutsideCity : RouteResult()
    data object NoRoute : RouteResult()
    data object Timeout : RouteResult()
    data object Offline : RouteResult()
    data object Invalid : RouteResult()
}

interface RouteRepository {
    suspend fun planTransit(from: LonLat, to: LonLat): RouteResult
}

class HttpRouteRepository(private val api: ZylosApi) : RouteRepository {
    override suspend fun planTransit(from: LonLat, to: LonLat): RouteResult {
        return try {
            val response = api.route(RouteRequest(from = from, to = to, mode = "transit"))
            if (!coroutineContext.isActive) {
                throw CancellationException()
            }
            when (response.code()) {
                200 -> {
                    val body = response.body()
                    if (body != null) RouteResult.Ok(body) else RouteResult.Timeout
                }
                400 -> RouteResult.Invalid
                404 -> RouteResult.NoRoute
                422 -> RouteResult.OutsideCity
                504 -> RouteResult.Timeout
                else -> {
                    val error = errorCode(response.errorBody()?.string())
                    when (error) {
                        "outside_city" -> RouteResult.OutsideCity
                        "no_route" -> RouteResult.NoRoute
                        "routing_timeout" -> RouteResult.Timeout
                        else -> RouteResult.Offline
                    }
                }
            }
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (_: SocketTimeoutException) {
            RouteResult.Timeout
        } catch (_: IOException) {
            RouteResult.Offline
        }
    }

    private fun errorCode(raw: String?): String? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return try {
            rs.zylos.app.data.api.ApiJson.moshi.adapter(RouteErrorBody::class.java).fromJson(raw)?.error
        } catch (_: Exception) {
            null
        }
    }
}
