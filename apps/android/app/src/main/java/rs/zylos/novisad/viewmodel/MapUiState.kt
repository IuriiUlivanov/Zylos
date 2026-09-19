package rs.zylos.novisad.viewmodel

import rs.zylos.novisad.data.api.BuildingDetailResponse
import rs.zylos.novisad.data.api.LonLat
import rs.zylos.novisad.data.api.OrgDetailResponse
import rs.zylos.novisad.data.api.SearchHit
import rs.zylos.novisad.data.local.SearchHistoryEntity
import rs.zylos.novisad.map.MapDefaults
import rs.zylos.novisad.ui.CategoryLabels

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
)

data class MapUiState(
    val mode: SheetMode = SheetMode.Idle,
    val pinMode: MapPinMode = MapPinMode.Browse,
    val building: BuildingDetailResponse? = null,
    val org: OrgDetailResponse? = null,
    val peek: PeekInfo? = null,
    val highlightJson: String? = null,
    val markerJson: String? = null,
    val orgPinsJson: String? = null,
    val searchPinsJson: String? = null,
    val camera: CameraTarget? = null,
    val bounds: BoundsTarget? = null,
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val history: List<SearchHistoryEntity> = emptyList(),
    val searchLoading: Boolean = false,
    val searchError: SearchUiError? = null,
    val searchFocused: Boolean = false,
    val selectedHit: SearchHit? = null,
    val message: UserMessage? = null,
    val haptic: Boolean = false,
    val generation: Int = 0,
) {
    val dropdownOpen: Boolean
        get() = searchFocused && (
            (query.isEmpty() && history.isNotEmpty()) ||
                query.length >= MapDefaults.SEARCH_MIN_LENGTH
            )
}

object SheetLogic {
    fun title(state: MapUiState, fallback: String): String {
        val building = state.building
        return building?.addresses?.firstOrNull()?.label
            ?: building?.name
            ?: fallback
    }

    fun orgSubtitle(org: OrgDetailResponse): String {
        return CategoryLabels.label(org.category_slug, org.category_name) ?: ""
    }

    fun buildingSubtitleCount(state: MapUiState): Int {
        return state.building?.organizations?.size ?: 0
    }

    fun reduceClose(state: MapUiState): MapUiState {
        return state.copy(
            mode = SheetMode.Idle,
            pinMode = MapPinMode.Browse,
            building = null,
            org = null,
            peek = null,
            highlightJson = null,
            markerJson = null,
            searchPinsJson = null,
            selectedHit = null,
            bounds = null,
            message = null,
            haptic = false,
        )
    }

    fun reduceBackToBuilding(state: MapUiState): MapUiState {
        if (state.building == null) {
            return reduceClose(state)
        }
        return state.copy(
            mode = SheetMode.Building,
            org = null,
            peek = null,
            message = null,
            haptic = false,
        )
    }

    fun reduceNotFound(generation: Int, previous: MapUiState = MapUiState()): MapUiState {
        return previous.copy(
            mode = SheetMode.Idle,
            building = null,
            org = null,
            peek = null,
            highlightJson = null,
            markerJson = null,
            selectedHit = null,
            message = UserMessage.NotFound,
            haptic = true,
            generation = generation,
        )
    }

    fun reduceOutsideCity(generation: Int, previous: MapUiState = MapUiState()): MapUiState {
        return previous.copy(
            mode = SheetMode.Idle,
            building = null,
            org = null,
            peek = null,
            highlightJson = null,
            markerJson = null,
            selectedHit = null,
            message = UserMessage.OutsideCity,
            haptic = false,
            generation = generation,
        )
    }

    fun reduceNetwork(generation: Int, previous: MapUiState): MapUiState {
        val mode = when {
            previous.building != null && previous.org != null -> SheetMode.Organization
            previous.building != null -> SheetMode.Building
            previous.peek != null -> SheetMode.Peek
            else -> SheetMode.Idle
        }
        return previous.copy(
            mode = mode,
            message = UserMessage.Network,
            haptic = false,
            generation = generation,
        )
    }
}
