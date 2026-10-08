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
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 「条件をリセット」の前の入力。「元に戻す」で戻す。年齢は入力途中の文字もそのまま持つ。 */
data class SearchDraft(val criteria: RoomSearchCriteria, val minAgeInput: String, val maxAgeInput: String)

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
    val automatic: Boolean = true,
    val newRoomIds: Set<String> = emptySet(),
) {
    val validAges: Boolean get() = (minAgeInput.isEmpty() || minAgeInput.toIntOrNull()?.let { it in 18..99 } == true) &&
        (maxAgeInput.isEmpty() || maxAgeInput.toIntOrNull()?.let { it in 18..99 } == true) && criteria.isValid
    val results: List<Room> get() = if (validAges) searchRooms(rooms, criteria) else emptyList()
    val canLoadMore: Boolean get() = page in 1 until lastPage && !loading
}

class SearchViewModel(private val repository: RoomListSource, private val preferences: RoomListPreferenceStore? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState(initialized = preferences == null))
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var window = RoomPageWindow()
    private val fetchMutex = Mutex()
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

    /** 条件をすべて既定に戻し、戻す前の入力を返す。もともと既定なら何もせず null。 */
    fun resetCriteria(): SearchDraft? {
        val s = _state.value
        val draft = SearchDraft(s.criteria, s.minAgeInput, s.maxAgeInput)
        if (draft == SearchDraft(RoomSearchCriteria(), "", "")) return null
        _state.update { it.copy(criteria = RoomSearchCriteria(), minAgeInput = "", maxAgeInput = "") }
        return draft
    }

    fun restoreCriteria(draft: SearchDraft) =
        _state.update { it.copy(criteria = draft.criteria, minAgeInput = draft.minAgeInput, maxAgeInput = draft.maxAgeInput) }
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
        window = RoomPageWindow()
        _state.update { SearchUiState(automatic = it.automatic, genre = value, criteria = it.criteria, minAgeInput = it.minAgeInput, maxAgeInput = it.maxAgeInput) }
        rememberGenre(value.key)
    }
    /** 同じジャンルの取得済みページは再利用し、別ジャンルへの適用は通信を取消・結果を破棄する。 */
    fun applyPreset(value: SearchPreset) {
        val genre = requireNotNull(Genres[value.genreKey])
        require(value.criteria.isValid)
        if (genre != _state.value.genre) { job?.cancel(); window = RoomPageWindow() }
        _state.update {
            val scoped = if (genre == it.genre) it else SearchUiState(genre = genre, automatic = it.automatic)
            scoped.copy(criteria = value.criteria, minAgeInput = value.criteria.minAge?.toString().orEmpty(),
                maxAgeInput = value.criteria.maxAge?.toString().orEmpty())
        }
        rememberGenre(genre.key)
    }
    fun refresh() { if (_state.value.initialized) load(1, _state.value.page > 0) }
    fun more() { if (_state.value.canLoadMore) load(_state.value.page + 1, false) }
    fun automatic(enabled: Boolean) = _state.update { it.copy(automatic = enabled) }
    fun clearNewRooms() = _state.update { it.copy(newRoomIds = emptySet()) }
    fun stopRefresh() { job?.cancel(); _state.update { it.copy(loading = false) } }

    /** 画面が前面にある間だけ実行。取消はHTTP取得にも伝わる。 */
    suspend fun monitor() {
        val schedule = RoomPageSchedule(clock)
        try {
            while (currentCoroutineContext().isActive && _state.value.automatic) {
                if (!_state.value.initialized) { state.first { it.initialized }; continue }
                job?.join()
                val page = schedule.next(_state.value.lastPage)
                fetchPage(page, force = false)
                if (_state.value.error == null) schedule.completed(page, _state.value.lastPage)
                delay(if (_state.value.error != null || _state.value.lastPage <= 1) RoomPageSchedule.HEAD_INTERVAL_MS else RoomPageSchedule.STEP_INTERVAL_MS)
            }
        } finally { stopRefresh() }
    }

    private fun load(page: Int, force: Boolean) {
        job?.cancel()
        job = viewModelScope.launch { fetchPage(page, force) }
    }
    private suspend fun fetchPage(page: Int, force: Boolean) = fetchMutex.withLock {
        val genre = _state.value.genre
        _state.update { it.copy(loading = true, error = null) }
        try {
            // AND/OR・除外・並び替えは取得済み一覧に適用。入力ごとに通信しない。
            val query = RoomQuery(genre, page = page)
            val result = repository.fetch(query, force)
            currentCoroutineContext().ensureActive()
            if (_state.value.genre != genre) return@withLock
            val observation = repository.observation(query)?.takeIf { it.query == query }
            val previous = _state.value.rooms.map(::roomIdentity).toSet()
            window = window.observe(observation?.page ?: result, observation?.revision)
            val rooms = window.rooms
            _state.update { it.copy(rooms = rooms,
                page = window.pages.keys.maxOrNull() ?: 0, lastPage = window.lastPage, loading = false, errorOnMore = false,
                newRoomIds = (it.newRoomIds + if (previous.isEmpty()) emptySet() else rooms.map(::roomIdentity).toSet() - previous).intersect(rooms.map(::roomIdentity).toSet()),
                pageTimes = (it.pageTimes + (observation?.let { mapOf(page to it.confirmedAt) } ?: emptyMap()))
                    .filterKeys { it <= window.lastPage }) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.update { it.copy(loading = false, error = describeError(e), errorOnMore = page > 1) } }
    }
}
