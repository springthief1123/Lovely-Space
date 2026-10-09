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
    /** 取得したページ（本家の一覧のページ）ごとの確認時刻。 */
    val pageTimes: Map<RoomQuery, Long> = emptyMap(),
    val automatic: Boolean = true,
    val newRoomIds: Set<String> = emptySet(),
    /** [rooms] を本家から取ったときの絞り込み（各一覧の 1 ページ目）。まだ取っていなければ null。 */
    val listQueries: List<RoomQuery>? = null,
) {
    val validAges: Boolean get() = (minAgeInput.isEmpty() || minAgeInput.toIntOrNull()?.let { it in 18..99 } == true) &&
        (maxAgeInput.isEmpty() || maxAgeInput.toIntOrNull()?.let { it in 18..99 } == true) && criteria.isValid
    /** 一覧に使う条件。年齢の入力が正しくない間は年齢の条件だけを外し、一覧は消さない。 */
    val effectiveCriteria: RoomSearchCriteria get() = if (validAges) criteria else criteria.copy(minAge = null, maxAge = null)
    val results: List<Room> get() = searchRooms(rooms, effectiveCriteria)
    val canLoadMore: Boolean get() = page in 1 until lastPage && !loading
    /**
     * 本家に渡す絞り込み（各一覧の 1 ページ目）。性別・待機・公開・語などは本家側で絞り、残りは取得後に端末で判定する。
     * 共通検索欄の語は名前と募集文の 2 つの一覧で探す（[siteQueries]）。[page] と [lastPage] は全一覧の合計。
     */
    val siteQueries: List<RoomQuery> get() = effectiveCriteria.siteQueries(genre)
    /**
     * 本家側の条件を変えた後で、まだ新しい条件の一覧を取れていない。表示中の [results] は前の条件で取った部屋に
     * 新しい条件を掛けたものなので、条件を広げた場合（女性 → 指定なしなど）は本来出る部屋が欠けている。
     */
    val awaitingNewConditions: Boolean get() = listQueries != null && listQueries != siteQueries
}

class SearchViewModel(private val repository: RoomListSource, private val preferences: RoomListPreferenceStore? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** 取り直しの間隔の設定。巡回のたびに読むので、設定の変更は次の取得から効く。 */
    private val pacing: () -> RefreshPacing = { RefreshPacing() }) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState(initialized = preferences == null))
    val state = _state.asStateFlow()
    private var job: Job? = null
    /** [windowQueries] の一覧ごとの取得済みページ。 */
    private var windows: List<RoomPageWindow> = emptyList()
    /** [windows] がどの絞り込みの一覧か。条件が変わったら次の取得で一覧を作り直す。 */
    private var windowQueries: List<RoomQuery>? = null
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
        if (!_state.value.initialized || _state.value.siteQueries == windowQueries) return
        // まだ一度も取得しておらず、取得中でもなければ、最初の取得（refresh）に任せる。
        if (windowQueries == null && job?.isActive != true) return
        requery = viewModelScope.launch {
            delay(REQUERY_DELAY_MS)
            load(heads(), false)
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
        windows = emptyList(); windowQueries = null
        _state.update { SearchUiState(automatic = it.automatic, genre = value, criteria = it.criteria, minAgeInput = it.minAgeInput, maxAgeInput = it.maxAgeInput) }
        rememberGenre(value.key)
    }
    /** 同じジャンルの取得済みページは再利用し、別ジャンルへの適用は通信を取消・結果を破棄する。 */
    fun applyPreset(value: SearchPreset) {
        val genre = requireNotNull(Genres[value.genreKey])
        require(value.criteria.isValid)
        val sameGenre = genre == _state.value.genre
        if (!sameGenre) { job?.cancel(); requery?.cancel(); windows = emptyList(); windowQueries = null }
        _state.update {
            val scoped = if (genre == it.genre) it else SearchUiState(genre = genre, automatic = it.automatic)
            scoped.copy(criteria = value.criteria, minAgeInput = value.criteria.minAge?.toString().orEmpty(),
                maxAgeInput = value.criteria.maxAge?.toString().orEmpty())
        }
        if (sameGenre) requeryIfNeeded()
        rememberGenre(genre.key)
    }
    fun refresh() { if (_state.value.initialized) load(heads(), _state.value.page > 0) }
    /** まだ読んでいないページを 1 つ読む。 */
    fun more() {
        if (!_state.value.canLoadMore) return
        val next = windows.withIndex().firstNotNullOfOrNull { (i, w) ->
            (if (w.pages.isEmpty()) 1 else (2..w.lastPage).firstOrNull { it !in w.pages })?.let { i to it }
        } ?: return
        load(listOf(next), false)
    }
    /** 各一覧の 1 ページ目。 */
    private fun heads() = _state.value.siteQueries.indices.map { it to 1 }
    fun automatic(enabled: Boolean) = _state.update { it.copy(automatic = enabled) }
    fun clearNewRooms() = _state.update { it.copy(newRoomIds = emptySet()) }
    fun clearPreferenceError() = _state.update { it.copy(preferenceError = null) }
    fun stopRefresh() { job?.cancel(); requery?.cancel(); _state.update { it.copy(loading = false) } }

    /**
     * 画面が前面にある間だけ実行。取消はHTTP取得にも伝わる。
     * 一覧を 1 ページ目から最後のページまで順に読み直し、読み終えたらまた 1 ページ目から読む（[RoomPageSchedule]）。
     * 新しい周は前の周の始まりから設定の間隔（[RefreshPacing.searchHeadMs]）が経つまで始めない。
     * 一覧が 2 つ（共通検索欄の語を名前と募集文で探す）なら、待たずに読める方から、同じなら交互に取る。
     */
    suspend fun monitor() {
        val schedules = mutableMapOf<RoomQuery, RoomPageSchedule>()
        var turn = 0
        try {
            while (currentCoroutineContext().isActive && _state.value.automatic) {
                if (!_state.value.initialized) { state.first { it.initialized }; continue }
                job?.join()
                val queries = _state.value.siteQueries
                schedules.keys.retainAll(queries.toSet())
                val current = if (queries == windowQueries) windows else queries.map { RoomPageWindow() }
                fun scheduleOf(i: Int) = schedules.getOrPut(queries[i]) { schedule() }
                val index = queries.indices.map { (turn + it) % queries.size }.minBy { scheduleOf(it).waitMs(current[it].lastPage) }
                val wait = scheduleOf(index).waitMs(current[index].lastPage)
                // 新しい周の始まりを待つ間に条件が変わることがあるので、待った後に選び直す。
                if (wait > 0) { delay(wait); continue }
                turn = index + 1
                // 条件が変わった直後は 1 ページ目を取るので、実際に取ったページで巡回位置を進める。
                val (fetched, page) = fetchPage(index, scheduleOf(index).next(current[index].lastPage), force = false)
                if (_state.value.error == null) {
                    val lastPage = windowQueries?.indexOf(fetched)?.let { windows.getOrNull(it) }?.lastPage ?: 1
                    schedules.getOrPut(fetched) { schedule() }.completed(page, lastPage)
                }
                // ページ同士は通信の最小間隔で順に取る（間隔は ShaloveClient も守る）。
                delay(if (_state.value.error != null) RoomPageSchedule.ERROR_INTERVAL_MS else pacing().sanitized().minIntervalMs)
            }
        } finally { stopRefresh() }
    }

    private fun schedule() = RoomPageSchedule(clock) { pacing().sanitized().searchHeadMs }

    /** [targets]（一覧の番号とページ）を順に取る。失敗したらそこで止める。 */
    private fun load(targets: List<Pair<Int, Int>>, force: Boolean) {
        job?.cancel()
        job = viewModelScope.launch {
            for ((index, page) in targets) {
                fetchPage(index, page, force)
                if (_state.value.error != null) break
            }
        }
    }
    /** [index] 番目の一覧の [page] を取る。実際に取った一覧（1 ページ目）とページを返す。 */
    private suspend fun fetchPage(index: Int, page: Int, force: Boolean): Pair<RoomQuery, Int> = fetchMutex.withLock {
        val queries = _state.value.siteQueries
        // 条件が変わった直後は、新しい絞り込みの 1 ページ目から取り直す（ページ数は 1 ページ目で分かる）。
        val fresh = queries != windowQueries
        val i = if (fresh) 0 else index.coerceIn(queries.indices)
        val base = queries[i]
        val target = if (fresh) 1 else page
        _state.update { it.copy(loading = true, error = null) }
        try {
            // AND/OR・除外・並び替えは取得済み一覧に適用。入力ごとに通信しない。
            val query = base.copy(page = target)
            val result = repository.fetch(query, force)
            currentCoroutineContext().ensureActive()
            // 取得中にジャンルや本家側の条件が変わったら、古い条件の結果は捨てる。
            if (_state.value.siteQueries != queries) {
                _state.update { it.copy(loading = false) }
                requeryIfNeeded()
                return@withLock base to target
            }
            val observation = repository.observation(query)?.takeIf { it.query == query }
            if (fresh) { windows = queries.map { RoomPageWindow() }; windowQueries = queries }
            // 初めて読んだページの部屋は、前からあった部屋なので新着にしない。新着は読んだことのあるページの取り直しで数える。
            val firstSight = target !in windows[i].pages
            val previous = _state.value.rooms.map(::roomIdentity).toSet()
            windows = windows.toMutableList().also { it[i] = it[i].observe(observation?.page ?: result, observation?.revision) }
            val rooms = mergedRooms()
            val ids = rooms.map(::roomIdentity).toSet()
            _state.update { it.copy(rooms = rooms, listQueries = queries,
                page = windows.sumOf { w -> w.pages.size }, lastPage = windows.sumOf { w -> w.lastPage }, loading = false, errorOnMore = false,
                newRoomIds = ((if (fresh) emptySet() else it.newRoomIds) + if (fresh || firstSight) emptySet() else ids - previous).intersect(ids),
                pageTimes = ((if (fresh) emptyMap() else it.pageTimes) + (observation?.let { mapOf(query to it.confirmedAt) } ?: emptyMap()))
                    .filterKeys { q -> windows.getOrNull(queries.indexOf(q.firstPage))?.let { w -> q.page <= w.lastPage } == true }) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.update { it.copy(loading = false, error = describeError(e), errorOnMore = target > 1 || i > 0) } }
        base to target
    }

    /** 一覧が 1 つなら本家の並び。2 つなら重複を除き、部屋番号の新しい順に並べる。 */
    private fun mergedRooms(): List<Room> = windows.singleOrNull()?.rooms
        ?: windows.flatMap { it.rooms }.distinctBy(::roomIdentity).sortedByDescending { it.id }

    companion object {
        /** 条件の変更から取り直すまでの待ち（ミリ秒）。年齢の入力などが続く間は取り直さない。 */
        const val REQUERY_DELAY_MS = 600L
    }
}
