package rs.zylos.novisad.map

import android.graphics.Color
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

object SearchMarkerLayers {
    fun ensure(style: Style) {
        val existing = style.getSourceAs<GeoJsonSource>(SearchMarker.SOURCE_ID)
        if (existing == null) {
            style.addSource(
                GeoJsonSource(SearchMarker.SOURCE_ID, SearchMarker.emptyCollectionJson()),
            )
        }
        if (style.getLayer(SearchMarker.LAYER_ID) == null) {
            style.addLayer(
                CircleLayer(SearchMarker.LAYER_ID, SearchMarker.SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(SearchMarker.RADIUS.toFloat()),
                    PropertyFactory.circleColor(Color.parseColor(SearchMarker.FILL_COLOR)),
                    PropertyFactory.circleStrokeWidth(SearchMarker.STROKE_WIDTH.toFloat()),
                    PropertyFactory.circleStrokeColor(Color.parseColor(SearchMarker.STROKE_COLOR)),
                ),
            )
        }
    }

    fun setGeometry(style: Style, featureCollectionJson: String?) {
        ensure(style)
        val json = featureCollectionJson ?: SearchMarker.emptyCollectionJson()
        val source = style.getSourceAs<GeoJsonSource>(SearchMarker.SOURCE_ID) ?: return
        source.setGeoJson(json)
    }

    fun clear(style: Style) {
        setGeometry(style, null)
    }
}
