package rs.zylos.app.map

import android.graphics.RectF
import android.view.Gravity
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import rs.zylos.app.viewmodel.MapPinMode
import rs.zylos.app.viewmodel.MapRouteMode
import rs.zylos.app.viewmodel.MapUiState
import rs.zylos.app.viewmodel.MapViewModel
import rs.zylos.app.viewmodel.RouteLogic
import rs.zylos.app.viewmodel.SearchLogic

class MapOverlayBinder(
    private val mapView: MapView,
    private val viewModel: MapViewModel,
    private val dp: (Int) -> Int,
    private val insetTop: () -> Int,
    private val dockReservePx: () -> Int,
    private val hideKeyboard: () -> Unit,
) {
    var map: MapLibreMap? = null
        private set
    private var mapStyle: Style? = null
    private var lastCameraNonce = 0
    private var lastBoundsNonce = 0
    private var searchFlyPaddingActive = false
    var lastPinMode: MapPinMode = MapPinMode.Browse
        private set

    fun attach(mapLibre: MapLibreMap, style: Style) {
        map = mapLibre
        mapStyle = style
        BuildingHighlightLayers.ensure(style)
        OrgPinsLayers.ensure(style)
        SearchPinsLayers.ensure(style)
        SearchMarkerLayers.ensure(style)
        RouteLayers.ensure(style)
        render(viewModel.state.value)
        mapLibre.addOnMapLongClickListener { latLng ->
            viewModel.onMapLongClick(latLng.longitude, latLng.latitude)
            true
        }
        mapLibre.addOnMapClickListener { latLng ->
            hideKeyboard()
            val state = viewModel.state.value
            if (state.dropdownOpen && state.route.pickField == null) {
                viewModel.onSearchFocusChanged(false)
                viewModel.onRouteFieldFocus(null)
                return@addOnMapClickListener true
            }
            val screen = mapLibre.projection.toScreenLocation(latLng)
            val slop = dp(16).toFloat()
            val box = RectF(screen.x - slop, screen.y - slop, screen.x + slop, screen.y + slop)
            val routeHits = mapLibre.queryRenderedFeatures(
                box,
                RouteLayers.FROM_LAYER_ID,
                RouteLayers.TO_LAYER_ID,
            )
            if (routeHits.isNotEmpty()) {
                return@addOnMapClickListener true
            }
            val markerHits = mapLibre.queryRenderedFeatures(box, SearchMarker.LAYER_ID)
            if (markerHits.isNotEmpty()) {
                viewModel.onSearchMarkerClick()
                return@addOnMapClickListener true
            }
            val searchPinHits = mapLibre.queryRenderedFeatures(box, SearchPins.LAYER_ID)
            if (searchPinHits.isNotEmpty()) {
                viewModel.onSearchPinClick(searchPinHits[0].getStringProperty("id"))
                return@addOnMapClickListener true
            }
            val orgHits = mapLibre.queryRenderedFeatures(
                box,
                OrgPins.CIRCLE_LAYER_ID,
                OrgPins.LABEL_LAYER_ID,
            )
            if (orgHits.isNotEmpty()) {
                viewModel.onOrgPinClick(orgHits[0].getStringProperty("id"))
                return@addOnMapClickListener true
            }
            viewModel.onMapClick(latLng.longitude, latLng.latitude)
            true
        }
    }

    fun render(state: MapUiState) {
        val style = mapStyle ?: return
        val browse = state.overlay.pinMode == MapPinMode.Browse
        val searchMulti = state.overlay.pinMode == MapPinMode.SearchMulti
        BuildingHighlightLayers.setGeometry(
            style,
            state.overlay.highlight?.let { BuildingHighlight.collectionJson(it) },
        )
        OrgPinsLayers.setGeometry(
            style,
            if (browse) OrgPins.collectionJson(state.overlay.orgPins) else null,
        )
        SearchPinsLayers.setGeometry(
            style,
            if (searchMulti) SearchPins.collectionJson(state.overlay.searchPins) else null,
        )
        val marker = state.overlay.marker
        SearchMarkerLayers.setGeometry(
            style,
            if (marker != null) SearchMarker.pointJson(marker.lon, marker.lat) else null,
        )
        renderRouteLayers(state, style)
        applyPoiSearchDim(!browse)
        renderCamera(state)
        renderBounds(state)
        if (state.overlay.pinMode == MapPinMode.SearchMulti && lastPinMode != MapPinMode.SearchMulti) {
            hideKeyboard()
        }
        lastPinMode = state.overlay.pinMode
    }

    fun resetSearchCameraPadding() {
        if (!searchFlyPaddingActive) {
            return
        }
        searchFlyPaddingActive = false
        val mapLibre = map ?: return
        mapLibre.easeCamera(
            CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0),
            MapDefaults.SHEET_ANIMATION_MS,
        )
    }

    fun bumpZoom(delta: Double) {
        val mapLibre = map ?: return
        val next = (mapLibre.cameraPosition.zoom + delta).coerceIn(0.0, 22.0)
        mapLibre.easeCamera(CameraUpdateFactory.zoomTo(next), MapDefaults.SHEET_ANIMATION_MS)
    }

    fun applyAttributionMargins(bottomPx: Int) {
        map?.uiSettings?.setAttributionGravity(Gravity.BOTTOM or Gravity.START)
        map?.uiSettings?.setAttributionMargins(dp(12), 0, 0, bottomPx)
    }

    private fun renderRouteLayers(state: MapUiState, style: Style) {
        val itinerary = state.route.activeItinerary
        if (state.route.mode == MapRouteMode.Result && itinerary != null) {
            val from = state.route.from
            val to = state.route.to
            RouteLayers.setGeometry(
                style,
                RouteLogic.walkCollectionJson(itinerary),
                RouteLogic.transitCollectionJson(itinerary),
                RouteLogic.labelsCollectionJson(itinerary),
                from?.let { RouteLogic.pointCollectionJson(it.lon, it.lat) },
                to?.let { RouteLogic.pointCollectionJson(it.lon, it.lat) },
            )
        } else {
            RouteLayers.clear(style)
        }
    }

    private fun applyPoiSearchDim(dim: Boolean) {
        val style = mapStyle ?: return
        val opacity = if (dim) 0.5f else 1.0f
        MapStyleFactory.POI_DOT_LAYER_IDS.forEach { id ->
            style.getLayer(id)?.setProperties(PropertyFactory.circleOpacity(opacity))
        }
        MapStyleFactory.POI_LABEL_LAYER_IDS.forEach { id ->
            style.getLayer(id)?.setProperties(PropertyFactory.textOpacity(opacity))
        }
    }

    private fun renderCamera(state: MapUiState) {
        val target = state.camera ?: return
        if (target.nonce == lastCameraNonce) {
            return
        }
        lastCameraNonce = target.nonce
        val mapLibre = map ?: return
        val height = if (mapView.height > 0) mapView.height else mapView.resources.displayMetrics.heightPixels
        val bottomPadding = SearchLogic.flyBottomPaddingPx(height, target.anchorYFromBottom).toDouble()
        val current = mapLibre.cameraPosition
        mapLibre.easeCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(target.lat, target.lon))
                    .zoom(target.zoom)
                    .tilt(current.tilt)
                    .bearing(current.bearing)
                    .padding(0.0, 0.0, 0.0, bottomPadding)
                    .build(),
            ),
            target.durationMs,
        )
        searchFlyPaddingActive = bottomPadding > 0.0
        viewModel.consumeCamera()
    }

    private fun renderBounds(state: MapUiState) {
        val target = state.bounds ?: return
        if (target.nonce == lastBoundsNonce) {
            return
        }
        lastBoundsNonce = target.nonce
        val mapLibre = map ?: return
        val points = target.points
        if (points.isEmpty()) {
            viewModel.consumeBounds()
            return
        }
        val padLeft = dp(32)
        val padRight = dp(32)
        val padTop = insetTop() + dp(48)
        val padBottom = if (target.topHalf) {
            (mapView.height * MapDefaults.ROUTE_FIT_TOP_RATIO).toInt().coerceAtLeast(dockReservePx())
        } else {
            dockReservePx() + dp(MapDefaults.SHEET_STEP1_DP) + dp(24)
        }
        if (points.size == 1) {
            mapLibre.easeCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(points[0].lat, points[0].lon),
                    MapDefaults.FLY_MIN_ZOOM,
                ),
                target.durationMs,
            )
        } else {
            val builder = LatLngBounds.Builder()
            points.forEach { builder.include(LatLng(it.lat, it.lon)) }
            try {
                mapLibre.easeCamera(
                    CameraUpdateFactory.newLatLngBounds(
                        builder.build(),
                        padLeft,
                        padTop,
                        padRight,
                        padBottom,
                    ),
                    target.durationMs,
                )
            } catch (_: Exception) {
                val first = points.first()
                mapLibre.easeCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(first.lat, first.lon),
                        MapDefaults.FLY_MIN_ZOOM,
                    ),
                    target.durationMs,
                )
            }
        }
        viewModel.consumeBounds()
    }
}
