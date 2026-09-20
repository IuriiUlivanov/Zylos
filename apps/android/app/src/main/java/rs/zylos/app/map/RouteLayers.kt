package rs.zylos.app.map

import android.graphics.Color
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import rs.zylos.app.viewmodel.RouteLogic

object RouteLayers {
    const val WALK_SOURCE_ID = "route-walk"
    const val WALK_LAYER_ID = "route-walk-line"
    const val TRANSIT_SOURCE_ID = "route-transit"
    const val TRANSIT_LAYER_ID = "route-transit-line"
    const val LABELS_SOURCE_ID = "route-labels"
    const val LABELS_LAYER_ID = "route-transit-label"
    const val FROM_SOURCE_ID = "route-from"
    const val FROM_LAYER_ID = "route-from-dot"
    const val TO_SOURCE_ID = "route-to"
    const val TO_LAYER_ID = "route-to-dot"

    fun ensure(style: Style) {
        addLineSource(style, WALK_SOURCE_ID)
        addLineSource(style, TRANSIT_SOURCE_ID)
        addPointSource(style, LABELS_SOURCE_ID)
        addPointSource(style, FROM_SOURCE_ID)
        addPointSource(style, TO_SOURCE_ID)

        if (style.getLayer(WALK_LAYER_ID) == null) {
            style.addLayer(
                LineLayer(WALK_LAYER_ID, WALK_SOURCE_ID).withProperties(
                    PropertyFactory.lineColor(Color.parseColor(MapDefaults.ROUTE_WALK_COLOR)),
                    PropertyFactory.lineWidth(MapDefaults.ROUTE_LINE_WIDTH_WALK.toFloat()),
                    PropertyFactory.lineDasharray(arrayOf(MapDefaults.ROUTE_DASH, MapDefaults.ROUTE_DASH)),
                    PropertyFactory.lineCap("round"),
                    PropertyFactory.lineJoin("round"),
                ),
            )
        }
        if (style.getLayer(TRANSIT_LAYER_ID) == null) {
            style.addLayer(
                LineLayer(TRANSIT_LAYER_ID, TRANSIT_SOURCE_ID).withProperties(
                    PropertyFactory.lineColor(Expression.get("color")),
                    PropertyFactory.lineWidth(MapDefaults.ROUTE_LINE_WIDTH_TRANSIT.toFloat()),
                    PropertyFactory.lineCap("round"),
                    PropertyFactory.lineJoin("round"),
                ),
            )
        }
        if (style.getLayer(LABELS_LAYER_ID) == null) {
            style.addLayer(
                SymbolLayer(LABELS_LAYER_ID, LABELS_SOURCE_ID).withProperties(
                    PropertyFactory.textField(Expression.get("name")),
                    PropertyFactory.textSize(12f),
                    PropertyFactory.textColor(Color.parseColor("#1F1F1F")),
                    PropertyFactory.textHaloColor(Color.parseColor("#FFFFFF")),
                    PropertyFactory.textHaloWidth(1.4f),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                ),
            )
        }
        if (style.getLayer(FROM_LAYER_ID) == null) {
            style.addLayer(
                CircleLayer(FROM_LAYER_ID, FROM_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleColor(Color.parseColor(MapDefaults.ROUTE_FROM_COLOR)),
                    PropertyFactory.circleStrokeWidth(2f),
                    PropertyFactory.circleStrokeColor(Color.WHITE),
                ),
            )
        }
        if (style.getLayer(TO_LAYER_ID) == null) {
            style.addLayer(
                CircleLayer(TO_LAYER_ID, TO_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleColor(Color.parseColor(MapDefaults.ROUTE_TO_COLOR)),
                    PropertyFactory.circleStrokeWidth(2f),
                    PropertyFactory.circleStrokeColor(Color.WHITE),
                ),
            )
        }
    }

    fun setGeometry(
        style: Style,
        walkJson: String?,
        transitJson: String?,
        labelsJson: String?,
        fromJson: String?,
        toJson: String?,
    ) {
        ensure(style)
        set(style, WALK_SOURCE_ID, walkJson)
        set(style, TRANSIT_SOURCE_ID, transitJson)
        set(style, LABELS_SOURCE_ID, labelsJson)
        set(style, FROM_SOURCE_ID, fromJson)
        set(style, TO_SOURCE_ID, toJson)
    }

    fun clear(style: Style) {
        val empty = RouteLogic.emptyCollectionJson()
        setGeometry(style, empty, empty, empty, empty, empty)
    }

    private fun addLineSource(style: Style, id: String) {
        if (style.getSourceAs<GeoJsonSource>(id) == null) {
            style.addSource(GeoJsonSource(id, RouteLogic.emptyCollectionJson()))
        }
    }

    private fun addPointSource(style: Style, id: String) {
        addLineSource(style, id)
    }

    private fun set(style: Style, sourceId: String, json: String?) {
        val source = style.getSourceAs<GeoJsonSource>(sourceId) ?: return
        source.setGeoJson(json ?: RouteLogic.emptyCollectionJson())
    }
}
