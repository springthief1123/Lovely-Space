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
    /** [rooms] を本家から取ったときの絞り込み（1 ページ目）。まだ取っていなければ null。 */
    val listQuery: RoomQuery? = null,
) {
    val validAges: Boolean get() = (minAgeInput.isEmpty() || minAgeInput.toIntOrNull()?.let { it in 18..99 } == true) &&
        (maxAgeInput.isEmpty() || maxAgeInput.toIntOrNull()?.let { it in 18..99 } == true) && criteria.isValid
    /** 一覧に使う条件。年齢の入力が正しくない間は年齢の条件だけを外し、一覧は消さない。 */
    val effectiveCriteria: RoomSearchCriteria get() = if (validAges) criteria else criteria.copy(minAge = null, maxAge = null)
    val results: List<Room> get() = searchRooms(rooms, effectiveCriteria)
    val canLoadMore: Boolean get() = page in 1 until lastPage && !loading
    /** 本家に渡す絞り込み（1 ページ目）。性別・待機・公開などは本家側で絞り、残りは取得後に端末で判定する。 */
    val siteQuery: RoomQuery get() = effectiveCriteria.siteQuery(genre)
    /**
     * 本家側の条件を変えた後で、まだ新しい条件の一覧を取れていない。表示中の [results] は前の条件で取った部屋に
     * 新しい条件を掛けたものなので、条件を広げた場合（女性 → 指定なしなど）は本来出る部屋が欠けている。
     */
    val awaitingNewConditions: Boolean get() = listQuery != null && listQuery != siteQuery
}

class SearchViewModel(private val repository: RoomListSource, private val preferences: RoomListPreferenceStore? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState(initialized = preferences == null))
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var window = RoomPageWindow()
    /** [window] がどの絞り込みの一覧か。条件が変わったら次の取得で一覧を作り直す。 */
    private var windowQuery: RoomQuery? = null
    /** 条件の変更で取り直すまでの待ち。入力が続く間は取り直さない。 */
    private var requery: Job? = null
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
    fun criteria(value: RoomSearchCriteria) { _state.update { it.copy(criteria = value) }; requeryIfNeeded() }

    /**
     * 本家側に渡す条件が変わったら、少し待ってから 1 ページ目から取り直す。端末側だけの条件（語・除外・並び替えなど）なら通信しない。
     * 取り直すまでの間は前の一覧に新しい条件を掛けて表示する。
     */
    private fun requeryIfNeeded() {
        requery?.cancel()
        if (!_state.value.initialized || _state.value.siteQuery == windowQuery) return
        // まだ一度も取得しておらず、取得中でもなければ、最初の取得（refresh）に任せる。
        if (windowQuery == null && job?.isActive != true) return
        requery = viewModelScope.launch {
            delay(REQUERY_DELAY_MS)
            load(1, false)
        }
    }

    /** 条件をすべて既定に戻し、戻す前の入力を返す。もともと既定なら何もせず null。 */
    fun resetCriteria(): SearchDraft? {
        val s = _state.value
        val draft = SearchDraft(s.criteria, s.minAgeInput, s.maxAgeInput)
        if (draft == SearchDraft(RoomSearchCriteria(), "", "")) return null
        _state.update { it.copy(criteria = RoomSearchCriteria(), minAgeInput = "", maxAgeInput = "") }
        requeryIfNeeded()
        return draft
    }

    fun restoreCriteria(draft: SearchDraft) {
        _state.update { it.copy(criteria = draft.criteria, minAgeInput = draft.minAgeInput, maxAgeInput = draft.maxAgeInput) }
        requeryIfNeeded()
    }
    fun minAge(value: String) {
        val input = value.filter(Char::isDigit).take(2)
        _state.update { it.copy(minAgeInput = input, criteria = it.criteria.copy(minAge = input.toIntOrNull())) }
        requeryIfNeeded()
    }
    fun maxAge(value: String) {
        val input = value.filter(Char::isDigit).take(2)
        _state.update { it.copy(maxAgeInput = input, criteria = it.criteria.copy(maxAge = input.toIntOrNull())) }
        requeryIfNeeded()
    }
    fun genre(value: Genre) {
        if (value == _state.value.genre) return
        job?.cancel(); requery?.cancel()
        window = RoomPageWindow(); windowQuery = null
        _state.update { SearchUiState(automatic = it.automatic, genre = value, criteria = it.criteria, minAgeInput = it.minAgeInput, maxAgeInput = it.maxAgeInput) }
        rememberGenre(value.key)
    }
    /** 同じジャンルの取得済みページは再利用し、別ジャンルへの適用は通信を取消・結果を破棄する。 */
    fun applyPreset(value: SearchPreset) {
        val genre = requireNotNull(Genres[value.genreKey])
        require(value.criteria.isValid)
        val sameGenre = genre == _state.value.genre
        if (!sameGenre) { job?.cancel(); requery?.cancel(); window = RoomPageWindow(); windowQuery = null }
        _state.update {
            val scoped = if (genre == it.genre) it else SearchUiState(genre = genre, automatic = it.automatic)
            scoped.copy(criteria = value.criteria, minAgeInput = value.criteria.minAge?.toString().orEmpty(),
                maxAgeInput = value.criteria.maxAge?.toString().orEmpty())
        }
        if (sameGenre) requeryIfNeeded()
        rememberGenre(genre.key)
    }
    fun refresh() { if (_state.value.initialized) load(1, _state.value.page > 0) }
    fun more() { if (_state.value.canLoadMore) load(_state.value.page + 1, false) }
    fun automatic(enabled: Boolean) = _state.update { it.copy(automatic = enabled) }
    fun clearNewRooms() = _state.update { it.copy(newRoomIds = emptySet()) }
    fun clearPreferenceError() = _state.update { it.copy(preferenceError = null) }
    fun stopRefresh() { job?.cancel(); requery?.cancel(); _state.update { it.copy(loading = false) } }

    /**
     * 画面が前面にある間だけ実行。取消はHTTP取得にも伝わる。
     * 新着が出る 1 ページ目を [RoomPageSchedule.HEAD_INTERVAL_MS] ごとに取り直し、その合間に残りのページを順に巡る。
     */
    suspend fun monitor() {
        val schedule = RoomPageSchedule(clock)
        try {
            while (currentCoroutineContext().isActive && _state.value.automatic) {
                if (!_state.value.initialized) { state.first { it.initialized }; continue }
                job?.join()
                // 条件が変わった直後は 1 ページ目を取るので、実際に取ったページで巡回位置を進める。
                val page = fetchPage(schedule.next(_state.value.lastPage), force = false)
                if (_state.value.error == null) schedule.completed(page, _state.value.lastPage)
                delay(when {
                    _state.value.error != null -> RoomPageSchedule.ERROR_INTERVAL_MS
                    _state.value.lastPage <= 1 -> RoomPageSchedule.HEAD_INTERVAL_MS
                    else -> RoomPageSchedule.STEP_INTERVAL_MS
                })
            }
        } finally { stopRefresh() }
    }

    private fun load(page: Int, force: Boolean) {
        job?.cancel()
        job = viewModelScope.launch { fetchPage(page, force) }
    }
    private suspend fun fetchPage(page: Int, force: Boolean) = fetchMutex.withLock {
        val base = _state.value.siteQuery
        // 条件が変わった直後は、新しい絞り込みの 1 ページ目から取り直す（ページ数は 1 ページ目で分かる）。
        val fresh = base != windowQuery
        val target = if (fresh) 1 else page
        _state.update { it.copy(loading = true, error = null) }
        try {
            // AND/OR・除外・並び替えは取得済み一覧に適用。入力ごとに通信しない。
            val query = base.copy(page = target)
            val result = repository.fetch(query, force)
            currentCoroutineContext().ensureActive()
            // 取得中にジャンルや本家側の条件が変わったら、古い条件の結果は捨てる。
            if (_state.value.siteQuery != base) {
                _state.update { it.copy(loading = false) }
                requeryIfNeeded()
                return@withLock target
            }
            val observation = repository.observation(query)?.takeIf { it.query == query }
            if (fresh) { window = RoomPageWindow(); windowQuery = base }
            val previous = if (fresh) emptySet() else _state.value.rooms.map(::roomIdentity).toSet()
            window = window.observe(observation?.page ?: result, observation?.revision)
            val rooms = window.rooms
            _state.update { it.copy(rooms = rooms, listQuery = base,
                page = window.pages.keys.maxOrNull() ?: 0, lastPage = window.lastPage, loading = false, errorOnMore = false,
                newRoomIds = ((if (fresh) emptySet() else it.newRoomIds) + if (previous.isEmpty()) emptySet() else rooms.map(::roomIdentity).toSet() - previous).intersect(rooms.map(::roomIdentity).toSet()),
                pageTimes = ((if (fresh) emptyMap() else it.pageTimes) + (observation?.let { mapOf(target to it.confirmedAt) } ?: emptyMap()))
                    .filterKeys { it <= window.lastPage }) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.update { it.copy(loading = false, error = describeError(e), errorOnMore = target > 1) } }
        target
    }

    companion object {
        /** 条件の変更から取り直すまでの待ち（ミリ秒）。年齢の入力などが続く間は取り直さない。 */
        const val REQUERY_DELAY_MS = 600L
    }
}
