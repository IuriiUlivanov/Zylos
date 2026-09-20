package rs.zylos.app.viewmodel

import rs.zylos.app.data.api.BuildingGeometry
import rs.zylos.app.data.api.LonLat
import rs.zylos.app.data.api.SearchHit

fun interface MapEventSink {
    fun emit(event: MapEvent)
}

sealed interface MapEvent {
    data object Snapshot : MapEvent

    data class HitSelected(val hit: SearchHit) : MapEvent
    data object SearchCleared : MapEvent
    data class SearchMultiRequested(val hits: List<SearchHit>) : MapEvent
    data object SearchMultiCancelled : MapEvent
    data object SelectedHitCleared : MapEvent
    data object BlurSearch : MapEvent

    data class HighlightChanged(val geometry: BuildingGeometry?) : MapEvent
    data class MarkerChanged(val point: LonLat?) : MapEvent
    data object SheetClosed : MapEvent
    data class UserFeedback(val message: UserMessage?, val haptic: Boolean) : MapEvent

    data object RouteCleared : MapEvent
    data class RouteModeChanged(val mode: MapRouteMode) : MapEvent

    data class FlyTo(val target: CameraTarget) : MapEvent
    data class FitBounds(val target: BoundsTarget) : MapEvent
    data object ClearCamera : MapEvent
    data object ClearBounds : MapEvent
}
