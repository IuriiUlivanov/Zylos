package rs.zylos.app.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

fun interface NetworkMonitor {
    fun isOnline(): Boolean
}

class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val appContext = context.applicationContext

    override fun isOnline(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
