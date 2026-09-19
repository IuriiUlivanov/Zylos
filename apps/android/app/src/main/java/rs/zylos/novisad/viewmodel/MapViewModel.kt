package rs.zylos.novisad.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rs.zylos.novisad.data.api.BuildingDetailResponse
import rs.zylos.novisad.data.api.LonLat
import rs.zylos.novisad.data.api.OrgDetailResponse
import rs.zylos.novisad.data.api.SearchHit
import rs.zylos.novisad.data.api.SearchKind
import rs.zylos.novisad.data.api.ZylosApi
import rs.zylos.novisad.data.local.RoomSearchHistoryStore
import rs.zylos.novisad.data.local.SearchHistoryDao
import rs.zylos.novisad.data.local.SearchHistoryStore
import rs.zylos.novisad.data.repository.BuildingAtResult
import rs.zylos.novisad.data.repository.BuildingDetailResult
import rs.zylos.novisad.data.repository.BuildingRepository
import rs.zylos.novisad.data.repository.HttpBuildingRepository
import rs.zylos.novisad.data.repository.HttpOrgRepository
import rs.zylos.novisad.data.repository.HttpSearchRepository
import rs.zylos.novisad.data.repository.OrgBboxResult
import rs.zylos.novisad.data.repository.OrgDetailResult
import rs.zylos.novisad.data.repository.OrgRepository
import rs.zylos.novisad.data.repository.SearchRepository
import rs.zylos.novisad.data.repository.SearchResult
import rs.zylos.novisad.map.BuildingHighlight
import rs.zylos.novisad.map.MapBbox
import rs.zylos.novisad.map.MapDefaults
import rs.zylos.novisad.map.OrgPinLimits
import rs.zylos.novisad.map.OrgPinLogic
import rs.zylos.novisad.map.OrgPins
import rs.zylos.novisad.map.SearchMarker
import rs.zylos.novisad.map.SearchPins

class MapViewModel(
    private val buildings: BuildingRepository,
    private val orgs: OrgRepository,
    private val search: SearchRepository = IdleSearch,
    private val history: SearchHistoryStore = IdleHistory,
) : ViewModel() {
    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private var pickJob: Job? = null
    private var searchJob: Job? = null
    private var orgPinsJob: Job? = null
    private var generation = 0
    private var searchGeneration = 0
    private var orgPinsGeneration = 0
    private var cameraLat = MapDefaults.LAT
    private var cameraLon = MapDefaults.LON
    private var cameraZoom = MapDefaults.ZOOM
    private var cameraBbox: MapBbox? = null

    init {
        viewModelScope.launch {
            val rows = history.recent()
            _state.value = _state.value.copy(history = rows)
        }
    }

    fun onCameraIdle(
        lat: Double,
        lon: Double,
        zoom: Double,
        minLon: Double? = null,
        minLat: Double? = null,
        maxLon: Double? = null,
        maxLat: Double? = null,
    ) {
        cameraLat = lat
        cameraLon = lon
        cameraZoom = zoom
        if (minLon != null && minLat != null && maxLon != null && maxLat != null) {
            cameraBbox = MapBbox(minLon, minLat, maxLon, maxLat)
        }
        scheduleOrgPins()
    }

    fun onSearchFocusChanged(focused: Boolean) {
        _state.value = _state.value.copy(searchFocused = focused)
    }

    fun onQueryChange(text: String) {
        searchJob?.cancel()
        _state.value = _state.value.copy(
            query = text,
            searchError = null,
            message = null,
            haptic = false,
        )
        if (!SearchLogic.shouldRequest(text)) {
            searchGeneration += 1
            val leavingMulti = _state.value.pinMode == MapPinMode.SearchMulti
            _state.value = _state.value.copy(
                hits = emptyList(),
                searchLoading = false,
                pinMode = if (leavingMulti) MapPinMode.Browse else _state.value.pinMode,
                searchPinsJson = if (leavingMulti) null else _state.value.searchPinsJson,
                mode = if (leavingMulti && _state.value.mode == SheetMode.SearchList) {
                    SheetMode.Idle
                } else {
                    _state.value.mode
                },
            )
            if (leavingMulti) {
                scheduleOrgPins()
            }
            return
        }
        searchJob = viewModelScope.launch {
            delay(MapDefaults.SEARCH_DEBOUNCE_MS)
            val request = ++searchGeneration
            _state.value = _state.value.copy(searchLoading = true, searchError = null)
            when (val result = search.search(text, cameraLat, cameraLon)) {
                is SearchResult.Ok -> {
                    if (request != searchGeneration) return@launch
                    val empty = result.body.hits.isEmpty()
                    val eligible = SearchLogic.isMultiEligible(result.body.hits)
                    _state.value = _state.value.copy(
                        hits = result.body.hits,
                        searchLoading = false,
                        searchError = if (empty) SearchUiError.Empty else null,
                    )
                    if (eligible && _state.value.pinMode != MapPinMode.SearchSingle) {
                        enterSearchMulti(result.body.hits)
                    } else if (!eligible && _state.value.pinMode == MapPinMode.SearchMulti) {
                        _state.value = _state.value.copy(
                            pinMode = MapPinMode.Browse,
                            searchPinsJson = null,
                            mode = if (_state.value.mode == SheetMode.SearchList) SheetMode.Idle else _state.value.mode,
                            bounds = null,
                        )
                        scheduleOrgPins()
                    }
                }
                is SearchResult.Unavailable -> {
                    if (request != searchGeneration) return@launch
                    _state.value = _state.value.copy(
                        hits = emptyList(),
                        searchLoading = false,
                        searchError = SearchUiError.Unavailable,
                    )
                }
                is SearchResult.Network -> {
                    if (request != searchGeneration) return@launch
                    _state.value = _state.value.copy(
                        hits = emptyList(),
                        searchLoading = false,
                        searchError = SearchUiError.Network,
                    )
                }
            }
        }
    }

    fun onClearSearch() {
        searchJob?.cancel()
        orgPinsJob?.cancel()
        searchGeneration += 1
        generation += 1
        pickJob?.cancel()
        _state.value = SheetLogic.reduceClose(
            _state.value.copy(
                query = "",
                hits = emptyList(),
                searchLoading = false,
                searchError = null,
                searchFocused = true,
                camera = null,
                bounds = null,
                generation = generation,
            ),
        ).copy(searchFocused = true)
        scheduleOrgPins()
    }

    fun onSelectHit(hit: SearchHit) {
        searchJob?.cancel()
        searchGeneration += 1
        val request = ++generation
        pickJob?.cancel()
        val zoom = SearchLogic.flyZoom(cameraZoom)
        val queryToStore = _state.value.query.ifBlank { hit.label }
        pickJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                query = hit.label,
                searchFocused = false,
                searchLoading = false,
                searchError = null,
                selectedHit = hit,
                pinMode = MapPinMode.SearchSingle,
                markerJson = SearchMarker.pointJson(hit.lon, hit.lat),
                searchPinsJson = null,
                camera = CameraTarget(
                    lat = hit.lat,
                    lon = hit.lon,
                    zoom = zoom,
                    durationMs = MapDefaults.FLY_DURATION_MS,
                    nonce = request,
                    anchorYFromBottom = MapDefaults.SEARCH_FLYTO_ANCHOR_Y,
                ),
                bounds = null,
                mode = SheetMode.Loading,
                message = null,
                haptic = false,
                generation = request,
            )
            history.save(queryToStore, hit.id, hit.label, hit.kind.name, hit.category_slug)
            val rows = history.recent()
            if (request != generation) return@launch
            _state.value = _state.value.copy(history = rows)
            openHit(hit, request)
        }
    }

    fun onHistoryQuery(query: String) {
        onQueryChange(query)
        _state.value = _state.value.copy(searchFocused = true)
    }

    fun onSearchMarkerClick() {
        val hit = _state.value.selectedHit ?: return
        if (_state.value.dropdownOpen) {
            return
        }
        when {
            _state.value.building != null -> {
                _state.value = _state.value.copy(
                    mode = if (_state.value.org != null) SheetMode.Organization else SheetMode.Building,
                    message = null,
                )
            }
            _state.value.org != null -> {
                _state.value = _state.value.copy(mode = SheetMode.Organization, message = null)
            }
            _state.value.peek != null -> {
                _state.value = _state.value.copy(mode = SheetMode.Peek, message = null)
            }
            else -> onSelectHit(hit)
        }
    }

    fun onSearchPinClick(id: String?) {
        if (id.isNullOrBlank() || _state.value.dropdownOpen) {
            return
        }
        val hit = _state.value.hits.firstOrNull { it.id == id } ?: return
        onSelectHit(hit)
    }

    fun onShowAllOnMap() {
        val hits = _state.value.hits
        if (hits.isEmpty()) {
            return
        }
        enterSearchMulti(hits)
    }

    fun onOrgPinClick(id: String?) {
        if (id.isNullOrBlank() || _state.value.dropdownOpen) {
            return
        }
        onOrgSelected(id)
    }

    fun onMapClick(lon: Double, lat: Double) {
        if (_state.value.dropdownOpen) {
            _state.value = _state.value.copy(searchFocused = false)
            return
        }
        val request = ++generation
        pickJob?.cancel()
        pickJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                mode = SheetMode.Loading,
                pinMode = MapPinMode.Browse,
                org = null,
                peek = null,
                markerJson = null,
                searchPinsJson = null,
                selectedHit = null,
                searchFocused = false,
                bounds = null,
                message = null,
                haptic = false,
                generation = request,
            )
            when (val at = buildings.at(lon, lat)) {
                is BuildingAtResult.NotFound -> {
                    if (request != generation) return@launch
                    _state.value = SheetLogic.reduceNotFound(request, _state.value)
                }
                is BuildingAtResult.OutsideCity -> {
                    if (request != generation) return@launch
                    _state.value = SheetLogic.reduceOutsideCity(request, _state.value)
                }
                is BuildingAtResult.Network -> {
                    if (request != generation) return@launch
                    _state.value = SheetLogic.reduceNetwork(request, _state.value)
                }
                is BuildingAtResult.Found -> {
                    when (val detail = buildings.byId(at.body.id)) {
                        is BuildingDetailResult.Found -> {
                            if (request != generation) return@launch
                            _state.value = _state.value.copy(
                                mode = SheetMode.Building,
                                building = detail.body,
                                org = null,
                                peek = null,
                                highlightJson = BuildingHighlight.collectionJson(detail.body.geometry),
                                markerJson = null,
                                selectedHit = null,
                                generation = request,
                            )
                        }
                        is BuildingDetailResult.NotFound -> {
                            if (request != generation) return@launch
                            _state.value = SheetLogic.reduceNotFound(request, _state.value)
                        }
                        is BuildingDetailResult.Network -> {
                            if (request != generation) return@launch
                            _state.value = SheetLogic.reduceNetwork(request, _state.value)
                        }
                    }
                }
            }
        }
    }

    fun onOrgSelected(id: String) {
        val request = ++generation
        val building = _state.value.building
        pickJob?.cancel()
        pickJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                mode = if (building != null) SheetMode.Building else SheetMode.Loading,
                message = null,
                haptic = false,
                generation = request,
            )
            when (val result = orgs.byId(id)) {
                is OrgDetailResult.Found -> {
                    if (request != generation) return@launch
                    _state.value = _state.value.copy(
                        mode = SheetMode.Organization,
                        org = result.body,
                        building = building,
                        peek = null,
                        message = null,
                        haptic = false,
                        generation = request,
                    )
                }
                is OrgDetailResult.NotFound, is OrgDetailResult.Network -> {
                    if (request != generation) return@launch
                    _state.value = SheetLogic.reduceNetwork(request, _state.value)
                }
            }
        }
    }

    fun onBackToBuilding() {
        _state.value = SheetLogic.reduceBackToBuilding(_state.value)
    }

    fun onSheetClosed() {
        generation += 1
        pickJob?.cancel()
        _state.value = SheetLogic.reduceClose(_state.value.copy(generation = generation))
        scheduleOrgPins()
    }

    fun consumeMessage() {
        if (_state.value.message != null) {
            _state.value = _state.value.copy(message = null, haptic = false)
        }
    }

    fun consumeCamera() {
        if (_state.value.camera != null) {
            _state.value = _state.value.copy(camera = null)
        }
    }

    fun consumeBounds() {
        if (_state.value.bounds != null) {
            _state.value = _state.value.copy(bounds = null)
        }
    }

    override fun onCleared() {
        pickJob?.cancel()
        searchJob?.cancel()
        orgPinsJob?.cancel()
        super.onCleared()
    }

    private fun enterSearchMulti(hits: List<SearchHit>) {
        val pins = SearchLogic.multiPins(hits)
        if (pins.isEmpty()) {
            return
        }
        val request = ++generation
        _state.value = _state.value.copy(
            pinMode = MapPinMode.SearchMulti,
            searchFocused = false,
            searchLoading = false,
            selectedHit = null,
            markerJson = null,
            searchPinsJson = SearchPins.collectionJson(pins),
            mode = SheetMode.SearchList,
            peek = null,
            org = null,
            building = null,
            highlightJson = null,
            camera = null,
            bounds = BoundsTarget(
                points = pins.map { LonLat(it.lon, it.lat) },
                durationMs = MapDefaults.FLY_DURATION_MS,
                nonce = request,
            ),
            generation = request,
        )
    }

    private fun scheduleOrgPins() {
        orgPinsJob?.cancel()
        if (_state.value.pinMode != MapPinMode.Browse) {
            return
        }
        if (!OrgPinLimits.shouldRequest(cameraZoom)) {
            orgPinsGeneration += 1
            _state.value = _state.value.copy(orgPinsJson = OrgPins.emptyCollectionJson())
            return
        }
        val bbox = cameraBbox ?: return
        val zoom = cameraZoom
        orgPinsJob = viewModelScope.launch {
            delay(OrgPinLimits.debounceMs(zoom))
            if (_state.value.pinMode != MapPinMode.Browse) return@launch
            val latest = cameraBbox ?: bbox
            if (!OrgPinLimits.shouldRequest(cameraZoom)) {
                _state.value = _state.value.copy(orgPinsJson = OrgPins.emptyCollectionJson())
                return@launch
            }
            val request = ++orgPinsGeneration
            val limit = OrgPinLimits.limit(cameraZoom)
            when (val result = orgs.inBbox(latest.minLon, latest.minLat, latest.maxLon, latest.maxLat, limit)) {
                is OrgBboxResult.Ok -> {
                    if (request != orgPinsGeneration) return@launch
                    if (_state.value.pinMode != MapPinMode.Browse) return@launch
                    val visible = OrgPinLogic.visiblePins(result.pins, cameraZoom, latest)
                    _state.value = _state.value.copy(orgPinsJson = OrgPins.collectionJson(visible))
                }
                is OrgBboxResult.Network -> {
                    if (request != orgPinsGeneration) return@launch
                }
            }
        }
    }

    private suspend fun openHit(hit: SearchHit, request: Int) {
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
        if (request != generation) return
        when {
            orgDetail != null -> {
                _state.value = _state.value.copy(
                    mode = SheetMode.Organization,
                    org = orgDetail,
                    building = buildingDetail,
                    peek = null,
                    highlightJson = buildingDetail?.let { BuildingHighlight.collectionJson(it.geometry) },
                    generation = request,
                )
            }
            buildingDetail != null -> {
                _state.value = _state.value.copy(
                    mode = SheetMode.Building,
                    building = buildingDetail,
                    org = null,
                    peek = null,
                    highlightJson = BuildingHighlight.collectionJson(buildingDetail.geometry),
                    generation = request,
                )
            }
            else -> showPeek(hit, request)
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
            highlightJson = if (hit.building_id == null) null else _state.value.highlightJson,
            generation = request,
        )
    }

    companion object {
        private const val ADDRESS_FALLBACK = "Adresa"
        private const val ORG_FALLBACK = "Organizacija"

        private object IdleSearch : SearchRepository {
            override suspend fun search(q: String, lat: Double, lon: Double, limit: Int) =
                SearchResult.Ok(rs.zylos.novisad.data.api.SearchResponse(q, emptyList(), 0))
        }

        private object IdleHistory : SearchHistoryStore {
            override suspend fun recent() = emptyList<rs.zylos.novisad.data.local.SearchHistoryEntity>()
            override suspend fun save(query: String, hitId: String?, label: String?, kind: String?, categorySlug: String?) = Unit
        }

        fun factory(api: ZylosApi, historyDao: SearchHistoryDao): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MapViewModel(
                        HttpBuildingRepository(api),
                        HttpOrgRepository(api),
                        HttpSearchRepository(api),
                        RoomSearchHistoryStore(historyDao),
                    ) as T
                }
            }
        }
    }
}
