package rs.zylos.novisad.data.api

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.ToJson
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

object ApiJson {
    private val anyAdapter: com.squareup.moshi.JsonAdapter<Any> =
        Moshi.Builder().build().adapter(Any::class.java)

    val moshi: Moshi = Moshi.Builder()
        .add(BuildingGeometryJson())
        .addLast(KotlinJsonAdapterFactory())
        .build()

    class BuildingGeometryJson {
        @FromJson
        fun fromJson(reader: JsonReader): BuildingGeometry {
            val value = reader.readJsonValue()
            val encoded = anyAdapter.toJson(value)
            val map = value as? Map<*, *>
            val type = map?.get("type") as? String ?: "Polygon"
            return BuildingGeometry(
                type = type,
                coordinates = map?.get("coordinates"),
                encodedJson = encoded,
            )
        }

        @ToJson
        fun toJson(writer: JsonWriter, value: BuildingGeometry) {
            val parsed = anyAdapter.fromJson(value.toGeoJsonObject())
            anyAdapter.toJson(writer, parsed)
        }
    }
}
