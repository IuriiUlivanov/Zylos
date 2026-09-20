package rs.zylos.app

import android.app.Application
import org.maplibre.android.MapLibre
import rs.zylos.app.data.local.ZylosDatabase

class ZylosApp : Application() {
    lateinit var database: ZylosDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        database = ZylosDatabase.create(this)
    }
}
