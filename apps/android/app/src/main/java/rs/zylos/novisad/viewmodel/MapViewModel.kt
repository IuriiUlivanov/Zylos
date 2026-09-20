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
import rs.zylos.novisad.map.MapBbox
import rs.zylos.novisad.map.MapDefaults
import rs.zylos.novisad.map.OrgPinLimits
import rs.zylos.novisad.map.OrgPinLogic
import rs.zylos.novisad.platform.LocationProvider
import rs.zylos.novisad.platform.NetworkMonitor

class MapViewModel(
    private val buildings: BuildingRepository,
    private val orgs: OrgRepository,
    private val search: SearchRepository = IdleSearch,
    private val history: SearchHistoryStore = IdleHistory,
    private val routes: RouteRepository = IdleRoutes,
    private val network: NetworkMonitor = NetworkMonitor { true },
    private val location: LocationProvider = IdleLocation,
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
            _state.value = _state.value.withSearch { it.copy(history = rows) }
        }
        refreshGpsAvailability()
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
        _state.value = _state.value.withSearch { it.copy(focused = focused) }
    }

    fun onQueryChange(text: String) {
        searchJob?.cancel()
        _state.value = _state.value
            .withSearch { it.copy(query = text, error = null) }
            .copy(message = null, haptic = false)
        if (!SearchLogic.shouldRequest(text)) {
            searchGeneration += 1
            val leavingMulti = _state.value.overlay.pinMode == MapPinMode.SearchMulti
            _state.value = _state.value
                .withSearch { it.copy(hits = emptyList(), loading = false) }
                .withOverlay { overlay ->
                    overlay.copy(
                        pinMode = if (leavingMulti) MapPinMode.Browse else overlay.pinMode,
                        searchPins = if (leavingMulti) emptyList() else overlay.searchPins,
                    )
                }
                .withSheet { sheet ->
                    sheet.copy(
                        mode = if (leavingMulti && sheet.mode == SheetMode.SearchList) {
                            SheetMode.Idle
                        } else {
                            sheet.mode
                        },
                    )
                }
            if (leavingMulti) {
                scheduleOrgPins()
            }
            return
        }
        searchJob = viewModelScope.launch {
            delay(MapDefaults.SEARCH_DEBOUNCE_MS)
            val request = ++searchGeneration
            _state.value = _state.value.withSearch { it.copy(loading = true, error = null) }
            when (val result = search.search(text, cameraLat, cameraLon)) {
                is SearchResult.Ok -> {
                    if (request != searchGeneration) return@launch
                    val empty = result.body.hits.isEmpty()
                    val eligible = SearchLogic.isMultiEligible(result.body.hits)
                    _state.value = _state.value.withSearch {
                        it.copy(
                            hits = result.body.hits,
                            loading = false,
                            error = if (empty) SearchUiError.Empty else null,
                        )
                    }
                    if (eligible && _state.value.overlay.pinMode != MapPinMode.SearchSingle) {
                        enterSearchMulti(result.body.hits)
                    } else if (!eligible && _state.value.overlay.pinMode == MapPinMode.SearchMulti) {
                        _state.value = _state.value
                            .withOverlay { it.copy(pinMode = MapPinMode.Browse, searchPins = emptyList()) }
                            .withSheet { sheet ->
                                sheet.copy(
                                    mode = if (sheet.mode == SheetMode.SearchList) SheetMode.Idle else sheet.mode,
                                )
                            }
                            .copy(bounds = null)
                        scheduleOrgPins()
                    }
                }
                is SearchResult.Unavailable -> {
                    if (request != searchGeneration) return@launch
                    _state.value = _state.value.withSearch {
                        it.copy(hits = emptyList(), loading = false, error = SearchUiError.Unavailable)
                    }
                }
                is SearchResult.Network -> {
                    if (request != searchGeneration) return@launch
                    _state.value = _state.value.withSearch {
                        it.copy(hits = emptyList(), loading = false, error = SearchUiError.Network)
                    }
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
            _state.value
                .withSearch {
                    it.copy(
                        query = "",
                        hits = emptyList(),
                        loading = false,
                        error = null,
                        focused = true,
                    )
                }
                .copy(camera = null, bounds = null, generation = generation),
        ).withSearch { it.copy(focused = true) }
        scheduleOrgPins()
    }

    fun onSelectHit(hit: SearchHit) {
        searchJob?.cancel()
        searchGeneration += 1
        val request = ++generation
        pickJob?.cancel()
        val zoom = SearchLogic.flyZoom(cameraZoom)
        val queryToStore = _state.value.search.query.ifBlank { hit.label }
        pickJob = viewModelScope.launch {
            _state.value = _state.value
                .withSearch {
                    it.copy(
                        query = hit.label,
                        focused = false,
                        loading = false,
                        error = null,
                        selectedHit = hit,
                    )
                }
                .withOverlay {
                    it.copy(
                        pinMode = MapPinMode.SearchSingle,
                        marker = LonLat(hit.lon, hit.lat),
                        searchPins = emptyList(),
                    )
                }
                .withSheet { it.copy(mode = SheetMode.Loading) }
                .copy(
                    camera = CameraTarget(
                        lat = hit.lat,
                        lon = hit.lon,
                        zoom = zoom,
                        durationMs = MapDefaults.FLY_DURATION_MS,
                        nonce = request,
                        anchorYFromBottom = MapDefaults.SEARCH_FLYTO_ANCHOR_Y,
                    ),
                    bounds = null,
                    message = null,
                    haptic = false,
                    generation = request,
                )
            history.save(queryToStore, hit.id, hit.label, hit.kind.name, hit.category_slug)
            val rows = history.recent()
            if (request != generation) return@launch
            _state.value = _state.value.withSearch { it.copy(history = rows) }
            openHit(hit, request)
        }
    }

    fun onHistoryQuery(query: String) {
        onQueryChange(query)
        _state.value = _state.value.withSearch { it.copy(focused = true) }
    }

    fun onSearchMarkerClick() {
        val hit = _state.value.search.selectedHit ?: return
        if (_state.value.dropdownOpen) {
            return
        }
        val sheet = _state.value.sheet
        when {
            sheet.building != null -> {
                _state.value = _state.value
                    .withSheet {
                        it.copy(
                            mode = if (it.org != null) SheetMode.Organization else SheetMode.Building,
                        )
                    }
                    .copy(message = null)
            }
            sheet.org != null -> {
                _state.value = _state.value.withSheet { it.copy(mode = SheetMode.Organization) }.copy(message = null)
            }
            sheet.peek != null -> {
                _state.value = _state.value.withSheet { it.copy(mode = SheetMode.Peek) }.copy(message = null)
            }
            else -> onSelectHit(hit)
        }
    }

    fun onSearchPinClick(id: String?) {
        if (id.isNullOrBlank() || _state.value.dropdownOpen) {
            return
        }
        val hit = _state.value.search.hits.firstOrNull { it.id == id } ?: return
        onSelectHit(hit)
    }

    fun onShowAllOnMap() {
        val hits = _state.value.search.hits
        if (hits.isEmpty()) {
            return
        }
        enterSearchMulti(hits)
    }

    fun onOrgPinClick(id: String?) {
        if (id.isNullOrBlank() || _state.value.dropdownOpen) {
            return
        }
        val current = _state.value.sheet.building
        if (current != null && current.organizations.none { it.id == id }) {
            _state.value = _state.value.withOverlay { it.copy(highlight = null) }
        }
        onOrgSelected(id)
    }

    fun onMapClick(lon: Double, lat: Double) {
        if (_state.value.route.pickField != null) {
            onMapPicked(_state.value.route.pickField!!, lon, lat)
            return
        }
        if (_state.value.dropdownOpen) {
            _state.value = _state.value
                .withSearch { it.copy(focused = false) }
                .withRoute { it.copy(fieldFocus = null) }
            return
        }
        if (_state.value.route.mode == MapRouteMode.Result ||
            _state.value.route.mode == MapRouteMode.Planning
        ) {
            return
        }
        val request = ++generation
        pickJob?.cancel()
        pickJob = viewModelScope.launch {
            _state.value = _state.value
                .withSheet { it.copy(mode = SheetMode.Loading, org = null, peek = null) }
                .withOverlay {
                    it.copy(
                        pinMode = MapPinMode.Browse,
                        marker = null,
                        searchPins = emptyList(),
                    )
                }
                .withSearch { it.copy(selectedHit = null, focused = false) }
                .copy(
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
                            _state.value = _state.value
                                .withSheet {
                                    it.copy(
                                        mode = SheetMode.Building,
                                        building = detail.body,
                                        org = null,
                                        peek = null,
                                    )
                                }
                                .withOverlay {
                                    it.copy(
                                        highlight = detail.body.geometry,
                                        marker = null,
                                    )
                                }
                                .withSearch { it.copy(selectedHit = null) }
                                .copy(generation = request)
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
        val previousBuilding = _state.value.sheet.building
        pickJob?.cancel()
        pickJob = viewModelScope.launch {
            _state.value = _state.value
                .withSheet {
                    it.copy(
                        mode = if (previousBuilding != null) SheetMode.Building else SheetMode.Loading,
                    )
                }
                .copy(message = null, haptic = false, generation = request)
            when (val result = orgs.byId(id)) {
                is OrgDetailResult.Found -> {
                    if (request != generation) return@launch
                    val org = result.body
                    val building = resolveOrgBuilding(org.building_id, previousBuilding)
                    if (request != generation) return@launch
                    _state.value = _state.value
                        .withSheet {
                            it.copy(
                                mode = SheetMode.Organization,
                                org = org,
                                building = building,
                                peek = null,
                            )
                        }
                        .withOverlay { it.copy(highlight = building?.geometry) }
                        .copy(message = null, haptic = false, generation = request)
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
        if (_state.value.route.error != null) {
            _state.value = _state.value.withRoute { it.copy(error = null) }
        }
    }

    fun refreshGpsAvailability() {
        _state.value = _state.value.withRoute { it.copy(gpsEnabled = location.isAvailable()) }
    }

    fun onSelectTab(tab: BottomTab) {
        if (_state.value.route.bottomTab == tab) {
            return
        }
        if (tab == BottomTab.Search) {
            _state.value = _state.value.withRoute {
                it.copy(
                    bottomTab = BottomTab.Search,
                    pickField = null,
                    fieldFocus = null,
                    searchField = null,
                    hits = emptyList(),
                )
            }
            if (_state.value.route.mode == MapRouteMode.Idle) {
                scheduleOrgPins()
            }
            return
        }
        enterRouteTab()
    }

    fun onBuildCta() {
        onBuildRoute()
    }

    fun onBuildRoute() {
        val from = _state.value.route.from
        val to = _state.value.route.to
        if (from == null || to == null) {
            return
        }
        if (!network.isOnline()) {
            _state.value = _state.value.withRoute { it.copy(loading = false, error = RouteUiError.Offline) }
            return
        }
        routeJob?.cancel()
        val request = ++routeGeneration
        routeJob = viewModelScope.launch {
            _state.value = _state.value.withRoute {
                it.copy(
                    bottomTab = BottomTab.Route,
                    mode = MapRouteMode.Planning,
                    loading = true,
                    error = null,
                    pickField = null,
                    fieldFocus = null,
                    searchField = null,
                )
            }
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

    fun onSwapRoute() {
        val from = _state.value.route.from
        val to = _state.value.route.to
        val fromQuery = _state.value.route.fromQuery
        val toQuery = _state.value.route.toQuery
        _state.value = _state.value.withRoute {
            it.copy(
                from = to,
                to = from,
                fromQuery = toQuery,
                toQuery = fromQuery,
            )
        }
        if (_state.value.route.mode == MapRouteMode.Result && to != null && from != null) {
            onBuildRoute()
        }
    }

    fun onClearRoute() {
        routeJob?.cancel()
        routeSearchJob?.cancel()
        routeGeneration += 1
        _state.value = _state.value
            .withRoute {
                it.copy(
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
            }
            .copy(bounds = null)
        scheduleOrgPins()
    }

    fun onSelectItinerary(index: Int) {
        val items = _state.value.route.itineraries
        if (index !in items.indices) {
            return
        }
        applyActiveItinerary(items, index, ++generation)
    }

    fun onRouteFieldFocus(field: RouteField?) {
        _state.value = _state.value
            .withRoute {
                it.copy(
                    fieldFocus = field,
                    searchField = field ?: it.searchField,
                )
            }
            .withSearch { it.copy(focused = false) }
    }

    fun onRouteQueryChange(field: RouteField, text: String) {
        routeSearchJob?.cancel()
        _state.value = _state.value.withRoute {
            when (field) {
                RouteField.From -> it.copy(
                    fromQuery = text,
                    error = null,
                    searchField = field,
                    pickField = null,
                )
                RouteField.To -> it.copy(
                    toQuery = text,
                    error = null,
                    searchField = field,
                    pickField = null,
                )
            }
        }
        if (!SearchLogic.shouldRequest(text)) {
            routeSearchGeneration += 1
            _state.value = _state.value.withRoute { it.copy(hits = emptyList(), searchLoading = false) }
            return
        }
        routeSearchJob = viewModelScope.launch {
            delay(MapDefaults.SEARCH_DEBOUNCE_MS)
            val request = ++routeSearchGeneration
            _state.value = _state.value.withRoute { it.copy(searchLoading = true) }
            when (val result = search.search(text, cameraLat, cameraLon)) {
                is SearchResult.Ok -> {
                    if (request != routeSearchGeneration) return@launch
                    _state.value = _state.value.withRoute {
                        it.copy(hits = result.body.hits, searchLoading = false)
                    }
                }
                is SearchResult.Unavailable, is SearchResult.Network -> {
                    if (request != routeSearchGeneration) return@launch
                    _state.value = _state.value.withRoute {
                        it.copy(hits = emptyList(), searchLoading = false)
                    }
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
        _state.value = _state.value
            .withRoute {
                it.copy(
                    pickField = field,
                    searchField = field,
                    fieldFocus = null,
                    hits = emptyList(),
                )
            }
            .withSearch { it.copy(focused = false) }
    }

    fun onMapPicked(field: RouteField, lon: Double, lat: Double, label: String = mapPickLabel(lon, lat)) {
        assignRoutePoint(field, RoutePoint(lon, lat, label))
    }

    fun onMapLongClick(lon: Double, lat: Double) {
        if (_state.value.route.bottomTab != BottomTab.Route && _state.value.route.mode == MapRouteMode.Idle) {
            return
        }
        onMapPicked(activeRouteFieldForMap(), lon, lat)
    }

    fun onFillMyLocation() {
        refreshGpsAvailability()
        val loc = location.lastKnown() ?: return
        assignRoutePoint(RouteField.From, RoutePoint(loc.lon, loc.lat, MY_LOCATION_LABEL))
    }

    private fun enterRouteTab() {
        val dest = selectedDestination()
        val from = _state.value.route.from
        _state.value = _state.value
            .withRoute {
                it.copy(
                    bottomTab = BottomTab.Route,
                    mode = if (it.mode == MapRouteMode.Result) MapRouteMode.Result else MapRouteMode.Planning,
                    to = it.to ?: dest,
                    toQuery = (it.to ?: dest)?.label ?: it.toQuery,
                    from = from,
                    fromQuery = from?.label ?: it.fromQuery,
                    fieldFocus = null,
                    searchField = null,
                    hits = emptyList(),
                )
            }
            .withSearch { it.copy(focused = false) }
    }

    private fun selectedDestination(): RoutePoint? {
        val org = _state.value.sheet.org
        if (org != null) {
            val label = org.address?.label?.takeIf { it.isNotBlank() } ?: org.name
            return RoutePoint(org.location.lon, org.location.lat, label)
        }
        val building = _state.value.sheet.building
        if (building != null) {
            val label = building.addresses.firstOrNull()?.label
                ?: building.name
                ?: building.id
            return RoutePoint(building.centroid.lon, building.centroid.lat, label)
        }
        val hit = _state.value.search.selectedHit ?: _state.value.sheet.peek?.hit
        if (hit != null) {
            return RoutePoint(hit.lon, hit.lat, hit.label)
        }
        return null
    }

    private fun activeRouteFieldForSearch(): RouteField {
        return _state.value.route.searchField
            ?: _state.value.route.fieldFocus
            ?: RouteField.To
    }

    private fun activeRouteFieldForMap(): RouteField {
        return _state.value.route.pickField
            ?: _state.value.route.fieldFocus
            ?: _state.value.route.searchField
            ?: RouteField.To
    }

    private fun assignRoutePoint(field: RouteField, point: RoutePoint) {
        val wasResult = _state.value.route.mode == MapRouteMode.Result
        _state.value = _state.value.withRoute {
            val nextMode = when {
                wasResult -> MapRouteMode.Planning
                it.mode == MapRouteMode.Idle -> MapRouteMode.Planning
                else -> it.mode
            }
            when (field) {
                RouteField.From -> it.copy(
                    from = point,
                    fromQuery = point.label,
                    pickField = null,
                    fieldFocus = null,
                    searchField = null,
                    hits = emptyList(),
                    mode = nextMode,
                    bottomTab = BottomTab.Route,
                )
                RouteField.To -> it.copy(
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
        }
        if (wasResult) {
            clearRouteLayersOnly()
        }
    }

    private fun clearRouteLayersOnly() {
        routeJob?.cancel()
        routeGeneration += 1
        _state.value = _state.value
            .withRoute {
                it.copy(
                    itineraries = emptyList(),
                    activeItineraryIndex = 0,
                    loading = false,
                )
            }
            .copy(bounds = null)
    }

    private fun failRoute(request: Int, error: RouteUiError) {
        if (request != routeGeneration) {
            return
        }
        _state.value = _state.value.withRoute {
            it.copy(loading = false, error = error, mode = MapRouteMode.Planning)
        }
    }

    private fun applyRouteResult(body: rs.zylos.novisad.data.api.RouteResponse, request: Int) {
        val sorted = RouteLogic.sortItineraries(RouteLogic.itinerariesOf(body))
        if (sorted.isEmpty()) {
            failRoute(request, RouteUiError.NoRoute)
            return
        }
        _state.value = _state.value.withRoute {
            it.copy(
                mode = MapRouteMode.Result,
                loading = false,
                error = null,
                itineraries = sorted,
                bottomTab = BottomTab.Route,
            )
        }
        applyActiveItinerary(sorted, 0, request)
    }

    private fun applyActiveItinerary(
        items: List<rs.zylos.novisad.data.api.RouteItinerary>,
        index: Int,
        nonce: Int,
    ) {
        val itinerary = items.getOrNull(index) ?: return
        val from = _state.value.route.from
        val to = _state.value.route.to
        _state.value = _state.value
            .withRoute { it.copy(activeItineraryIndex = index) }
            .copy(
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
        _state.value = _state.value
            .withOverlay {
                it.copy(
                    pinMode = MapPinMode.SearchMulti,
                    marker = null,
                    searchPins = pins,
                    highlight = null,
                )
            }
            .withSearch { it.copy(focused = false, loading = false, selectedHit = null) }
            .withSheet {
                it.copy(
                    mode = SheetMode.SearchList,
                    peek = null,
                    org = null,
                    building = null,
                )
            }
            .copy(
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
        if (_state.value.overlay.pinMode != MapPinMode.Browse || _state.value.route.mode != MapRouteMode.Idle) {
            return
        }
        if (!OrgPinLimits.shouldRequest(cameraZoom)) {
            orgPinsGeneration += 1
            _state.value = _state.value.withOverlay { it.copy(orgPins = emptyList()) }
            return
        }
        val bbox = cameraBbox ?: return
        val zoom = cameraZoom
        orgPinsJob = viewModelScope.launch {
            delay(OrgPinLimits.debounceMs(zoom))
            if (_state.value.overlay.pinMode != MapPinMode.Browse) return@launch
            if (_state.value.route.mode != MapRouteMode.Idle) return@launch
            val latest = cameraBbox ?: bbox
            if (!OrgPinLimits.shouldRequest(cameraZoom)) {
                _state.value = _state.value.withOverlay { it.copy(orgPins = emptyList()) }
                return@launch
            }
            val request = ++orgPinsGeneration
            val limit = OrgPinLimits.limit(cameraZoom)
            when (val result = orgs.inBbox(latest.minLon, latest.minLat, latest.maxLon, latest.maxLat, limit)) {
                is OrgBboxResult.Ok -> {
                    if (request != orgPinsGeneration) return@launch
                    if (_state.value.overlay.pinMode != MapPinMode.Browse) return@launch
                    if (_state.value.route.mode != MapRouteMode.Idle) return@launch
                    val visible = OrgPinLogic.visiblePins(result.pins, cameraZoom, latest)
                    _state.value = _state.value.withOverlay { it.copy(orgPins = visible) }
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
                _state.value = _state.value
                    .withSheet {
                        it.copy(
                            mode = SheetMode.Organization,
                            org = orgDetail,
                            building = buildingDetail,
                            peek = null,
                        )
                    }
                    .withOverlay { it.copy(highlight = buildingDetail?.geometry) }
                    .copy(generation = request)
            }
            buildingDetail != null -> {
                _state.value = _state.value
                    .withSheet {
                        it.copy(
                            mode = SheetMode.Building,
                            building = buildingDetail,
                            org = null,
                            peek = null,
                        )
                    }
                    .withOverlay { it.copy(highlight = buildingDetail.geometry) }
                    .copy(generation = request)
            }
            else -> showPeek(hit, request)
        }
    }

    private fun showPeek(hit: SearchHit, request: Int) {
        if (request != generation) return
        _state.value = _state.value
            .withSheet {
                it.copy(
                    mode = SheetMode.Peek,
                    building = if (hit.building_id == null) null else it.building,
                    org = null,
                    peek = PeekInfo(
                        title = hit.label,
                        subtitle = SearchLogic.peekSubtitle(hit, ADDRESS_FALLBACK, ORG_FALLBACK),
                        hit = hit,
                    ),
                )
            }
            .withOverlay {
                it.copy(highlight = if (hit.building_id == null) null else it.highlight)
            }
            .copy(generation = request)
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

        private object IdleLocation : LocationProvider {
            override fun isAvailable() = false
            override fun lastKnown() = null
        }

        fun factory(
            api: ZylosApi,
            historyDao: SearchHistoryDao,
            routeApi: ZylosApi = api,
            network: NetworkMonitor,
            location: LocationProvider,
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MapViewModel(
                        HttpBuildingRepository(api),
                        HttpOrgRepository(api),
                        HttpSearchRepository(api),
                        RoomSearchHistoryStore(historyDao),
                        HttpRouteRepository(routeApi),
                        network,
                        location,
                    ) as T
                }
            }
        }
    }
}
