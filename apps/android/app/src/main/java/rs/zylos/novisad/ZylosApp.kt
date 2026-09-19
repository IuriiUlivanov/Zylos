package rs.zylos.novisad

import android.app.Application
import org.maplibre.android.MapLibre
import rs.zylos.novisad.data.local.ZylosDatabase

class ZylosApp : Application() {
    lateinit var database: ZylosDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        database = ZylosDatabase.create(this)
    }
}
