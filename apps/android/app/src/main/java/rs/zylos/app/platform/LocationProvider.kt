package rs.zylos.app.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import rs.zylos.app.data.api.LonLat

interface LocationProvider {
    fun isAvailable(): Boolean
    fun lastKnown(): LonLat?
}

class AndroidLocationProvider(context: Context) : LocationProvider {
    private val appContext = context.applicationContext

    override fun isAvailable(): Boolean {
        if (!hasPermission()) {
            return false
        }
        val lm = appContext.getSystemService(LocationManager::class.java) ?: return false
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    @SuppressLint("MissingPermission")
    override fun lastKnown(): LonLat? {
        if (!hasPermission()) {
            return null
        }
        val lm = appContext.getSystemService(LocationManager::class.java) ?: return null
        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: return null
        return LonLat(loc.longitude, loc.latitude)
    }

    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }
}
