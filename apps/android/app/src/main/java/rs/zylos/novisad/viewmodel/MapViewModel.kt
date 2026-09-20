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
import rs.zylos.novisad.data.repository.HttpRouteRepository
import rs.zylos.novisad.data.repository.HttpSearchRepository
import rs.zylos.novisad.data.repository.OrgBboxResult
import rs.zylos.novisad.data.repository.OrgDetailResult
import rs.zylos.novisad.data.repository.OrgRepository
import rs.zylos.novisad.data.repository.RouteRepository
import rs.zylos.novisad.data.repository.RouteResult
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
    private val routes: RouteRepository = IdleRoutes,
) : ViewModel() {
    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private var pickJob: Job? = null
    private var searchJob: Job? = null
    private var orgPinsJob: Job? = null
    private var routeJob: Job? = null
    private var routeSearchJob: Job? = null
    private var generation = 0
    private var searchGeneration = 0
    private var orgPinsGeneration = 0
    private var routeGeneration = 0
    private var routeSearchGeneration = 0
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
        val current = _state.value.building
        if (current != null && current.organizations.none { it.id == id }) {
            _state.value = _state.value.copy(highlightJson = null)
        }
        onOrgSelected(id)
    }

    fun onMapClick(lon: Double, lat: Double) {
        if (_state.value.routePickField != null) {
            onMapPicked(_state.value.routePickField!!, lon, lat)
            return
        }
        if (_state.value.dropdownOpen) {
            _state.value = _state.value.copy(searchFocused = false, routeFieldFocus = null)
            return
        }
        if (_state.value.routeMode == MapRouteMode.Result ||
            _state.value.routeMode == MapRouteMode.Planning
        ) {
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
        val previousBuilding = _state.value.building
        pickJob?.cancel()
        pickJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                mode = if (previousBuilding != null) SheetMode.Building else SheetMode.Loading,
                message = null,
                haptic = false,
                generation = request,
            )
            when (val result = orgs.byId(id)) {
                is OrgDetailResult.Found -> {
                    if (request != generation) return@launch
                    val org = result.body
                    val building = resolveOrgBuilding(org.building_id, previousBuilding)
                    if (request != generation) return@launch
                    _state.value = _state.value.copy(
                        mode = SheetMode.Organization,
                        org = org,
                        building = building,
                        peek = null,
                        highlightJson = building?.let { BuildingHighlight.collectionJson(it.geometry) },
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

    fun consumeRouteError() {
        if (_state.value.routeError != null) {
            _state.value = _state.value.copy(routeError = null)
        }
    }

    fun onGpsAvailability(enabled: Boolean) {
        _state.value = _state.value.copy(gpsEnabled = enabled)
    }

    fun onSelectTab(tab: BottomTab) {
        if (_state.value.bottomTab == tab) {
            return
        }
        if (tab == BottomTab.Search) {
            _state.value = _state.value.copy(
                bottomTab = BottomTab.Search,
                routePickField = null,
                routeFieldFocus = null,
                routeSearchField = null,
                routeHits = emptyList(),
            )
            if (_state.value.routeMode == MapRouteMode.Idle) {
                scheduleOrgPins()
            }
            return
        }
        enterRouteTab()
    }

    fun onBuildCta(online: Boolean) {
        onBuildRoute(online)
    }

    fun onBuildRoute(online: Boolean) {
        val from = _state.value.routeFrom
        val to = _state.value.routeTo
        if (from == null || to == null) {
            return
        }
        if (!online) {
            _state.value = _state.value.copy(routeLoading = false, routeError = RouteUiError.Offline)
            return
        }
        routeJob?.cancel()
        val request = ++routeGeneration
        routeJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                bottomTab = BottomTab.Route,
                routeMode = MapRouteMode.Planning,
                routeLoading = true,
                routeError = null,
                routePickField = null,
                routeFieldFocus = null,
                routeSearchField = null,
            )
            when (val result = routes.planTransit(LonLat(from.lon, from.lat), LonLat(to.lon, to.lat))) {
                is RouteResult.Ok -> {
                    if (request != routeGeneration) return@launch
                    applyRouteResult(result.body, request)
                }
                RouteResult.OutsideCity -> failRoute(request, RouteUiError.OutsideCity)
                RouteResult.NoRoute -> failRoute(request, RouteUiError.NoRoute)
                RouteResult.Timeout -> failRoute(request, RouteUiError.Timeout)
                RouteResult.Offline, RouteResult.Invalid -> failRoute(request, RouteUiError.Offline)
            }
        }
    }

    fun onSwapRoute(online: Boolean) {
        val from = _state.value.routeFrom
        val to = _state.value.routeTo
        val fromQuery = _state.value.routeFromQuery
        val toQuery = _state.value.routeToQuery
        _state.value = _state.value.copy(
            routeFrom = to,
            routeTo = from,
            routeFromQuery = toQuery,
            routeToQuery = fromQuery,
        )
        if (_state.value.routeMode == MapRouteMode.Result && to != null && from != null) {
            onBuildRoute(online)
        }
    }

    fun onClearRoute() {
        routeJob?.cancel()
        routeSearchJob?.cancel()
        routeGeneration += 1
        _state.value = _state.value.copy(
            bottomTab = BottomTab.Search,
            routeMode = MapRouteMode.Idle,
            routeLoading = false,
            routeError = null,
            routeItineraries = emptyList(),
            activeItineraryIndex = 0,
            routeWalkJson = null,
            routeTransitJson = null,
            routeLabelsJson = null,
            routeFromJson = null,
            routeToJson = null,
            routePickField = null,
            routeFieldFocus = null,
            routeSearchField = null,
            routeHits = emptyList(),
            bounds = null,
        )
        scheduleOrgPins()
    }

    fun onSelectItinerary(index: Int) {
        val items = _state.value.routeItineraries
        if (index !in items.indices) {
            return
        }
        applyActiveItinerary(items, index, ++generation)
    }

    fun onRouteFieldFocus(field: RouteField?) {
        _state.value = _state.value.copy(
            routeFieldFocus = field,
            routeSearchField = field ?: _state.value.routeSearchField,
            searchFocused = false,
        )
    }

    fun onRouteQueryChange(field: RouteField, text: String) {
        routeSearchJob?.cancel()
        _state.value = when (field) {
            RouteField.From -> _state.value.copy(
                routeFromQuery = text,
                routeError = null,
                routeSearchField = field,
                routePickField = null,
            )
            RouteField.To -> _state.value.copy(
                routeToQuery = text,
                routeError = null,
                routeSearchField = field,
                routePickField = null,
            )
        }
        if (!SearchLogic.shouldRequest(text)) {
            routeSearchGeneration += 1
            _state.value = _state.value.copy(routeHits = emptyList(), routeSearchLoading = false)
            return
        }
        routeSearchJob = viewModelScope.launch {
            delay(MapDefaults.SEARCH_DEBOUNCE_MS)
            val request = ++routeSearchGeneration
            _state.value = _state.value.copy(routeSearchLoading = true)
            when (val result = search.search(text, cameraLat, cameraLon)) {
                is SearchResult.Ok -> {
                    if (request != routeSearchGeneration) return@launch
                    _state.value = _state.value.copy(
                        routeHits = result.body.hits,
                        routeSearchLoading = false,
                    )
                }
                is SearchResult.Unavailable, is SearchResult.Network -> {
                    if (request != routeSearchGeneration) return@launch
                    _state.value = _state.value.copy(
                        routeHits = emptyList(),
                        routeSearchLoading = false,
                    )
                }
            }
        }
    }

    fun onSelectRouteHit(hit: SearchHit) {
        routeSearchJob?.cancel()
        routeSearchGeneration += 1
        val point = RoutePoint(hit.lon, hit.lat, hit.label)
        assignRoutePoint(activeRouteFieldForSearch(), point)
    }

    fun onNaKartu(field: RouteField) {
        _state.value = _state.value.copy(
            routePickField = field,
            routeSearchField = field,
            routeFieldFocus = null,
            searchFocused = false,
            routeHits = emptyList(),
        )
    }

    fun onMapPicked(field: RouteField, lon: Double, lat: Double, label: String = mapPickLabel(lon, lat)) {
        assignRoutePoint(field, RoutePoint(lon, lat, label))
    }

    fun onMapLongClick(lon: Double, lat: Double) {
        if (_state.value.bottomTab != BottomTab.Route && _state.value.routeMode == MapRouteMode.Idle) {
            return
        }
        onMapPicked(activeRouteFieldForMap(), lon, lat)
    }

    fun onMyLocation(lon: Double, lat: Double) {
        assignRoutePoint(RouteField.From, RoutePoint(lon, lat, MY_LOCATION_LABEL))
    }

    private fun enterRouteTab() {
        val dest = selectedDestination()
        val from = _state.value.routeFrom
        val nextFrom = from ?: if (_state.value.gpsEnabled) _state.value.routeFrom else from
        _state.value = _state.value.copy(
            bottomTab = BottomTab.Route,
            routeMode = if (_state.value.routeMode == MapRouteMode.Result) MapRouteMode.Result else MapRouteMode.Planning,
            routeTo = _state.value.routeTo ?: dest,
            routeToQuery = (_state.value.routeTo ?: dest)?.label ?: _state.value.routeToQuery,
            routeFrom = nextFrom,
            routeFromQuery = nextFrom?.label ?: _state.value.routeFromQuery,
            searchFocused = false,
            routeFieldFocus = null,
            routeSearchField = null,
            routeHits = emptyList(),
        )
    }

    private fun selectedDestination(): RoutePoint? {
        val org = _state.value.org
        if (org != null) {
            val label = org.address?.label?.takeIf { it.isNotBlank() } ?: org.name
            return RoutePoint(org.location.lon, org.location.lat, label)
        }
        val building = _state.value.building
        if (building != null) {
            val label = building.addresses.firstOrNull()?.label
                ?: building.name
                ?: building.id
            return RoutePoint(building.centroid.lon, building.centroid.lat, label)
        }
        val hit = _state.value.selectedHit ?: _state.value.peek?.hit
        if (hit != null) {
            return RoutePoint(hit.lon, hit.lat, hit.label)
        }
        return null
    }

    private fun activeRouteFieldForSearch(): RouteField {
        return _state.value.routeSearchField
            ?: _state.value.routeFieldFocus
            ?: RouteField.To
    }

    private fun activeRouteFieldForMap(): RouteField {
        return _state.value.routePickField
            ?: _state.value.routeFieldFocus
            ?: _state.value.routeSearchField
            ?: RouteField.To
    }

    private fun assignRoutePoint(field: RouteField, point: RoutePoint) {
        val wasResult = _state.value.routeMode == MapRouteMode.Result
        _state.value = when (field) {
            RouteField.From -> _state.value.copy(
                routeFrom = point,
                routeFromQuery = point.label,
                routePickField = null,
                routeFieldFocus = null,
                routeSearchField = null,
                routeHits = emptyList(),
                routeMode = if (wasResult) MapRouteMode.Planning else {
                    if (_state.value.routeMode == MapRouteMode.Idle) MapRouteMode.Planning else _state.value.routeMode
                },
                bottomTab = BottomTab.Route,
            )
            RouteField.To -> _state.value.copy(
                routeTo = point,
                routeToQuery = point.label,
                routePickField = null,
                routeFieldFocus = null,
                routeSearchField = null,
                routeHits = emptyList(),
                routeMode = if (wasResult) MapRouteMode.Planning else {
                    if (_state.value.routeMode == MapRouteMode.Idle) MapRouteMode.Planning else _state.value.routeMode
                },
                bottomTab = BottomTab.Route,
            )
        }
        if (wasResult) {
            clearRouteLayersOnly()
        }
    }

    private fun clearRouteLayersOnly() {
        routeJob?.cancel()
        routeGeneration += 1
        _state.value = _state.value.copy(
            routeItineraries = emptyList(),
            activeItineraryIndex = 0,
            routeWalkJson = null,
            routeTransitJson = null,
            routeLabelsJson = null,
            routeFromJson = null,
            routeToJson = null,
            routeLoading = false,
            bounds = null,
        )
    }

    private fun failRoute(request: Int, error: RouteUiError) {
        if (request != routeGeneration) {
            return
        }
        _state.value = _state.value.copy(
            routeLoading = false,
            routeError = error,
            routeMode = MapRouteMode.Planning,
        )
    }

    private fun applyRouteResult(body: rs.zylos.novisad.data.api.RouteResponse, request: Int) {
        val sorted = RouteLogic.sortItineraries(RouteLogic.itinerariesOf(body))
        if (sorted.isEmpty()) {
            failRoute(request, RouteUiError.NoRoute)
            return
        }
        _state.value = _state.value.copy(
            routeMode = MapRouteMode.Result,
            routeLoading = false,
            routeError = null,
            routeItineraries = sorted,
            bottomTab = BottomTab.Route,
        )
        applyActiveItinerary(sorted, 0, request)
    }

    private fun applyActiveItinerary(
        items: List<rs.zylos.novisad.data.api.RouteItinerary>,
        index: Int,
        nonce: Int,
    ) {
        val itinerary = items.getOrNull(index) ?: return
        val from = _state.value.routeFrom
        val to = _state.value.routeTo
        _state.value = _state.value.copy(
            activeItineraryIndex = index,
            routeWalkJson = RouteLogic.walkCollectionJson(itinerary),
            routeTransitJson = RouteLogic.transitCollectionJson(itinerary),
            routeLabelsJson = RouteLogic.labelsCollectionJson(itinerary),
            routeFromJson = from?.let { RouteLogic.pointCollectionJson(it.lon, it.lat) },
            routeToJson = to?.let { RouteLogic.pointCollectionJson(it.lon, it.lat) },
            bounds = BoundsTarget(
                points = RouteLogic.boundsPoints(itinerary, from, to),
                durationMs = MapDefaults.FLY_DURATION_MS,
                nonce = nonce,
                topHalf = true,
            ),
        )
    }

    private fun mapPickLabel(lon: Double, lat: Double): String {
        return String.format("%.5f, %.5f", lat, lon)
    }

    override fun onCleared() {
        pickJob?.cancel()
        searchJob?.cancel()
        orgPinsJob?.cancel()
        routeJob?.cancel()
        routeSearchJob?.cancel()
        super.onCleared()
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
        if (_state.value.pinMode != MapPinMode.Browse || _state.value.routeMode != MapRouteMode.Idle) {
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
            if (_state.value.routeMode != MapRouteMode.Idle) return@launch
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
                    if (_state.value.routeMode != MapRouteMode.Idle) return@launch
                    val visible = OrgPinLogic.visiblePins(result.pins, cameraZoom, latest)
                    _state.value = _state.value.copy(orgPinsJson = OrgPins.collectionJson(visible))
                }
                is OrgBboxResult.Network -> {
                    if (request != orgPinsGeneration) return@launch
                }
            }
        }
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
        private const val MY_LOCATION_LABEL = "Moja lokacija"

        private object IdleSearch : SearchRepository {
            override suspend fun search(q: String, lat: Double, lon: Double, limit: Int) =
                SearchResult.Ok(rs.zylos.novisad.data.api.SearchResponse(q, emptyList(), 0))
        }

        private object IdleHistory : SearchHistoryStore {
            override suspend fun recent() = emptyList<rs.zylos.novisad.data.local.SearchHistoryEntity>()
            override suspend fun save(query: String, hitId: String?, label: String?, kind: String?, categorySlug: String?) = Unit
        }

        private object IdleRoutes : RouteRepository {
            override suspend fun planTransit(from: LonLat, to: LonLat) = RouteResult.Offline
        }

        fun factory(api: ZylosApi, historyDao: SearchHistoryDao, routeApi: ZylosApi = api): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MapViewModel(
                        HttpBuildingRepository(api),
                        HttpOrgRepository(api),
                        HttpSearchRepository(api),
                        RoomSearchHistoryStore(historyDao),
                        HttpRouteRepository(routeApi),
                    ) as T
                }
            }
        }
    }
}
