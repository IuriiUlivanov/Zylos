package rs.zylos.app.feature.sheet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rs.zylos.app.data.api.BuildingDetailResponse
import rs.zylos.app.data.api.OrgDetailResponse
import rs.zylos.app.data.api.SearchHit
import rs.zylos.app.data.api.SearchKind
import rs.zylos.app.data.repository.BuildingAtResult
import rs.zylos.app.data.repository.BuildingDetailResult
import rs.zylos.app.data.repository.BuildingRepository
import rs.zylos.app.data.repository.OrgDetailResult
import rs.zylos.app.data.repository.OrgRepository
import rs.zylos.app.viewmodel.MapEvent
import rs.zylos.app.viewmodel.MapEventSink
import rs.zylos.app.viewmodel.PeekInfo
import rs.zylos.app.viewmodel.SearchLogic
import rs.zylos.app.viewmodel.SheetMode
import rs.zylos.app.viewmodel.SheetUiState
import rs.zylos.app.viewmodel.UserMessage

class SheetCoordinator(
    private val scope: CoroutineScope,
    private val buildings: BuildingRepository,
    private val orgs: OrgRepository,
    private val events: MapEventSink,
) {
    private val _state = MutableStateFlow(SheetUiState())
    val state: StateFlow<SheetUiState> = _state.asStateFlow()

    private var pickJob: Job? = null
    private var generation = 0

    fun onCleared() {
        pickJob?.cancel()
    }

    fun reset() {
        pickJob?.cancel()
        generation += 1
        _state.value = SheetUiState()
    }

    fun onClosed() {
        reset()
        events.emit(MapEvent.SheetClosed)
    }

    fun showSearchList() {
        pickJob?.cancel()
        generation += 1
        _state.value = SheetUiState(mode = SheetMode.SearchList)
    }

    fun leaveSearchList() {
        if (_state.value.mode == SheetMode.SearchList) {
            _state.value = _state.value.copy(mode = SheetMode.Idle)
        }
    }

    fun restoreFromMarker(): Boolean {
        val sheet = _state.value
        val mode = when {
            sheet.building != null -> if (sheet.org != null) SheetMode.Organization else SheetMode.Building
            sheet.org != null -> SheetMode.Organization
            sheet.peek != null -> SheetMode.Peek
            else -> return false
        }
        _state.value = sheet.copy(mode = mode)
        events.emit(MapEvent.UserFeedback(null, false))
        return true
    }

    fun onBackToBuilding() {
        val building = _state.value.building
        if (building == null) {
            onClosed()
            return
        }
        _state.value = _state.value.copy(
            mode = SheetMode.Building,
            org = null,
            peek = null,
        )
        events.emit(MapEvent.UserFeedback(null, false))
    }

    fun onMapClick(lon: Double, lat: Double) {
        val request = ++generation
        pickJob?.cancel()
        pickJob = scope.launch {
            _state.value = _state.value.copy(mode = SheetMode.Loading, org = null, peek = null)
            events.emit(MapEvent.Snapshot)
            when (val at = buildings.at(lon, lat)) {
                is BuildingAtResult.NotFound -> failNotFound(request)
                is BuildingAtResult.OutsideCity -> failOutsideCity(request)
                is BuildingAtResult.Network -> failNetwork(request)
                is BuildingAtResult.Found -> {
                    when (val detail = buildings.byId(at.body.id)) {
                        is BuildingDetailResult.Found -> {
                            if (request != generation) return@launch
                            _state.value = SheetUiState(
                                mode = SheetMode.Building,
                                building = detail.body,
                            )
                            events.emit(MapEvent.HighlightChanged(detail.body.geometry))
                            events.emit(MapEvent.MarkerChanged(null))
                        }
                        is BuildingDetailResult.NotFound -> failNotFound(request)
                        is BuildingDetailResult.Network -> failNetwork(request)
                    }
                }
            }
        }
    }

    fun onOrgSelected(id: String) {
        val request = ++generation
        val previousBuilding = _state.value.building
        pickJob?.cancel()
        pickJob = scope.launch {
            _state.value = _state.value.copy(
                mode = if (previousBuilding != null) SheetMode.Building else SheetMode.Loading,
            )
            events.emit(MapEvent.UserFeedback(null, false))
            when (val result = orgs.byId(id)) {
                is OrgDetailResult.Found -> {
                    if (request != generation) return@launch
                    val org = result.body
                    val building = resolveOrgBuilding(org.building_id, previousBuilding)
                    if (request != generation) return@launch
                    _state.value = SheetUiState(
                        mode = SheetMode.Organization,
                        org = org,
                        building = building,
                    )
                    events.emit(MapEvent.HighlightChanged(building?.geometry))
                }
                is OrgDetailResult.NotFound, is OrgDetailResult.Network -> failNetwork(request)
            }
        }
    }

    fun openHit(hit: SearchHit) {
        val request = ++generation
        pickJob?.cancel()
        pickJob = scope.launch {
            _state.value = _state.value.copy(mode = SheetMode.Loading)
            events.emit(MapEvent.Snapshot)
            var orgDetail: OrgDetailResponse? = null
            var buildingDetail: BuildingDetailResponse? = null
            if (hit.kind == SearchKind.organization) {
                when (val result = orgs.byId(hit.id)) {
                    is OrgDetailResult.Found -> orgDetail = result.body
                    is OrgDetailResult.NotFound, is OrgDetailResult.Network -> Unit
                }
            }
            val buildingId = hit.building_id ?: orgDetail?.building_id
            if (buildingId != null) {
                when (val detail = buildings.byId(buildingId)) {
                    is BuildingDetailResult.Found -> buildingDetail = detail.body
                    is BuildingDetailResult.NotFound, is BuildingDetailResult.Network -> Unit
                }
            }
            if (request != generation) return@launch
            when {
                orgDetail != null -> {
                    _state.value = SheetUiState(
                        mode = SheetMode.Organization,
                        org = orgDetail,
                        building = buildingDetail,
                    )
                    events.emit(MapEvent.HighlightChanged(buildingDetail?.geometry))
                }
                buildingDetail != null -> {
                    _state.value = SheetUiState(
                        mode = SheetMode.Building,
                        building = buildingDetail,
                    )
                    events.emit(MapEvent.HighlightChanged(buildingDetail.geometry))
                }
                else -> showPeek(hit, request)
            }
        }
    }

    private fun showPeek(hit: SearchHit, request: Int) {
        if (request != generation) return
        _state.value = _state.value.copy(
            mode = SheetMode.Peek,
            building = if (hit.building_id == null) null else _state.value.building,
            org = null,
            peek = PeekInfo(
                title = hit.label,
                subtitle = SearchLogic.peekSubtitle(hit, ADDRESS_FALLBACK, ORG_FALLBACK),
                hit = hit,
            ),
        )
        if (hit.building_id == null) {
            events.emit(MapEvent.HighlightChanged(null))
        } else {
            events.emit(MapEvent.Snapshot)
        }
    }

    private fun failNotFound(request: Int) {
        if (request != generation) {
            return
        }
        _state.value = SheetUiState()
        events.emit(MapEvent.HighlightChanged(null))
        events.emit(MapEvent.MarkerChanged(null))
        events.emit(MapEvent.SelectedHitCleared)
        events.emit(MapEvent.UserFeedback(UserMessage.NotFound, true))
    }

    private fun failOutsideCity(request: Int) {
        if (request != generation) {
            return
        }
        _state.value = SheetUiState()
        events.emit(MapEvent.HighlightChanged(null))
        events.emit(MapEvent.MarkerChanged(null))
        events.emit(MapEvent.SelectedHitCleared)
        events.emit(MapEvent.UserFeedback(UserMessage.OutsideCity, false))
    }

    private fun failNetwork(request: Int) {
        if (request != generation) {
            return
        }
        val previous = _state.value
        val mode = when {
            previous.building != null && previous.org != null -> SheetMode.Organization
            previous.building != null -> SheetMode.Building
            previous.peek != null -> SheetMode.Peek
            else -> SheetMode.Idle
        }
        _state.value = previous.copy(mode = mode)
        events.emit(MapEvent.UserFeedback(UserMessage.Network, false))
    }

    private suspend fun resolveOrgBuilding(
        buildingId: String?,
        previous: BuildingDetailResponse?,
    ): BuildingDetailResponse? {
        if (buildingId == null) {
            return null
        }
        if (previous?.id == buildingId) {
            return previous
        }
        return when (val detail = buildings.byId(buildingId)) {
            is BuildingDetailResult.Found -> detail.body
            is BuildingDetailResult.NotFound, is BuildingDetailResult.Network -> null
        }
    }

    companion object {
        private const val ADDRESS_FALLBACK = "Adresa"
        private const val ORG_FALLBACK = "Organizacija"
    }
}
