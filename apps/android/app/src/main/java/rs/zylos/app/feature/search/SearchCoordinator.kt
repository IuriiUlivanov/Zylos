package rs.zylos.app.feature.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rs.zylos.app.data.api.SearchHit
import rs.zylos.app.data.local.SearchHistoryStore
import rs.zylos.app.data.repository.SearchRepository
import rs.zylos.app.data.repository.SearchResult
import rs.zylos.app.map.MapDefaults
import rs.zylos.app.viewmodel.CameraTracker
import rs.zylos.app.viewmodel.MapEvent
import rs.zylos.app.viewmodel.MapEventSink
import rs.zylos.app.viewmodel.SearchLogic
import rs.zylos.app.viewmodel.SearchUiError
import rs.zylos.app.viewmodel.SearchUiState

class SearchCoordinator(
    private val scope: CoroutineScope,
    private val search: SearchRepository,
    private val history: SearchHistoryStore,
    private val camera: CameraTracker,
    private val events: MapEventSink,
) {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var searchGeneration = 0
    private var session = Session.Idle

    init {
        scope.launch {
            _state.value = _state.value.copy(history = history.recent())
            events.emit(MapEvent.Snapshot)
        }
    }

    fun onCleared() {
        searchJob?.cancel()
    }

    fun setFocused(focused: Boolean) {
        if (_state.value.focused == focused) {
            return
        }
        _state.value = _state.value.copy(focused = focused)
    }

    fun clearSelected() {
        if (_state.value.selectedHit == null) {
            return
        }
        _state.value = _state.value.copy(selectedHit = null)
    }

    fun onQueryChange(text: String) {
        searchJob?.cancel()
        _state.value = _state.value.copy(query = text, error = null)
        if (!SearchLogic.shouldRequest(text)) {
            searchGeneration += 1
            _state.value = _state.value.copy(hits = emptyList(), loading = false)
            if (session == Session.Multi) {
                session = Session.Idle
                events.emit(MapEvent.SearchMultiCancelled)
            }
            return
        }
        searchJob = scope.launch {
            delay(MapDefaults.SEARCH_DEBOUNCE_MS)
            val request = ++searchGeneration
            _state.value = _state.value.copy(loading = true, error = null)
            events.emit(MapEvent.Snapshot)
            when (val result = search.search(text, camera.lat, camera.lon)) {
                is SearchResult.Ok -> {
                    if (request != searchGeneration) return@launch
                    val empty = result.body.hits.isEmpty()
                    val eligible = SearchLogic.isMultiEligible(result.body.hits)
                    _state.value = _state.value.copy(
                        hits = result.body.hits,
                        loading = false,
                        error = if (empty) SearchUiError.Empty else null,
                    )
                    when {
                        eligible && session != Session.Single -> {
                            session = Session.Multi
                            events.emit(MapEvent.SearchMultiRequested(result.body.hits))
                        }
                        !eligible && session == Session.Multi -> {
                            session = Session.Idle
                            events.emit(MapEvent.SearchMultiCancelled)
                        }
                        else -> events.emit(MapEvent.Snapshot)
                    }
                }
                is SearchResult.Unavailable -> {
                    if (request != searchGeneration) return@launch
                    _state.value = _state.value.copy(
                        hits = emptyList(),
                        loading = false,
                        error = SearchUiError.Unavailable,
                    )
                    events.emit(MapEvent.Snapshot)
                }
                is SearchResult.Network -> {
                    if (request != searchGeneration) return@launch
                    _state.value = _state.value.copy(
                        hits = emptyList(),
                        loading = false,
                        error = SearchUiError.Network,
                    )
                    events.emit(MapEvent.Snapshot)
                }
            }
        }
    }

    fun onClearSearch() {
        searchJob?.cancel()
        searchGeneration += 1
        session = Session.Idle
        _state.value = _state.value.copy(
            query = "",
            hits = emptyList(),
            loading = false,
            error = null,
            focused = true,
            selectedHit = null,
        )
        events.emit(MapEvent.SearchCleared)
    }

    fun onSelectHit(hit: SearchHit) {
        searchJob?.cancel()
        searchGeneration += 1
        session = Session.Single
        val queryToStore = _state.value.query.ifBlank { hit.label }
        _state.value = _state.value.copy(
            query = hit.label,
            focused = false,
            loading = false,
            error = null,
            selectedHit = hit,
        )
        events.emit(MapEvent.HitSelected(hit))
        scope.launch {
            history.save(queryToStore, hit.id, hit.label, hit.kind.name, hit.category_slug)
            val rows = history.recent()
            _state.value = _state.value.copy(history = rows)
            events.emit(MapEvent.Snapshot)
        }
    }

    fun onHistoryQuery(query: String) {
        onQueryChange(query)
        setFocused(true)
    }

    fun onShowAllOnMap() {
        val hits = _state.value.hits
        if (hits.isEmpty()) {
            return
        }
        session = Session.Multi
        events.emit(MapEvent.SearchMultiRequested(hits))
    }

    fun hitById(id: String): SearchHit? {
        return _state.value.hits.firstOrNull { it.id == id }
    }

    private enum class Session {
        Idle,
        Single,
        Multi,
    }
}
