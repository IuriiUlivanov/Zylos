package rs.zylos.app.feature.route

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rs.zylos.app.data.api.LonLat
import rs.zylos.app.data.api.RouteItinerary
import rs.zylos.app.data.api.RouteResponse
import rs.zylos.app.data.api.SearchHit
import rs.zylos.app.data.repository.RouteRepository
import rs.zylos.app.data.repository.RouteResult
import rs.zylos.app.data.repository.SearchRepository
import rs.zylos.app.data.repository.SearchResult
import rs.zylos.app.map.MapDefaults
import rs.zylos.app.platform.LocationProvider
import rs.zylos.app.platform.NetworkMonitor
import rs.zylos.app.viewmodel.BottomTab
import rs.zylos.app.viewmodel.BoundsTarget
import rs.zylos.app.viewmodel.CameraTracker
import rs.zylos.app.viewmodel.MapEvent
import rs.zylos.app.viewmodel.MapEventSink
import rs.zylos.app.viewmodel.MapRouteMode
import rs.zylos.app.viewmodel.RouteField
import rs.zylos.app.viewmodel.RouteLogic
import rs.zylos.app.viewmodel.RoutePoint
import rs.zylos.app.viewmodel.RouteUiError
import rs.zylos.app.viewmodel.RouteUiState
import rs.zylos.app.viewmodel.SearchLogic

class RouteCoordinator(
    private val scope: CoroutineScope,
    private val routes: RouteRepository,
    private val search: SearchRepository,
    private val network: NetworkMonitor,
    private val location: LocationProvider,
    private val camera: CameraTracker,
    private val events: MapEventSink,
) {
    private val _state = MutableStateFlow(RouteUiState())
    val state: StateFlow<RouteUiState> = _state.asStateFlow()

    private var routeJob: Job? = null
    private var routeSearchJob: Job? = null
    private var routeGeneration = 0
    private var routeSearchGeneration = 0
    private var boundsNonce = 0

    fun onCleared() {
        routeJob?.cancel()
        routeSearchJob?.cancel()
    }

    fun consumeError() {
        if (_state.value.error != null) {
            _state.value = _state.value.copy(error = null)
        }
    }

    fun refreshGps() {
        _state.value = _state.value.copy(gpsEnabled = location.isAvailable())
    }

    fun showSearchTab() {
        val previous = _state.value.mode
        val nextMode = if (previous == MapRouteMode.Result) MapRouteMode.Result else MapRouteMode.Idle
        _state.value = _state.value.copy(
            bottomTab = BottomTab.Search,
            mode = nextMode,
            pickField = null,
            fieldFocus = null,
            searchField = null,
            hits = emptyList(),
        )
        if (previous != nextMode) {
            events.emit(MapEvent.RouteModeChanged(nextMode))
        }
    }

    fun enter(destination: RoutePoint?) {
        val from = _state.value.from
        val nextMode = if (_state.value.mode == MapRouteMode.Result) MapRouteMode.Result else MapRouteMode.Planning
        _state.value = _state.value.copy(
            bottomTab = BottomTab.Route,
            mode = nextMode,
            to = _state.value.to ?: destination,
            toQuery = (_state.value.to ?: destination)?.label ?: _state.value.toQuery,
            from = from,
            fromQuery = from?.label ?: _state.value.fromQuery,
            fieldFocus = null,
            searchField = null,
            hits = emptyList(),
        )
        events.emit(MapEvent.BlurSearch)
        events.emit(MapEvent.RouteModeChanged(nextMode))
    }

    fun onBuildRoute() {
        val from = _state.value.from
        val to = _state.value.to
        if (from == null || to == null) {
            return
        }
        if (!network.isOnline()) {
            _state.value = _state.value.copy(loading = false, error = RouteUiError.Offline)
            return
        }
        routeJob?.cancel()
        val request = ++routeGeneration
        routeJob = scope.launch {
            _state.value = _state.value.copy(
                bottomTab = BottomTab.Route,
                mode = MapRouteMode.Planning,
                loading = true,
                error = null,
                pickField = null,
                fieldFocus = null,
                searchField = null,
            )
            events.emit(MapEvent.RouteModeChanged(MapRouteMode.Planning))
            when (val result = routes.planTransit(LonLat(from.lon, from.lat), LonLat(to.lon, to.lat))) {
                is RouteResult.Ok -> {
                    if (request != routeGeneration) return@launch
                    applyRouteResult(result.body)
                }
                RouteResult.OutsideCity -> failRoute(request, RouteUiError.OutsideCity)
                RouteResult.NoRoute -> failRoute(request, RouteUiError.NoRoute)
                RouteResult.Timeout -> failRoute(request, RouteUiError.Timeout)
                RouteResult.Offline, RouteResult.Invalid -> failRoute(request, RouteUiError.Offline)
            }
        }
    }

    fun onSwapRoute() {
        val from = _state.value.from
        val to = _state.value.to
        val fromQuery = _state.value.fromQuery
        val toQuery = _state.value.toQuery
        _state.value = _state.value.copy(
            from = to,
            to = from,
            fromQuery = toQuery,
            toQuery = fromQuery,
        )
        if (_state.value.mode == MapRouteMode.Result && to != null && from != null) {
            onBuildRoute()
        }
    }

    fun onClearRoute() {
        routeJob?.cancel()
        routeSearchJob?.cancel()
        routeGeneration += 1
        _state.value = _state.value.copy(
            bottomTab = BottomTab.Search,
            mode = MapRouteMode.Idle,
            loading = false,
            error = null,
            itineraries = emptyList(),
            activeItineraryIndex = 0,
            pickField = null,
            fieldFocus = null,
            searchField = null,
            hits = emptyList(),
        )
        events.emit(MapEvent.ClearBounds)
        events.emit(MapEvent.RouteCleared)
    }

    fun onSelectItinerary(index: Int) {
        val items = _state.value.itineraries
        if (index !in items.indices) {
            return
        }
        applyActiveItinerary(items, index)
    }

    fun onFieldFocus(field: RouteField?) {
        _state.value = _state.value.copy(
            fieldFocus = field,
            searchField = field ?: _state.value.searchField,
        )
        events.emit(MapEvent.BlurSearch)
    }

    fun onQueryChange(field: RouteField, text: String) {
        routeSearchJob?.cancel()
        _state.value = when (field) {
            RouteField.From -> _state.value.copy(
                fromQuery = text,
                error = null,
                searchField = field,
                pickField = null,
            )
            RouteField.To -> _state.value.copy(
                toQuery = text,
                error = null,
                searchField = field,
                pickField = null,
            )
        }
        if (!SearchLogic.shouldRequest(text)) {
            routeSearchGeneration += 1
            _state.value = _state.value.copy(hits = emptyList(), searchLoading = false)
            return
        }
        routeSearchJob = scope.launch {
            delay(MapDefaults.SEARCH_DEBOUNCE_MS)
            val request = ++routeSearchGeneration
            _state.value = _state.value.copy(searchLoading = true)
            events.emit(MapEvent.Snapshot)
            when (val result = search.search(text, camera.lat, camera.lon)) {
                is SearchResult.Ok -> {
                    if (request != routeSearchGeneration) return@launch
                    _state.value = _state.value.copy(hits = result.body.hits, searchLoading = false)
                    events.emit(MapEvent.Snapshot)
                }
                is SearchResult.Unavailable, is SearchResult.Network -> {
                    if (request != routeSearchGeneration) return@launch
                    _state.value = _state.value.copy(hits = emptyList(), searchLoading = false)
                    events.emit(MapEvent.Snapshot)
                }
            }
        }
    }

    fun onSelectHit(hit: SearchHit) {
        routeSearchJob?.cancel()
        routeSearchGeneration += 1
        assignPoint(
            activeFieldForSearch(),
            RoutePoint(hit.lon, hit.lat, hit.label),
        )
    }

    fun onNaKartu(field: RouteField) {
        _state.value = _state.value.copy(
            pickField = field,
            searchField = field,
            fieldFocus = null,
            hits = emptyList(),
        )
        events.emit(MapEvent.BlurSearch)
    }

    fun onMapPicked(field: RouteField, lon: Double, lat: Double, label: String = mapPickLabel(lon, lat)) {
        assignPoint(field, RoutePoint(lon, lat, label))
    }

    fun onMapLongClick(lon: Double, lat: Double) {
        if (_state.value.bottomTab != BottomTab.Route) {
            return
        }
        onMapPicked(activeFieldForMap(), lon, lat)
    }

    fun onFillMyLocation() {
        refreshGps()
        val loc = location.lastKnown() ?: return
        assignPoint(RouteField.From, RoutePoint(loc.lon, loc.lat, MY_LOCATION_LABEL))
    }

    fun handleMapTap(lon: Double, lat: Double): Boolean {
        if (_state.value.bottomTab != BottomTab.Route) {
            return false
        }
        val pick = _state.value.pickField ?: return false
        onMapPicked(pick, lon, lat)
        return true
    }

    fun clearFocus() {
        _state.value = _state.value.copy(fieldFocus = null)
    }

    private fun activeFieldForSearch(): RouteField {
        return _state.value.searchField ?: _state.value.fieldFocus ?: RouteField.To
    }

    private fun activeFieldForMap(): RouteField {
        return _state.value.pickField
            ?: _state.value.fieldFocus
            ?: _state.value.searchField
            ?: RouteField.To
    }

    private fun assignPoint(field: RouteField, point: RoutePoint) {
        val wasResult = _state.value.mode == MapRouteMode.Result
        val nextMode = when {
            wasResult -> MapRouteMode.Planning
            _state.value.mode == MapRouteMode.Idle -> MapRouteMode.Planning
            else -> _state.value.mode
        }
        _state.value = when (field) {
            RouteField.From -> _state.value.copy(
                from = point,
                fromQuery = point.label,
                pickField = null,
                fieldFocus = null,
                searchField = null,
                hits = emptyList(),
                mode = nextMode,
                bottomTab = BottomTab.Route,
            )
            RouteField.To -> _state.value.copy(
                to = point,
                toQuery = point.label,
                pickField = null,
                fieldFocus = null,
                searchField = null,
                hits = emptyList(),
                mode = nextMode,
                bottomTab = BottomTab.Route,
            )
        }
        if (wasResult) {
            clearLayersOnly()
            events.emit(MapEvent.RouteModeChanged(MapRouteMode.Planning))
        }
    }

    private fun clearLayersOnly() {
        routeJob?.cancel()
        routeGeneration += 1
        _state.value = _state.value.copy(
            itineraries = emptyList(),
            activeItineraryIndex = 0,
            loading = false,
        )
        events.emit(MapEvent.ClearBounds)
    }

    private fun failRoute(request: Int, error: RouteUiError) {
        if (request != routeGeneration) {
            return
        }
        _state.value = _state.value.copy(
            loading = false,
            error = error,
            mode = MapRouteMode.Planning,
        )
        events.emit(MapEvent.RouteModeChanged(MapRouteMode.Planning))
    }

    private fun applyRouteResult(body: RouteResponse) {
        val sorted = RouteLogic.sortItineraries(RouteLogic.itinerariesOf(body))
        if (sorted.isEmpty()) {
            failRoute(routeGeneration, RouteUiError.NoRoute)
            return
        }
        _state.value = _state.value.copy(
            mode = MapRouteMode.Result,
            loading = false,
            error = null,
            itineraries = sorted,
            bottomTab = BottomTab.Route,
        )
        events.emit(MapEvent.RouteModeChanged(MapRouteMode.Result))
        applyActiveItinerary(sorted, 0)
    }

    private fun applyActiveItinerary(items: List<RouteItinerary>, index: Int) {
        val itinerary = items.getOrNull(index) ?: return
        val from = _state.value.from
        val to = _state.value.to
        _state.value = _state.value.copy(activeItineraryIndex = index)
        events.emit(
            MapEvent.FitBounds(
                BoundsTarget(
                    points = RouteLogic.boundsPoints(itinerary, from, to),
                    durationMs = MapDefaults.FLY_DURATION_MS,
                    nonce = ++boundsNonce,
                    topHalf = true,
                ),
            ),
        )
    }

    private fun mapPickLabel(lon: Double, lat: Double): String {
        return String.format("%.5f, %.5f", lat, lon)
    }

    companion object {
        private const val MY_LOCATION_LABEL = "Moja lokacija"
    }
}
