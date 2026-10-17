package io.github.springthief1123.lovelyspace.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.data.ObservedRoomListSource
import io.github.springthief1123.lovelyspace.data.ObservedRoomPage
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

/** 「条件をリセット」の前の入力。「元に戻す」で戻す。年齢は入力途中の文字もそのまま持つ。 */
data class SearchDraft(val criteria: RoomSearchCriteria, val minAgeInput: String, val maxAgeInput: String,
    val keywordScope: KeywordScope = KeywordScope.ALL)

/**
 * 検索欄の語を探す場所。語は条件の 1 つの欄（共通検索欄 [RoomSearchCriteria.text]・名前・待機メッセージ）に入れる。
 * どの場所でも本家には渡さず、取得済みの一覧を端末で絞る。
 */
enum class KeywordScope(val label: String, val placeholder: String) {
    ALL("名前と待機メッセージ", "名前・待機メッセージを検索"),
    NAME("名前", "名前を検索"),
    MESSAGE("待機メッセージ", "待機メッセージを検索"),
}

/** [scope] の欄に入っている語。 */
internal fun RoomSearchCriteria.keyword(scope: KeywordScope): String = when (scope) {
    KeywordScope.ALL -> text
    KeywordScope.NAME -> name
    KeywordScope.MESSAGE -> message
}

/** [scope] の欄の語を [value] にした条件。 */
internal fun RoomSearchCriteria.withKeyword(scope: KeywordScope, value: String): RoomSearchCriteria = when (scope) {
    KeywordScope.ALL -> copy(text = value)
    KeywordScope.NAME -> copy(name = value)
    KeywordScope.MESSAGE -> copy(message = value)
}

/**
 * 語を探す場所を [from] から [to] へ移す。検索欄の語はそのまま、新しい場所で探す。
 * 移した先の欄に語が残っていれば（以前の版で保存した条件など）、どちらも動かさない。検索欄にはその語が出て、
 * 元の語は「ほかに…でも絞り込んでいます」（[hiddenKeywords]）に残る。つなげると「どれかを含む」で条件が緩むため。
 */
internal fun RoomSearchCriteria.moveKeyword(from: KeywordScope, to: KeywordScope): RoomSearchCriteria =
    if (from == to || keyword(from).isBlank() || keyword(to).isNotBlank()) this
    else withKeyword(from, "").withKeyword(to, keyword(from))

/** 保存した条件などから、検索欄に出す欄を決める。名前・待機メッセージの片方だけに語があればその欄、ほかは共通検索欄。 */
internal fun keywordScopeOf(c: RoomSearchCriteria): KeywordScope = when {
    c.text.isNotBlank() -> KeywordScope.ALL
    c.name.isNotBlank() && c.message.isBlank() -> KeywordScope.NAME
    c.message.isNotBlank() && c.name.isBlank() -> KeywordScope.MESSAGE
    else -> KeywordScope.ALL
}

/** [scope] 以外の欄の語を外した条件。検索欄に出していない語（[hiddenKeywords]）を「外す」ときに使う。 */
internal fun RoomSearchCriteria.onlyKeyword(scope: KeywordScope): RoomSearchCriteria =
    KeywordScope.entries.filter { it != scope }.fold(this) { c, other -> c.withKeyword(other, "") }

/** 年齢の範囲が正しくないときの案内。見つけると巡回計画で同じ文にする。 */
internal const val AGE_RANGE_ERROR = "年齢は18〜99で、最高年齢が最低年齢以上になる範囲を指定してください。"

/** 年齢の入力（空欄は指定なし）が 18〜99 で、最低年齢が最高年齢以下か。 */
internal fun validAgeInputs(min: String, max: String): Boolean =
    (min.isEmpty() || min.toIntOrNull() != null) && (max.isEmpty() || max.toIntOrNull() != null) &&
        RoomSearchCriteria(minAge = min.toIntOrNull(), maxAge = max.toIntOrNull()).isValid

/** 検索欄に出していない欄に残っている語（以前の版で保存した条件など）。欄の名前と語の組。 */
internal fun hiddenKeywords(c: RoomSearchCriteria, scope: KeywordScope): List<Pair<KeywordScope, String>> =
    KeywordScope.entries.filter { it != scope }.map { it to c.keyword(it) }.filter { (_, word) -> word.isNotBlank() }

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
    /** 検索欄の語を探す場所。 */
    val keywordScope: KeywordScope = KeywordScope.ALL,
    /** 利用者が押した取り直し（[SearchViewModel.refresh]）の途中。自動の取得（[loading]）とは別に、更新ボタンの回転に使う。 */
    val refreshing: Boolean = false,
    /** 押した取り直しが成功した回数。増えたら画面で「更新しました」を出す。 */
    val refreshedCount: Int = 0,
) {
    val validAges: Boolean get() = (minAgeInput.isEmpty() || minAgeInput.toIntOrNull()?.let { it in 18..99 } == true) &&
        (maxAgeInput.isEmpty() || maxAgeInput.toIntOrNull()?.let { it in 18..99 } == true) && criteria.isValid
    /** 一覧に使う条件。年齢の入力が正しくない間は年齢の条件だけを外し、一覧は消さない。 */
    val effectiveCriteria: RoomSearchCriteria get() = if (validAges) criteria else criteria.copy(minAge = null, maxAge = null)
    /** 取得済みの全ページに条件を掛けた結果。条件を変えても通信せず、その場で絞り直す。 */
    val results: List<Room> get() = searchRooms(rooms, effectiveCriteria)
    val canLoadMore: Boolean get() = page in 1 until lastPage && !loading
}

/**
 * 見つける画面。本家からはジャンル全体の一覧（本家側の絞り込みなし）を全ページ読んで手元に持ち、条件はすべて端末で判定する。
 * 語・性別・待機などを変えても、検索ボタンを押しても通信せず、取得済みの全ページからその場で結果を出す（Yuya の決定、2026-10-16）。
 *
 * レーダーの巡回も同じジャンル全体の一覧を [RoomListRepository] から読むので、[ObservedRoomListSource] なら
 * 他の機能が読んだページもこの画面の一覧に取り込む（ジャンルを開いた直後から結果が出る。古すぎるページは取り込まない）。
 */
class SearchViewModel(private val repository: RoomListSource, private val preferences: RoomListPreferenceStore? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** 取り直しの間隔の設定。巡回のたびに読むので、設定の変更は次の取得から効く。 */
    private val pacing: () -> RefreshPacing = { RefreshPacing() },
    /** 共有の一覧の確認時刻（[ObservedRoomPage.confirmedAt]）と比べる時計。 */
    private val now: () -> Long = System::currentTimeMillis) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState(initialized = preferences == null))
    val state = _state.asStateFlow()
    private var job: Job? = null
    /** いまのジャンルの一覧の取得済みページ。 */
    private var window = RoomPageWindow()
    /** 利用者が押した取り直し（[refresh]）。巡回や続きの読み込みを待たせずに並行して走る。 */
    private var refreshJob: Job? = null
    /** 取り直し中にもう一度押された。終わったらもう 1 回だけ取り直す（何度押されても 1 回にまとめる）。 */
    private var refreshAgain = false
    /** 進行中の取得の数。0 でなければ読み込み中と表示する。 */
    private var inFlight = 0
    /** いまのジャンルの巡回位置。画面に戻って [monitor] を呼び直しても、ジャンルを変えるまでは最初の周からやり直さない。 */
    private var schedule: RoomPageSchedule? = null
    init {
        if (repository is ObservedRoomListSource) viewModelScope.launch {
            repository.observations.collect { observed -> if (_state.value.initialized) absorbShared(observed.values) }
        }
        if (preferences != null) viewModelScope.launch {
            val genre = try { Genres[preferences.roomListPreferences.first().initialGenreKey()] ?: Genres.default }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(preferenceError = describeError(e)) }; Genres.default }
            _state.update { it.copy(genre = genre, initialized = true) }
            seedShared()
            load(1, _state.value.page > 0)
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
    /** 条件はすべて端末で判定するので、変えても通信しない。 */
    fun criteria(value: RoomSearchCriteria) = _state.update { it.copy(criteria = value) }
    /** 検索欄の語。いま選んでいる場所（[SearchUiState.keywordScope]）の欄に入れる。 */
    fun keyword(value: String) = criteria(_state.value.let { it.criteria.withKeyword(it.keywordScope, value) })
    fun keywordScope(value: KeywordScope) =
        _state.update { it.copy(criteria = it.criteria.moveKeyword(it.keywordScope, value), keywordScope = value) }
    /** 検索欄に出していない欄の語を消す。 */
    fun clearHiddenKeywords() = criteria(_state.value.let { s -> s.criteria.onlyKeyword(s.keywordScope) })

    /** 条件をすべて既定に戻し、戻す前の入力を返す。もともと既定なら何もせず null。 */
    fun resetCriteria(): SearchDraft? {
        val s = _state.value
        val draft = SearchDraft(s.criteria, s.minAgeInput, s.maxAgeInput, s.keywordScope)
        if (draft == SearchDraft(RoomSearchCriteria(), "", "")) return null
        _state.update { it.copy(criteria = RoomSearchCriteria(), minAgeInput = "", maxAgeInput = "", keywordScope = KeywordScope.ALL) }
        return draft
    }

    fun restoreCriteria(draft: SearchDraft) {
        _state.update { it.copy(criteria = draft.criteria, minAgeInput = draft.minAgeInput, maxAgeInput = draft.maxAgeInput,
            keywordScope = draft.keywordScope) }
    }
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
        cancelFetches()
        window = RoomPageWindow(); schedule = null
        _state.update { SearchUiState(automatic = it.automatic, genre = value, criteria = it.criteria, minAgeInput = it.minAgeInput, maxAgeInput = it.maxAgeInput,
            keywordScope = it.keywordScope) }
        seedShared()
        rememberGenre(value.key)
    }
    /** 同じジャンルの取得済みページは再利用し、別ジャンルへの適用は通信を取消・結果を破棄する。 */
    fun applyPreset(value: SearchPreset) {
        val genre = requireNotNull(Genres[value.genreKey])
        require(value.criteria.isValid)
        val sameGenre = genre == _state.value.genre
        if (!sameGenre) { cancelFetches(); window = RoomPageWindow(); schedule = null }
        _state.update {
            val scoped = if (sameGenre) it else SearchUiState(genre = genre, automatic = it.automatic)
            scoped.copy(criteria = value.criteria, minAgeInput = value.criteria.minAge?.toString().orEmpty(),
                maxAgeInput = value.criteria.maxAge?.toString().orEmpty(), keywordScope = keywordScopeOf(value.criteria))
        }
        if (!sameGenre) seedShared()
        rememberGenre(genre.key)
    }
    /**
     * 利用者が押した更新（本家の「最新更新」）。1 ページ目を取り直す。読み込み中でも押せて、押すたびに取り直す。
     * 本家のブラウザと同じく間隔は空けない（[RoomListSource.fetchNow]）。取り直し中に押された分は、終わった後の 1 回にまとめる。
     */
    fun refresh() {
        if (!_state.value.initialized) return
        if (_state.value.page == 0) { load(1, false); return }
        if (refreshJob?.isActive == true) { refreshAgain = true; return }
        _state.update { it.copy(refreshing = true) }
        refreshJob = viewModelScope.launch {
            try {
                var updated = false
                do {
                    refreshAgain = false
                    if (fetchPage(1, force = true, manual = true)) updated = true
                } while (refreshAgain && isActive)
                // 一覧を 1 度も取り込めなかったとき（本家が部屋の表を出さなかったときなど）は、済んだ印を出さない。
                _state.update { if (updated && it.error == null) it.copy(refreshedCount = it.refreshedCount + 1) else it }
            } finally { _state.update { it.copy(refreshing = false) } }
        }
    }
    /** まだ読んでいないページを 1 つ読む。 */
    fun more() {
        if (!_state.value.canLoadMore) return
        // 他の機能から取り込んだページだけがあるときは、1 ページ目が未読のこともある。
        val next = (1..maxOf(window.lastPage, 1)).firstOrNull { it !in window.pages } ?: return
        load(next, false)
    }
    fun automatic(enabled: Boolean) = _state.update { it.copy(automatic = enabled) }
    fun clearNewRooms() = _state.update { it.copy(newRoomIds = emptySet()) }
    fun clearPreferenceError() = _state.update { it.copy(preferenceError = null) }
    fun stopRefresh() { cancelFetches(); _state.update { it.copy(loading = false, refreshing = false) } }
    private fun cancelFetches() { job?.cancel(); refreshJob?.cancel(); refreshAgain = false }

    /**
     * 画面が前面にある間だけ実行。取消はHTTP取得にも伝わる。
     * 最初の周は 1 ページ目から最後のページまで順に読み、その後は直近 1 時間の部屋が載る前のページを周ごとに読み直し、
     * 後ろのページは周ごとに 1 つずつ回る（[RoomPageSchedule]）。
     * 新しい周は前の周の始まりから設定の間隔（[RefreshPacing.searchHeadMs]）が経つまで始めない。
     */
    suspend fun monitor() {
        try {
            while (currentCoroutineContext().isActive && _state.value.automatic) {
                if (!_state.value.initialized) { state.first { it.initialized }; continue }
                job?.join()
                val genre = _state.value.genre
                val schedule = schedule ?: schedule().also { schedule = it }
                val wait = schedule.waitMs(window)
                // 新しい周の始まりを待つ間にジャンルが変わることがあるので、待った後に選び直す。
                if (wait > 0) { delay(wait); continue }
                val page = schedule.next(window)
                val fetched = fetchPage(page, force = false)
                if (fetched && _state.value.error == null && _state.value.genre == genre) schedule.completed(page, window)
                // ページ同士は通信の最小間隔で順に取る（間隔は ShaloveClient も守る）。
                // 取れなかったときは、案内を出さない失敗（部屋の表が無いページ）でも 20 秒空ける。
                delay(if (!fetched || _state.value.error != null) RoomPageSchedule.ERROR_INTERVAL_MS else pacing().sanitized().minIntervalMs)
            }
        } finally { stopRefresh() }
    }

    private fun schedule(): RoomPageSchedule = RoomPageSchedule(clock) { pacing().sanitized().searchHeadMs }

    private fun load(page: Int, force: Boolean) {
        job?.cancel()
        job = viewModelScope.launch { fetchPage(page, force) }
    }
    /**
     * いまのジャンルの一覧の [page] を取る。取り込めたら true。
     * 本家が部屋の表を出さずにページを返したとき（[RoomListUnavailableException]。更新を続けて押したときに起きる）は、
     * 本家のブラウザでもう一度押すのと同じく、案内は出さずに手元の一覧のまま次の取り直しを待つ。
     */
    private suspend fun fetchPage(page: Int, force: Boolean, manual: Boolean = false): Boolean {
        val genre = _state.value.genre
        // 条件はすべて端末で判定するので、本家にはジャンル全体の一覧を取りに行く。
        val query = RoomQuery(genre, page = page)
        inFlight++
        _state.update { it.copy(loading = true, error = null) }
        try {
            val result = if (manual) repository.fetchNow(query) else repository.fetch(query, force)
            currentCoroutineContext().ensureActive()
            // 取得中にジャンルが変わったら、古いジャンルの結果は捨てる。
            if (_state.value.genre == genre) {
                val observation = repository.observation(query)?.takeIf { it.query == query }
                absorb(query, observation?.page ?: result, observation?.revision, observation?.confirmedAt)
            }
            _state.update { it.copy(errorOnMore = false) }
            return true
        } catch (e: CancellationException) { throw e }
        catch (_: RoomListUnavailableException) { return false }
        catch (e: Exception) { _state.update { it.copy(error = describeError(e), errorOnMore = page > 1) }; return false }
        finally {
            inFlight--
            _state.update { it.copy(loading = inFlight > 0) }
        }
    }

    /** 他の機能（レーダーなど）が読んだ、いまのジャンルの一覧のページを取り込む。 */
    private fun seedShared() {
        val source = repository as? ObservedRoomListSource ?: return
        absorbShared(source.observations.value.values)
    }
    private fun absorbShared(observed: Collection<ObservedRoomPage>) {
        val genre = _state.value.genre
        val oldest = now() - SHARED_MAX_AGE_MS
        observed.filter { it.query == RoomQuery(genre, page = it.query.page) && it.confirmedAt >= oldest }
            .sortedBy { it.revision }
            .forEach { absorb(it.query, it.page, it.revision, it.confirmedAt) }
    }

    /** 読んだページを一覧に反映する。同じ観測を 2 度反映しない（[RoomPageWindow.observe]）。 */
    private fun absorb(query: RoomQuery, page: RoomListPage, revision: Long?, confirmedAt: Long?) {
        // 初めて読んだページの部屋は、前からあった部屋なので新着にしない。新着は読んだことのあるページの取り直しで数える。
        val firstSight = page.page !in window.pages
        val previous = window.rooms.map(::roomIdentity).toSet()
        val next = window.observe(page, revision)
        if (next === window) return
        window = next
        val rooms = next.rooms
        val ids = rooms.map(::roomIdentity).toSet()
        _state.update { it.copy(rooms = rooms, page = next.pages.size, lastPage = next.lastPage,
            newRoomIds = (it.newRoomIds + if (firstSight) emptySet() else ids - previous).intersect(ids),
            pageTimes = (it.pageTimes + (confirmedAt?.let { at -> mapOf(query to at) } ?: emptyMap()))
                .filterKeys { q -> q.page <= next.lastPage }) }
    }

    companion object {
        /** 他の機能が読んだページを取り込むのは、確認からこの時間（ミリ秒）以内のものだけ。古い一覧で閉じた部屋を出さない。 */
        const val SHARED_MAX_AGE_MS = 5 * 60_000L
    }
}
