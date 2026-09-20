package rs.zylos.app.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import rs.zylos.app.BuildConfig
import java.util.concurrent.TimeUnit

object ApiClient {
    fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "API_URL is empty" }
        return "$trimmed/"
    }

    fun create(
        apiUrl: String = BuildConfig.API_URL,
        connectTimeoutMs: Long = 8_000,
        readTimeoutMs: Long = 15_000,
        callTimeoutMs: Long = 20_000,
    ): ZylosApi {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request()
                        .newBuilder()
                        .header("Accept", "application/json")
                        .header("User-Agent", "Zylos-Android/${BuildConfig.VERSION_NAME}")
                        .build(),
                )
            }
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
            )
        }
        return Retrofit.Builder()
            .baseUrl(normalizeBaseUrl(apiUrl))
            .client(builder.build())
            .addConverterFactory(MoshiConverterFactory.create(ApiJson.moshi))
            .build()
            .create(ZylosApi::class.java)
    }

    fun createRoute(apiUrl: String = BuildConfig.API_URL): ZylosApi {
        val timeout = 8_000L
        return create(
            apiUrl = apiUrl,
            connectTimeoutMs = timeout,
            readTimeoutMs = timeout,
            callTimeoutMs = timeout,
        )
    }
}
