package rs.zylos.novisad.map

import android.graphics.Color
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

object BuildingHighlightLayers {
    fun ensure(style: Style) {
        val existing = style.getSourceAs<GeoJsonSource>(BuildingHighlight.SOURCE_ID)
        if (existing == null) {
            style.addSource(
                GeoJsonSource(BuildingHighlight.SOURCE_ID, BuildingHighlight.emptyCollectionJson()),
            )
        }
        if (style.getLayer(BuildingHighlight.FILL_LAYER_ID) == null) {
            style.addLayer(
                FillLayer(BuildingHighlight.FILL_LAYER_ID, BuildingHighlight.SOURCE_ID).withProperties(
                    PropertyFactory.fillColor(Color.parseColor(BuildingHighlight.FILL_COLOR)),
                    PropertyFactory.fillOpacity(BuildingHighlight.FILL_OPACITY.toFloat()),
                ),
            )
        }
        if (style.getLayer(BuildingHighlight.OUTLINE_LAYER_ID) == null) {
            style.addLayer(
                LineLayer(BuildingHighlight.OUTLINE_LAYER_ID, BuildingHighlight.SOURCE_ID).withProperties(
                    PropertyFactory.lineColor(Color.parseColor(BuildingHighlight.OUTLINE_COLOR)),
                    PropertyFactory.lineWidth(BuildingHighlight.OUTLINE_WIDTH.toFloat()),
                ),
            )
        }
    }

    fun setGeometry(style: Style, featureCollectionJson: String?) {
        ensure(style)
        val json = featureCollectionJson ?: BuildingHighlight.emptyCollectionJson()
        val source = style.getSourceAs<GeoJsonSource>(BuildingHighlight.SOURCE_ID) ?: return
        source.setGeoJson(json)
    }

    fun clear(style: Style) {
        setGeometry(style, null)
    }
}
