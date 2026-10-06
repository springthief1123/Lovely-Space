package io.github.springthief1123.lovelyspace.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.data.RoomListSource
import io.github.springthief1123.lovelyspace.data.SearchPreset
import io.github.springthief1123.lovelyspace.ui.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import io.github.springthief1123.lovelyspace.settings.RoomListPreferenceStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

data class SearchUiState(
    val genre: Genre = Genres.default,
    val criteria: RoomSearchCriteria = RoomSearchCriteria(),
    val minAgeInput: String = "",
    val maxAgeInput: String = "",
    val rooms: List<Room> = emptyList(),
    val page: Int = 0,
    val lastPage: Int = 1,
    val loading: Boolean = false,
    val error: String? = null,
    val errorOnMore: Boolean = false,
    val initialized: Boolean = true,
    val preferenceError: String? = null,
    val pageTimes: Map<Int, Long> = emptyMap(),
) {
    val validAges: Boolean get() = (minAgeInput.isEmpty() || minAgeInput.toIntOrNull()?.let { it in 18..99 } == true) &&
        (maxAgeInput.isEmpty() || maxAgeInput.toIntOrNull()?.let { it in 18..99 } == true) && criteria.isValid
    val results: List<Room> get() = if (validAges) searchRooms(rooms, criteria) else emptyList()
    val canLoadMore: Boolean get() = page in 1 until lastPage && !loading
}

class SearchViewModel(private val repository: RoomListSource, private val preferences: RoomListPreferenceStore? = null) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState(initialized = preferences == null))
    val state = _state.asStateFlow()
    private var job: Job? = null
    init {
        if (preferences != null) viewModelScope.launch {
            val genre = try { Genres[preferences.roomListPreferences.first().initialGenreKey()] ?: Genres.default }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(preferenceError = describeError(e)) }; Genres.default }
            _state.update { it.copy(genre = genre, initialized = true) }
            refresh()
            rememberGenre(genre.key)
        }
    }
    private fun rememberGenre(key: String) {
        val store = preferences ?: return
        viewModelScope.launch {
            try { store.setLastRoomGenre(key) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(preferenceError = describeError(e)) } }
        }
    }
    private var handledRefreshKey = 0
    fun onRefreshKey(key: Int) {
        if (key != handledRefreshKey && _state.value.initialized) { handledRefreshKey = key; refresh() }
    }
    fun criteria(value: RoomSearchCriteria) = _state.update { it.copy(criteria = value) }
    fun minAge(value: String) {
        val input = value.filter(Char::isDigit).take(2)
        _state.update { it.copy(minAgeInput = input, criteria = it.criteria.copy(minAge = input.toIntOrNull())) }
    }
    fun maxAge(value: String) {
        val input = value.filter(Char::isDigit).take(2)
        _state.update { it.copy(maxAgeInput = input, criteria = it.criteria.copy(maxAge = input.toIntOrNull())) }
    }
    fun genre(value: Genre) {
        if (value == _state.value.genre) return
        job?.cancel()
        _state.update { SearchUiState(genre = value, criteria = it.criteria, minAgeInput = it.minAgeInput, maxAgeInput = it.maxAgeInput) }
        rememberGenre(value.key)
    }
    /** 同じジャンルの取得済みページは再利用し、別ジャンルへの適用は通信を取消・結果を破棄する。 */
    fun applyPreset(value: SearchPreset) {
        val genre = requireNotNull(Genres[value.genreKey])
        require(value.criteria.isValid)
        if (genre != _state.value.genre) job?.cancel()
        _state.update {
            val scoped = if (genre == it.genre) it else SearchUiState(genre = genre)
            scoped.copy(criteria = value.criteria, minAgeInput = value.criteria.minAge?.toString().orEmpty(),
                maxAgeInput = value.criteria.maxAge?.toString().orEmpty())
        }
        rememberGenre(genre.key)
    }
    fun refresh() { if (_state.value.initialized) load(1, _state.value.page > 0) }
    fun more() { if (_state.value.canLoadMore) load(_state.value.page + 1, false) }
    private fun load(page: Int, force: Boolean) {
        job?.cancel()
        val genre = _state.value.genre
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                // AND/OR・除外・並び替えは取得済み一覧に適用。入力ごとに通信しない。
                val result = repository.fetch(RoomQuery(genre, page = page), force)
                ensureActive()
                if (_state.value.genre != genre) return@launch
                _state.update { it.copy(rooms = if (page == 1) result.rooms else (it.rooms + result.rooms).distinctBy(::roomIdentity),
                    page = result.page, lastPage = result.lastPage, loading = false, errorOnMore = false,
                    pageTimes = (if (page == 1) emptyMap() else it.pageTimes) +
                        (repository.observation(RoomQuery(genre, page = page))?.let { observation -> mapOf(page to observation.confirmedAt) } ?: emptyMap())) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(loading = false, error = describeError(e), errorOnMore = page > 1) } }
        }
    }
}
