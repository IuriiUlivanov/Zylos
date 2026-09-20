package rs.zylos.app.map

import android.graphics.Color
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

object OrgPinsLayers {
    fun ensure(style: Style) {
        if (style.getSourceAs<GeoJsonSource>(OrgPins.SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(OrgPins.SOURCE_ID, OrgPins.emptyCollectionJson()))
        }
        if (style.getLayer(OrgPins.CIRCLE_LAYER_ID) == null) {
            style.addLayer(
                CircleLayer(OrgPins.CIRCLE_LAYER_ID, OrgPins.SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(
                        Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.stop(15, 5.6f),
                            Expression.stop(16, 6.4f),
                            Expression.stop(17, 6.8f),
                            Expression.stop(18, 7.2f),
                        ),
                    ),
                    PropertyFactory.circleColor(Expression.get("color")),
                    PropertyFactory.circleStrokeWidth(OrgPins.STROKE_WIDTH.toFloat()),
                    PropertyFactory.circleStrokeColor(Color.parseColor(OrgPins.STROKE_COLOR)),
                    PropertyFactory.circleSortKey(Expression.get("display_rank")),
                ),
            )
        }
        if (style.getLayer(OrgPins.LABEL_LAYER_ID) == null) {
            val labels = SymbolLayer(OrgPins.LABEL_LAYER_ID, OrgPins.SOURCE_ID).withProperties(
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textSize(11f),
                PropertyFactory.textOffset(arrayOf(0f, 1.05f)),
                PropertyFactory.textAnchor("top"),
                PropertyFactory.textOptional(true),
                PropertyFactory.textAllowOverlap(false),
                PropertyFactory.textColor(Color.parseColor("#3F3F3F")),
                PropertyFactory.textHaloColor(Color.parseColor("#F7F3EB")),
                PropertyFactory.textHaloWidth(1.2f),
                PropertyFactory.symbolSortKey(Expression.get("display_rank")),
            )
            labels.minZoom = 17f
            style.addLayer(labels)
        }
    }

    fun setGeometry(style: Style, featureCollectionJson: String?) {
        ensure(style)
        val json = featureCollectionJson ?: OrgPins.emptyCollectionJson()
        val source = style.getSourceAs<GeoJsonSource>(OrgPins.SOURCE_ID) ?: return
        source.setGeoJson(json)
    }
}
