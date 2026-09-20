package rs.zylos.app.viewmodel

import rs.zylos.app.map.CityConfig
import rs.zylos.app.map.MapBbox

class CameraTracker {
    var lat: Double = CityConfig.LAT
        private set
    var lon: Double = CityConfig.LON
        private set
    var zoom: Double = CityConfig.INITIAL_ZOOM
        private set
    var bbox: MapBbox? = null
        private set

    fun update(
        lat: Double,
        lon: Double,
        zoom: Double,
        minLon: Double? = null,
        minLat: Double? = null,
        maxLon: Double? = null,
        maxLat: Double? = null,
    ) {
        this.lat = lat
        this.lon = lon
        this.zoom = zoom
        if (minLon != null && minLat != null && maxLon != null && maxLat != null) {
            bbox = MapBbox(minLon, minLat, maxLon, maxLat)
        }
    }
}
