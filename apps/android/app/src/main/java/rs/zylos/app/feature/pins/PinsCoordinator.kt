package rs.zylos.app.feature.pins

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rs.zylos.app.data.api.BuildingGeometry
import rs.zylos.app.data.api.LonLat
import rs.zylos.app.data.api.SearchHit
import rs.zylos.app.data.repository.OrgBboxResult
import rs.zylos.app.data.repository.OrgRepository
import rs.zylos.app.map.OrgPinLimits
import rs.zylos.app.map.OrgPinLogic
import rs.zylos.app.viewmodel.CameraTracker
import rs.zylos.app.viewmodel.MapEvent
import rs.zylos.app.viewmodel.MapEventSink
import rs.zylos.app.viewmodel.MapPinMode
import rs.zylos.app.viewmodel.MapRouteMode
import rs.zylos.app.viewmodel.OverlayUiState
import rs.zylos.app.viewmodel.SearchLogic

class PinsCoordinator(
    private val scope: CoroutineScope,
    private val orgs: OrgRepository,
    private val camera: CameraTracker,
    private val events: MapEventSink,
) {
    private val _state = MutableStateFlow(OverlayUiState())
    val state: StateFlow<OverlayUiState> = _state.asStateFlow()

    private var orgPinsJob: Job? = null
    private var orgPinsGeneration = 0
    private var routeMode: MapRouteMode = MapRouteMode.Idle

    fun onCleared() {
        orgPinsJob?.cancel()
    }

    fun setRouteMode(mode: MapRouteMode) {
        routeMode = mode
    }

    fun setHighlight(geometry: BuildingGeometry?) {
        if (_state.value.highlight == geometry) {
            return
        }
        _state.value = _state.value.copy(highlight = geometry)
    }

    fun setMarker(point: LonLat?) {
        if (_state.value.marker == point) {
            return
        }
        _state.value = _state.value.copy(marker = point)
    }

    fun clearHighlight() {
        setHighlight(null)
    }

    fun resetBrowse() {
        orgPinsJob?.cancel()
        _state.value = OverlayUiState(
            pinMode = MapPinMode.Browse,
            orgPins = _state.value.orgPins,
        )
    }

    fun prepareMapPick() {
        orgPinsJob?.cancel()
        _state.value = _state.value.copy(
            pinMode = MapPinMode.Browse,
            marker = null,
            searchPins = emptyList(),
        )
    }

    fun enterSearchSingle(hit: SearchHit) {
        orgPinsJob?.cancel()
        _state.value = _state.value.copy(
            pinMode = MapPinMode.SearchSingle,
            marker = LonLat(hit.lon, hit.lat),
            searchPins = emptyList(),
        )
    }

    fun enterSearchMulti(hits: List<SearchHit>): Boolean {
        val pins = SearchLogic.multiPins(hits)
        if (pins.isEmpty()) {
            return false
        }
        orgPinsJob?.cancel()
        _state.value = _state.value.copy(
            pinMode = MapPinMode.SearchMulti,
            marker = null,
            searchPins = pins,
            highlight = null,
        )
        return true
    }

    fun leaveSearchMulti() {
        if (_state.value.pinMode != MapPinMode.SearchMulti) {
            return
        }
        _state.value = _state.value.copy(
            pinMode = MapPinMode.Browse,
            searchPins = emptyList(),
        )
    }

    fun schedule() {
        orgPinsJob?.cancel()
        if (_state.value.pinMode != MapPinMode.Browse || routeMode != MapRouteMode.Idle) {
            return
        }
        if (!OrgPinLimits.shouldRequest(camera.zoom)) {
            orgPinsGeneration += 1
            _state.value = _state.value.copy(orgPins = emptyList())
            return
        }
        val bbox = camera.bbox ?: return
        val zoom = camera.zoom
        orgPinsJob = scope.launch {
            delay(OrgPinLimits.debounceMs(zoom))
            if (_state.value.pinMode != MapPinMode.Browse) return@launch
            if (routeMode != MapRouteMode.Idle) return@launch
            val latest = camera.bbox ?: bbox
            if (!OrgPinLimits.shouldRequest(camera.zoom)) {
                _state.value = _state.value.copy(orgPins = emptyList())
                events.emit(MapEvent.Snapshot)
                return@launch
            }
            val request = ++orgPinsGeneration
            val limit = OrgPinLimits.limit(camera.zoom)
            when (val result = orgs.inBbox(latest.minLon, latest.minLat, latest.maxLon, latest.maxLat, limit)) {
                is OrgBboxResult.Ok -> {
                    if (request != orgPinsGeneration) return@launch
                    if (_state.value.pinMode != MapPinMode.Browse) return@launch
                    if (routeMode != MapRouteMode.Idle) return@launch
                    val visible = OrgPinLogic.visiblePins(result.pins, camera.zoom, latest)
                    _state.value = _state.value.copy(orgPins = visible)
                    events.emit(MapEvent.Snapshot)
                }
                is OrgBboxResult.Network -> {
                    if (request != orgPinsGeneration) return@launch
                }
            }
        }
    }
}
