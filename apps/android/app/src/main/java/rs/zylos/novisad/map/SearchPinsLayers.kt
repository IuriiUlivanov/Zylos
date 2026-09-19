package rs.zylos.novisad.map

import android.graphics.Color
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

object SearchPinsLayers {
    fun ensure(style: Style) {
        if (style.getSourceAs<GeoJsonSource>(SearchPins.SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(SearchPins.SOURCE_ID, SearchPins.emptyCollectionJson()))
        }
        if (style.getLayer(SearchPins.LAYER_ID) == null) {
            style.addLayer(
                CircleLayer(SearchPins.LAYER_ID, SearchPins.SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(SearchPins.RADIUS.toFloat()),
                    PropertyFactory.circleColor(Color.parseColor(SearchPins.FILL_COLOR)),
                    PropertyFactory.circleStrokeWidth(SearchPins.STROKE_WIDTH.toFloat()),
                    PropertyFactory.circleStrokeColor(Color.parseColor(SearchPins.STROKE_COLOR)),
                ),
            )
        }
    }

    fun setGeometry(style: Style, featureCollectionJson: String?) {
        ensure(style)
        val json = featureCollectionJson ?: SearchPins.emptyCollectionJson()
        val source = style.getSourceAs<GeoJsonSource>(SearchPins.SOURCE_ID) ?: return
        source.setGeoJson(json)
    }
}
