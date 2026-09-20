package rs.zylos.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rs.zylos.app.data.api.LonLat
import rs.zylos.app.data.api.SearchHit
import rs.zylos.app.data.api.ZylosApi
import rs.zylos.app.data.local.RoomSearchHistoryStore
import rs.zylos.app.data.local.SearchHistoryDao
import rs.zylos.app.data.local.SearchHistoryStore
import rs.zylos.app.data.repository.BuildingRepository
import rs.zylos.app.data.repository.HttpBuildingRepository
import rs.zylos.app.data.repository.HttpOrgRepository
import rs.zylos.app.data.repository.HttpRouteRepository
import rs.zylos.app.data.repository.HttpSearchRepository
import rs.zylos.app.data.repository.OrgRepository
import rs.zylos.app.data.repository.RouteRepository
import rs.zylos.app.data.repository.RouteResult
import rs.zylos.app.data.repository.SearchRepository
import rs.zylos.app.data.repository.SearchResult
import rs.zylos.app.feature.pins.PinsCoordinator
import rs.zylos.app.feature.route.RouteCoordinator
import rs.zylos.app.feature.search.SearchCoordinator
import rs.zylos.app.feature.sheet.SheetCoordinator
import rs.zylos.app.map.MapDefaults
import rs.zylos.app.platform.LocationProvider
import rs.zylos.app.platform.NetworkMonitor

class MapViewModel(
    buildings: BuildingRepository,
    orgs: OrgRepository,
    search: SearchRepository = IdleSearch,
    history: SearchHistoryStore = IdleHistory,
    routes: RouteRepository = IdleRoutes,
    network: NetworkMonitor = NetworkMonitor { true },
    location: LocationProvider = IdleLocation,
) : ViewModel() {
    private val cameraTracker = CameraTracker()
    private var generation = 0
    private var cameraTarget: CameraTarget? = null
    private var boundsTarget: BoundsTarget? = null
    private var message: UserMessage? = null
    private var haptic = false

    private lateinit var searchFeature: SearchCoordinator
    private lateinit var sheetFeature: SheetCoordinator
    private lateinit var routeFeature: RouteCoordinator
    private lateinit var pinsFeature: PinsCoordinator

    private val events = MapEventSink { event ->
        handle(event)
        publish()
    }

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    init {
        searchFeature = SearchCoordinator(viewModelScope, search, history, cameraTracker, events)
        sheetFeature = SheetCoordinator(viewModelScope, buildings, orgs, events)
        routeFeature = RouteCoordinator(
            viewModelScope,
            routes,
            search,
            network,
            location,
            cameraTracker,
            events,
        )
        pinsFeature = PinsCoordinator(viewModelScope, orgs, cameraTracker, events)
        routeFeature.refreshGps()
        publish()
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
        cameraTracker.update(lat, lon, zoom, minLon, minLat, maxLon, maxLat)
        pinsFeature.schedule()
        publish()
    }

    fun onSearchFocusChanged(focused: Boolean) {
        searchFeature.setFocused(focused)
        publish()
    }

    fun onQueryChange(text: String) {
        message = null
        haptic = false
        searchFeature.onQueryChange(text)
        publish()
    }

    fun onClearSearch() {
        searchFeature.onClearSearch()
        publish()
    }

    fun onSelectHit(hit: SearchHit) {
        searchFeature.onSelectHit(hit)
        publish()
    }

    fun onHistoryQuery(query: String) {
        searchFeature.onHistoryQuery(query)
        publish()
    }

    fun onSearchMarkerClick() {
        if (_state.value.dropdownOpen) {
            return
        }
        if (!sheetFeature.restoreFromMarker()) {
            val hit = searchFeature.state.value.selectedHit ?: return
            searchFeature.onSelectHit(hit)
        }
        publish()
    }

    fun onSearchPinClick(id: String?) {
        if (id.isNullOrBlank() || _state.value.dropdownOpen) {
            return
        }
        val hit = searchFeature.hitById(id) ?: return
        searchFeature.onSelectHit(hit)
        publish()
    }

    fun onShowAllOnMap() {
        searchFeature.onShowAllOnMap()
        publish()
    }

    fun onOrgPinClick(id: String?) {
        if (id.isNullOrBlank() || _state.value.dropdownOpen) {
            return
        }
        val current = sheetFeature.state.value.building
        if (current != null && current.organizations.none { it.id == id }) {
            pinsFeature.clearHighlight()
            publish()
        }
        sheetFeature.onOrgSelected(id)
        publish()
    }

    fun onMapClick(lon: Double, lat: Double) {
        if (routeFeature.handleMapTap(lon, lat)) {
            publish()
            return
        }
        if (_state.value.dropdownOpen) {
            searchFeature.setFocused(false)
            routeFeature.clearFocus()
            publish()
            return
        }
        if (routeFeature.state.value.bottomTab == BottomTab.Route) {
            return
        }
        searchFeature.clearSelected()
        searchFeature.setFocused(false)
        pinsFeature.prepareMapPick()
        boundsTarget = null
        message = null
        haptic = false
        sheetFeature.onMapClick(lon, lat)
        publish()
    }

    fun onOrgSelected(id: String) {
        sheetFeature.onOrgSelected(id)
        publish()
    }

    fun onBackToBuilding() {
        sheetFeature.onBackToBuilding()
        publish()
    }

    fun onSheetClosed() {
        sheetFeature.onClosed()
        publish()
    }

    fun consumeMessage() {
        if (message != null) {
            message = null
            haptic = false
            publish()
        }
    }

    fun consumeRouteError() {
        routeFeature.consumeError()
        publish()
    }

    fun refreshGpsAvailability() {
        routeFeature.refreshGps()
        publish()
    }

    fun onSelectTab(tab: BottomTab) {
        if (routeFeature.state.value.bottomTab == tab) {
            return
        }
        if (tab == BottomTab.Search) {
            routeFeature.showSearchTab()
            if (routeFeature.state.value.mode == MapRouteMode.Idle) {
                pinsFeature.schedule()
            }
            publish()
            return
        }
        routeFeature.enter(selectedDestination())
        publish()
    }

    fun onBuildCta() {
        onBuildRoute()
    }

    fun onBuildRoute() {
        routeFeature.onBuildRoute()
        publish()
    }

    fun onSwapRoute() {
        routeFeature.onSwapRoute()
        publish()
    }

    fun onClearRoute() {
        routeFeature.onClearRoute()
        publish()
    }

    fun onSelectItinerary(index: Int) {
        routeFeature.onSelectItinerary(index)
        publish()
    }

    fun onRouteFieldFocus(field: RouteField?) {
        routeFeature.onFieldFocus(field)
        publish()
    }

    fun onRouteQueryChange(field: RouteField, text: String) {
        routeFeature.onQueryChange(field, text)
        publish()
    }

    fun onSelectRouteHit(hit: SearchHit) {
        routeFeature.onSelectHit(hit)
        publish()
    }

    fun onNaKartu(field: RouteField) {
        routeFeature.onNaKartu(field)
        publish()
    }

    fun onMapPicked(field: RouteField, lon: Double, lat: Double, label: String = String.format("%.5f, %.5f", lat, lon)) {
        routeFeature.onMapPicked(field, lon, lat, label)
        publish()
    }

    fun onMapLongClick(lon: Double, lat: Double) {
        routeFeature.onMapLongClick(lon, lat)
        publish()
    }

    fun onFillMyLocation() {
        routeFeature.onFillMyLocation()
        publish()
    }

    fun consumeCamera() {
        if (cameraTarget != null) {
            cameraTarget = null
            publish()
        }
    }

    fun consumeBounds() {
        if (boundsTarget != null) {
            boundsTarget = null
            publish()
        }
    }

    override fun onCleared() {
        searchFeature.onCleared()
        sheetFeature.onCleared()
        routeFeature.onCleared()
        pinsFeature.onCleared()
        super.onCleared()
    }

    private fun handle(event: MapEvent) {
        when (event) {
            MapEvent.Snapshot -> Unit
            is MapEvent.HitSelected -> onHitSelected(event.hit)
            MapEvent.SearchCleared -> onSearchCleared()
            is MapEvent.SearchMultiRequested -> onSearchMulti(event.hits)
            MapEvent.SearchMultiCancelled -> onSearchMultiCancelled()
            MapEvent.SelectedHitCleared -> searchFeature.clearSelected()
            MapEvent.BlurSearch -> searchFeature.setFocused(false)
            is MapEvent.HighlightChanged -> pinsFeature.setHighlight(event.geometry)
            is MapEvent.MarkerChanged -> pinsFeature.setMarker(event.point)
            MapEvent.SheetClosed -> onSheetClosedEvent()
            is MapEvent.UserFeedback -> {
                message = event.message
                haptic = event.haptic
            }
            MapEvent.RouteCleared -> {
                pinsFeature.setRouteMode(MapRouteMode.Idle)
                pinsFeature.schedule()
            }
            is MapEvent.RouteModeChanged -> pinsFeature.setRouteMode(event.mode)
            is MapEvent.FlyTo -> cameraTarget = event.target
            is MapEvent.FitBounds -> boundsTarget = event.target
            MapEvent.ClearCamera -> cameraTarget = null
            MapEvent.ClearBounds -> boundsTarget = null
        }
    }

    private fun onHitSelected(hit: SearchHit) {
        pinsFeature.enterSearchSingle(hit)
        cameraTarget = CameraTarget(
            lat = hit.lat,
            lon = hit.lon,
            zoom = SearchLogic.flyZoom(cameraTracker.zoom),
            durationMs = MapDefaults.FLY_DURATION_MS,
            nonce = nextNonce(),
            anchorYFromBottom = MapDefaults.SEARCH_FLYTO_ANCHOR_Y,
        )
        boundsTarget = null
        message = null
        haptic = false
        sheetFeature.openHit(hit)
    }

    private fun onSearchCleared() {
        sheetFeature.reset()
        pinsFeature.resetBrowse()
        cameraTarget = null
        boundsTarget = null
        message = null
        haptic = false
        pinsFeature.schedule()
    }

    private fun onSearchMulti(hits: List<SearchHit>) {
        if (!pinsFeature.enterSearchMulti(hits)) {
            return
        }
        sheetFeature.showSearchList()
        searchFeature.setFocused(false)
        cameraTarget = null
        boundsTarget = BoundsTarget(
            points = pinsFeature.state.value.searchPins.map { LonLat(it.lon, it.lat) },
            durationMs = MapDefaults.FLY_DURATION_MS,
            nonce = nextNonce(),
        )
    }

    private fun onSearchMultiCancelled() {
        pinsFeature.leaveSearchMulti()
        sheetFeature.leaveSearchList()
        boundsTarget = null
        pinsFeature.schedule()
    }

    private fun onSheetClosedEvent() {
        pinsFeature.resetBrowse()
        searchFeature.clearSelected()
        boundsTarget = null
        message = null
        haptic = false
        pinsFeature.schedule()
    }

    private fun selectedDestination(): RoutePoint? {
        val org = sheetFeature.state.value.org
        if (org != null) {
            val label = org.address?.label?.takeIf { it.isNotBlank() } ?: org.name
            return RoutePoint(org.location.lon, org.location.lat, label)
        }
        val building = sheetFeature.state.value.building
        if (building != null) {
            val label = building.addresses.firstOrNull()?.label
                ?: building.name
                ?: building.id
            return RoutePoint(building.centroid.lon, building.centroid.lat, label)
        }
        val hit = searchFeature.state.value.selectedHit ?: sheetFeature.state.value.peek?.hit
        if (hit != null) {
            return RoutePoint(hit.lon, hit.lat, hit.label)
        }
        return null
    }

    private fun nextNonce(): Int {
        generation += 1
        return generation
    }

    private fun publish() {
        _state.value = MapUiState(
            search = searchFeature.state.value,
            sheet = sheetFeature.state.value,
            route = routeFeature.state.value,
            overlay = pinsFeature.state.value,
            camera = cameraTarget,
            bounds = boundsTarget,
            message = message,
            haptic = haptic,
            generation = generation,
        )
    }

    companion object {
        private object IdleSearch : SearchRepository {
            override suspend fun search(q: String, lat: Double, lon: Double, limit: Int) =
                SearchResult.Ok(rs.zylos.app.data.api.SearchResponse(q, emptyList(), 0))
        }

        private object IdleHistory : SearchHistoryStore {
            override suspend fun recent() = emptyList<rs.zylos.app.data.local.SearchHistoryEntity>()
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
