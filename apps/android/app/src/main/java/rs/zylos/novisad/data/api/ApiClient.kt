package rs.zylos.novisad.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import rs.zylos.novisad.BuildConfig
import java.util.concurrent.TimeUnit

object ApiClient {
    fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "API_URL is empty" }
        return "$trimmed/"
    }

    fun create(apiUrl: String = BuildConfig.API_URL): ZylosApi {
        val builder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
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
}
