package rs.zylos.novisad.ui.search

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import rs.zylos.novisad.R
import rs.zylos.novisad.databinding.ActivityMapBinding
import rs.zylos.novisad.map.MapDefaults
import rs.zylos.novisad.viewmodel.BottomTab
import rs.zylos.novisad.viewmodel.MapRouteMode
import rs.zylos.novisad.viewmodel.MapUiState
import rs.zylos.novisad.viewmodel.MapViewModel
import rs.zylos.novisad.viewmodel.RouteField
import rs.zylos.novisad.viewmodel.SearchUiError

class SearchDockBinder(
    private val binding: ActivityMapBinding,
    private val viewModel: MapViewModel,
    private val hideKeyboard: () -> Unit,
) {
    private var applyingSearchText = false

    private val searchAdapter = SearchDropdownAdapter(
        onHit = { hit ->
            hideKeyboard()
            if (viewModel.state.value.route.bottomTab == BottomTab.Route) {
                viewModel.onSelectRouteHit(hit)
            } else {
                viewModel.onSelectHit(hit)
            }
        },
        onHistory = { item ->
            applyingSearchText = true
            binding.searchInput.setText(item.query)
            binding.searchInput.setSelection(item.query.length)
            applyingSearchText = false
            viewModel.onHistoryQuery(item.query)
        },
    )

    fun bind() {
        binding.searchDropdown.layoutManager = LinearLayoutManager(binding.root.context)
        binding.searchDropdown.adapter = searchAdapter
        binding.searchClear.setOnClickListener {
            viewModel.onClearSearch()
            binding.searchInput.requestFocus()
        }
        binding.showAllOnMap.setOnClickListener {
            hideKeyboard()
            viewModel.onShowAllOnMap()
        }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (applyingSearchText) {
                    return
                }
                viewModel.onQueryChange(s?.toString().orEmpty())
            }
        })
        binding.searchInput.setOnFocusChangeListener { _, hasFocus ->
            viewModel.onSearchFocusChanged(hasFocus)
        }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val first = viewModel.state.value.search.hits.firstOrNull()
                if (first != null) {
                    hideKeyboard()
                    viewModel.onSelectHit(first)
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
        binding.tabSearch.setOnClickListener { viewModel.onSelectTab(BottomTab.Search) }
    }

    fun clearSearchFocus() {
        binding.searchInput.clearFocus()
    }

    fun render(state: MapUiState) {
        renderTabs(state)
        renderDropdown(state)
    }

    private fun renderTabs(state: MapUiState) {
        val routeTab = state.route.bottomTab == BottomTab.Route
        val showCta = routeTab && state.route.canBuild && state.route.mode != MapRouteMode.Result
        binding.tabBuildCta.visibility = if (showCta) View.VISIBLE else View.GONE
        binding.tabRoute.visibility = if (showCta) View.GONE else View.VISIBLE
        val context = binding.root.context
        val searchColor = if (!routeTab) R.color.accent else R.color.muted
        val routeColor = if (routeTab && !showCta) R.color.accent else R.color.muted
        binding.tabSearchIcon.imageTintList = ContextCompat.getColorStateList(context, searchColor)
        binding.tabSearchLabel.setTextColor(ContextCompat.getColor(context, searchColor))
        binding.tabRouteIcon.imageTintList = ContextCompat.getColorStateList(context, routeColor)
        binding.tabRouteLabel.setTextColor(ContextCompat.getColor(context, routeColor))
        binding.tabBuildCta.isEnabled = !state.route.loading
        binding.tabBuildCta.text = if (state.route.loading) {
            context.getString(R.string.ucitavam)
        } else {
            context.getString(R.string.ruta_napravi)
        }
    }

    private fun renderDropdown(state: MapUiState) {
        if (binding.searchInput.text.toString() != state.search.query) {
            applyingSearchText = true
            binding.searchInput.setText(state.search.query)
            binding.searchInput.setSelection(state.search.query.length)
            applyingSearchText = false
        }
        val loading = state.search.loading && state.search.query.length >= MapDefaults.SEARCH_MIN_LENGTH
        binding.searchClear.visibility =
            if (state.search.query.isNotEmpty() && !loading) View.VISIBLE else View.GONE
        binding.searchSpinner.visibility = if (loading) View.VISIBLE else View.GONE

        val showingHistory = state.route.bottomTab == BottomTab.Search &&
            state.search.focused && state.search.query.isEmpty() && state.search.history.isNotEmpty()
        val showingLive = if (state.route.bottomTab == BottomTab.Route && state.route.inputField != null) {
            val q = if (state.route.inputField == RouteField.From) state.route.fromQuery else state.route.toQuery
            q.length >= MapDefaults.SEARCH_MIN_LENGTH && !state.route.searchLoading
        } else {
            state.search.focused && state.search.query.length >= MapDefaults.SEARCH_MIN_LENGTH && !loading
        }
        val liveHits = if (state.route.bottomTab == BottomTab.Route) state.route.hits else state.search.hits
        val showingHits = showingLive && liveHits.isNotEmpty() &&
            (state.route.bottomTab == BottomTab.Route || state.search.error == null)
        val showingError = state.route.bottomTab == BottomTab.Search && showingLive && state.search.error != null
        val open = showingHistory || showingHits || showingError
        binding.searchDropdownCard.visibility = if (open) View.VISIBLE else View.GONE
        binding.searchHistoryHeader.visibility = if (showingHistory) View.VISIBLE else View.GONE
        val context = binding.root.context

        when {
            showingHistory -> {
                binding.searchStatus.visibility = View.GONE
                searchAdapter.submit(state.search.history.map { SearchRow.History(it) })
            }
            showingError && state.search.error == SearchUiError.Empty -> {
                binding.searchStatus.visibility = View.VISIBLE
                binding.searchStatus.setTextColor(context.getColor(R.color.muted))
                binding.searchStatus.setText(R.string.nista_nadjeno)
                searchAdapter.submit(emptyList())
            }
            showingError && state.search.error == SearchUiError.Unavailable -> {
                binding.searchStatus.visibility = View.VISIBLE
                binding.searchStatus.setTextColor(context.getColor(R.color.danger))
                binding.searchStatus.setText(R.string.pretraga_nedostupna)
                searchAdapter.submit(emptyList())
            }
            showingError && state.search.error == SearchUiError.Network -> {
                binding.searchStatus.visibility = View.VISIBLE
                binding.searchStatus.setTextColor(context.getColor(R.color.danger))
                binding.searchStatus.setText(R.string.nema_veze)
                searchAdapter.submit(emptyList())
            }
            showingHits -> {
                binding.searchStatus.visibility = View.GONE
                searchAdapter.submit(liveHits.map { SearchRow.Hit(it) })
            }
            else -> {
                binding.searchStatus.visibility = View.GONE
                searchAdapter.submit(emptyList())
            }
        }
        binding.showAllOnMap.visibility =
            if (showingHits && state.route.bottomTab == BottomTab.Search) View.VISIBLE else View.GONE
    }
}
