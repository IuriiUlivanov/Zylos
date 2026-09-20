package rs.zylos.app.viewmodel

import rs.zylos.app.data.api.BuildingDetailResponse
import rs.zylos.app.data.api.BuildingGeometry
import rs.zylos.app.data.api.LonLat
import rs.zylos.app.data.api.OrgDetailResponse
import rs.zylos.app.data.api.RouteItinerary
import rs.zylos.app.data.api.SearchHit
import rs.zylos.app.data.local.SearchHistoryEntity
import rs.zylos.app.map.MapDefaults
import rs.zylos.app.map.RankedOrgPin
import rs.zylos.app.ui.CategoryLabels

enum class SheetMode {
    Idle,
    Loading,
    Building,
    Organization,
    Peek,
    SearchList,
}

enum class MapPinMode {
    Browse,
    SearchSingle,
    SearchMulti,
}

enum class UserMessage {
    NotFound,
    OutsideCity,
    Network,
}

enum class SearchUiError {
    Empty,
    Unavailable,
    Network,
}

data class PeekInfo(
    val title: String,
    val subtitle: String,
    val hit: SearchHit? = null,
)

data class CameraTarget(
    val lat: Double,
    val lon: Double,
    val zoom: Double,
    val durationMs: Int,
    val nonce: Int,
    val anchorYFromBottom: Float = MapDefaults.SEARCH_FLYTO_ANCHOR_Y,
)

data class BoundsTarget(
    val points: List<LonLat>,
    val durationMs: Int,
    val nonce: Int,
    val topHalf: Boolean = false,
)

data class SearchUiState(
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val history: List<SearchHistoryEntity> = emptyList(),
    val loading: Boolean = false,
    val error: SearchUiError? = null,
    val focused: Boolean = false,
    val selectedHit: SearchHit? = null,
)

data class SheetUiState(
    val mode: SheetMode = SheetMode.Idle,
    val building: BuildingDetailResponse? = null,
    val org: OrgDetailResponse? = null,
    val peek: PeekInfo? = null,
)

data class RouteUiState(
    val bottomTab: BottomTab = BottomTab.Search,
    val mode: MapRouteMode = MapRouteMode.Idle,
    val from: RoutePoint? = null,
    val to: RoutePoint? = null,
    val pickField: RouteField? = null,
    val searchField: RouteField? = null,
    val fieldFocus: RouteField? = null,
    val loading: Boolean = false,
    val error: RouteUiError? = null,
    val itineraries: List<RouteItinerary> = emptyList(),
    val activeItineraryIndex: Int = 0,
    val fromQuery: String = "",
    val toQuery: String = "",
    val hits: List<SearchHit> = emptyList(),
    val searchLoading: Boolean = false,
    val gpsEnabled: Boolean = false,
) {
    val inputField: RouteField?
        get() = fieldFocus ?: searchField

    val canBuild: Boolean
        get() = RouteLogic.bothPointsReady(from, to)

    val activeItinerary: RouteItinerary?
        get() = itineraries.getOrNull(activeItineraryIndex)
}

data class OverlayUiState(
    val pinMode: MapPinMode = MapPinMode.Browse,
    val highlight: BuildingGeometry? = null,
    val marker: LonLat? = null,
    val orgPins: List<RankedOrgPin> = emptyList(),
    val searchPins: List<SearchHit> = emptyList(),
)

data class MapUiState(
    val search: SearchUiState = SearchUiState(),
    val sheet: SheetUiState = SheetUiState(),
    val route: RouteUiState = RouteUiState(),
    val overlay: OverlayUiState = OverlayUiState(),
    val camera: CameraTarget? = null,
    val bounds: BoundsTarget? = null,
    val message: UserMessage? = null,
    val haptic: Boolean = false,
    val generation: Int = 0,
) {
    val dropdownOpen: Boolean
        get() = when {
            route.bottomTab == BottomTab.Route && route.inputField != null -> {
                val q = if (route.inputField == RouteField.From) route.fromQuery else route.toQuery
                q.length >= MapDefaults.SEARCH_MIN_LENGTH
            }
            else -> search.focused && (
                (search.query.isEmpty() && search.history.isNotEmpty()) ||
                    search.query.length >= MapDefaults.SEARCH_MIN_LENGTH
                )
        }

    fun withSearch(block: (SearchUiState) -> SearchUiState) = copy(search = block(search))

    fun withSheet(block: (SheetUiState) -> SheetUiState) = copy(sheet = block(sheet))

    fun withRoute(block: (RouteUiState) -> RouteUiState) = copy(route = block(route))

    fun withOverlay(block: (OverlayUiState) -> OverlayUiState) = copy(overlay = block(overlay))
}

object SheetLogic {
    fun title(building: BuildingDetailResponse?, fallback: String): String {
        return building?.addresses?.firstOrNull()?.label
            ?: building?.name
            ?: fallback
    }

    fun orgSubtitle(org: OrgDetailResponse): String {
        return CategoryLabels.label(org.category_slug, org.category_name) ?: ""
    }

    fun buildingSubtitleCount(building: BuildingDetailResponse?): Int {
        return building?.organizations?.size ?: 0
    }

    fun reduceClose(state: MapUiState): MapUiState {
        return state.copy(
            sheet = SheetUiState(),
            overlay = state.overlay.copy(
                pinMode = MapPinMode.Browse,
                highlight = null,
                marker = null,
                searchPins = emptyList(),
            ),
            search = state.search.copy(selectedHit = null),
            bounds = null,
            message = null,
            haptic = false,
        )
    }

    fun reduceBackToBuilding(state: MapUiState): MapUiState {
        if (state.sheet.building == null) {
            return reduceClose(state)
        }
        return state.copy(
            sheet = state.sheet.copy(
                mode = SheetMode.Building,
                org = null,
                peek = null,
            ),
            message = null,
            haptic = false,
        )
    }

    fun reduceNotFound(generation: Int, previous: MapUiState = MapUiState()): MapUiState {
        return previous.copy(
            sheet = SheetUiState(),
            overlay = previous.overlay.copy(highlight = null, marker = null),
            search = previous.search.copy(selectedHit = null),
            message = UserMessage.NotFound,
            haptic = true,
            generation = generation,
        )
    }

    fun reduceOutsideCity(generation: Int, previous: MapUiState = MapUiState()): MapUiState {
        return previous.copy(
            sheet = SheetUiState(),
            overlay = previous.overlay.copy(highlight = null, marker = null),
            search = previous.search.copy(selectedHit = null),
            message = UserMessage.OutsideCity,
            haptic = false,
            generation = generation,
        )
    }

    fun reduceNetwork(generation: Int, previous: MapUiState): MapUiState {
        val mode = when {
            previous.sheet.building != null && previous.sheet.org != null -> SheetMode.Organization
            previous.sheet.building != null -> SheetMode.Building
            previous.sheet.peek != null -> SheetMode.Peek
            else -> SheetMode.Idle
        }
        return previous.copy(
            sheet = previous.sheet.copy(mode = mode),
            message = UserMessage.Network,
            haptic = false,
            generation = generation,
        )
    }
}
