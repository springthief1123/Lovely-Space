package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

enum class WaitlistStatus {
    /** 満室のまま。空くのを待っている。 */
    WATCHING,
    /** 空きを確認して知らせた。 */
    OPENED,
    /** 同じ ID に別の部屋（名前・性別・年齢が登録時と違う）を確認したので止めた。 */
    STOPPED,
    /** 期限を過ぎた。 */
    EXPIRED,
}

/**
 * 順番待ちの 1 件。部屋は `host + room_id`（[key]）で見分け、[room] に登録時の一覧の情報を残して照合に使う。
 * [query] は次に確認する一覧のページ。見つからなければ次のページへ進め、見つからないことを閉鎖とは判断しない。
 */
data class WaitlistEntry(
    val room: Room,
    val query: RoomQuery,
    val registeredAt: Long,
    val expiresAt: Long,
    val status: WaitlistStatus = WaitlistStatus.WATCHING,
    /** 一覧で最後に確認した時刻。 */
    val lastSeenAt: Long? = null,
    /** 続けて見つからなかった回数。 */
    val missed: Int = 0,
    /** 空いたときの一覧の情報。 */
    val openedRoom: Room? = null,
    val changedAt: Long? = null,
    /** 空きを通知し終えたか。通知の途中で止まっても、次の確認で出し直す。 */
    val noticed: Boolean = false,
    /** 最後にその部屋が見えたページ。見つからないときはここから前後へ広げて探す。 */
    val anchorPage: Int = query.page,
) {
    val key: String get() = roomIdentity(room)
    fun active(now: Long): Boolean = status == WaitlistStatus.WATCHING && now < expiresAt
    val unnoticed: Boolean get() = status == WaitlistStatus.OPENED && !noticed
}

interface WaitlistPersistence {
    fun load(): List<WaitlistEntry>
    fun save(entries: List<WaitlistEntry>)
}

/**
 * 満室の部屋に空きが出たら知らせる順番待ち。一覧の取得結果（[lists] の観測）で「満室 → 待機中」への変化を見つけ、[onOpened] を呼ぶ。
 * 入室とロボット確認は自動では行わず、通知から入力済みの入室画面を開くところまでにする。
 * 一覧は前面の画面・レーダーと同じ [lists]（＝ `ShaloveClient`）を通し、背景では [check] で待っている部屋のページだけを取得する。
 */
class WaitlistRepository(
    private val persistence: WaitlistPersistence,
    private val lists: ObservedRoomListSource,
    private val onOpened: suspend (WaitlistEntry) -> Unit,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val sync = ListSync(lists)
    private val seen = mutableMapOf<RoomQuery, Long>()
    private val _entries = MutableStateFlow(runCatching { persistence.load() }.getOrNull().orEmpty())
    val entries: StateFlow<List<WaitlistEntry>> = _entries.asStateFlow()

    init {
        // 一覧画面・レーダーが取得したページにも待っている部屋があれば、その場で反映する。
        scope.launch { lists.observations.collect { all -> all.values.sortedBy { it.revision }.forEach { apply(it) } } }
    }

    fun hasActive(): Boolean = _entries.value.any { it.active(now()) }

    /**
     * 背景の実行が必要か。待っている部屋に加え、空きを保存したが通知を出し終えていない登録も数える
     * （通知の前に止まったとき、周期実行が解除されて出し直せなくなるのを防ぐ）。
     */
    fun hasPendingWork(): Boolean = hasActive() || _entries.value.any { it.unnoticed }

    /** 満室の部屋を登録する。同じ部屋の古い登録は置き換える。 */
    suspend fun register(room: Room, sourceQuery: RoomQuery? = null, hours: Int = DEFAULT_HOURS) = mutex.withLock {
        require(room.status == RoomStatus.FULL) { "満室の部屋だけ順番待ちに登録できます。" }
        val genre = requireNotNull(Genres[room.genreKey]) { "ジャンルを確認できませんでした。" }
        val at = now()
        val others = _entries.value.filterNot { it.key == roomIdentity(room) }
        require(others.count { it.active(at) } < MAX_ACTIVE) { "順番待ちは同時に${MAX_ACTIVE}件までです。" }
        val query = startQuery(room, sourceQuery) ?: RoomQuery(genre)
        save(listOf(WaitlistEntry(room, query, at, at + hours * HOUR_MS, lastSeenAt = at)) + others)
    }

    /**
     * 最初に確認するページ。呼び出し元のページにその部屋が見えていればそこから、
     * そうでなければ取得済みの一覧でその部屋が見えた最新のページから始める（後ろのページの部屋を 1 ページ目から探して期限が切れないように）。
     */
    private fun startQuery(room: Room, sourceQuery: RoomQuery?): RoomQuery? {
        val key = roomIdentity(room)
        fun ObservedRoomPage.shows() = query.genre.key == room.genreKey && page.rooms.any { roomIdentity(it) == key }
        val source = sourceQuery?.takeIf { it.genre.key == room.genreKey }
        if (source != null && lists.observation(source)?.shows() == true) return source
        return lists.observations.value.values.filter { it.shows() }.maxByOrNull { it.revision }?.query ?: source
    }

    suspend fun remove(key: String) = mutex.withLock {
        save(_entries.value.filterNot { it.key == key })
    }

    /**
     * 取り消した登録 [entry] を「元に戻す」。元の位置 [index] に戻し、期限や確認の状態はそのまま引き継ぐ。
     * その間に同じ部屋を登録し直していれば、新しい登録を残す。
     */
    suspend fun restore(entry: WaitlistEntry, index: Int) = mutex.withLock {
        val current = _entries.value
        if (current.any { it.key == entry.key }) return@withLock
        require(!entry.active(now()) || current.count { it.active(now()) } < MAX_ACTIVE) { "順番待ちは同時に${MAX_ACTIVE}件までです。" }
        save(current.toMutableList().apply { add(index.coerceIn(0, size), entry) })
    }

    /** 期限を過ぎた登録を期限切れにする。 */
    suspend fun expire() = mutex.withLock {
        val at = now()
        if (_entries.value.none { it.status == WaitlistStatus.WATCHING && at >= it.expiresAt }) return@withLock
        save(_entries.value.map { if (it.status == WaitlistStatus.WATCHING && at >= it.expiresAt) it.copy(status = WaitlistStatus.EXPIRED, changedAt = at) else it })
    }

    /** 背景の実行から呼ぶ。待っている部屋のページを [maxPages] まで取得して反映する。 */
    suspend fun check(maxPages: Int) {
        expire()
        deliverPending()
        val queries = mutex.withLock { _entries.value.filter { it.active(now()) }.map { it.query } }
        if (queries.isEmpty()) return
        sync.sync(queries, maxPages, force = false, onResult = { query, outcome ->
            if (outcome is ListSyncOutcome.Fetched) lists.observation(query)?.let { apply(it) }
        })
        expire()
    }

    /** 前回、空きを保存した後に通知を出す前に止まっていれば、ここで出し直す。通信はしない。 */
    suspend fun deliverPending() = notify(_entries.value.filter { it.unnoticed })

    private suspend fun apply(o: ObservedRoomPage) {
        val opened = mutableListOf<WaitlistEntry>()
        mutex.withLock {
            if ((seen[o.query] ?: 0) >= o.revision) return
            seen[o.query] = o.revision
            val at = now()
            var changed = false
            val next = _entries.value.map { entry ->
                if (!entry.active(at) || entry.room.genreKey != o.query.genre.key) return@map entry
                val observed = o.page.rooms.firstOrNull { roomIdentity(it) == entry.key }
                if (observed == null) {
                    // 記録したページに無いだけでは閉鎖と判断しない。一覧の増減で前後へずれることがあるので、
                    // 最後に見えたページから前後へ交互に広げて探す（全ページを回ったら最初から）。
                    if (o.query != entry.query) return@map entry
                    changed = true
                    val missed = entry.missed + 1
                    return@map entry.copy(query = entry.query.copy(page = searchPage(entry.anchorPage, missed, o.page.lastPage)), missed = missed)
                }
                changed = true
                when {
                    roomIdentityEvidence(entry.room, observed) == RoomIdentityEvidence.REUSED ->
                        entry.copy(status = WaitlistStatus.STOPPED, changedAt = o.confirmedAt, lastSeenAt = o.confirmedAt, query = o.query, missed = 0, anchorPage = o.query.page)
                    observed.status == RoomStatus.FULL -> entry.copy(lastSeenAt = o.confirmedAt, query = o.query, missed = 0, anchorPage = o.query.page)
                    else -> entry.copy(status = WaitlistStatus.OPENED, openedRoom = observed, changedAt = o.confirmedAt,
                        lastSeenAt = o.confirmedAt, query = o.query, missed = 0, anchorPage = o.query.page).also { opened += it }
                }
            }
            if (changed) save(next)
        }
        notify(opened)
    }

    private suspend fun notify(entries: List<WaitlistEntry>) {
        val done = entries.filter { entry ->
            try { onOpened(entry); true }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { false }
        }.map { it.key }.toSet()
        if (done.isEmpty()) return
        mutex.withLock { save(_entries.value.map { if (it.key in done && it.status == WaitlistStatus.OPENED) it.copy(noticed = true) else it }) }
    }

    private fun save(entries: List<WaitlistEntry>) {
        _entries.value = entries
        runCatching { persistence.save(entries) }
    }

    companion object {
        /** 同時に待てる部屋の数。 */
        const val MAX_ACTIVE = 5
        /** 登録から期限までの時間。 */
        const val DEFAULT_HOURS = 3
        private const val HOUR_MS = 60 * 60 * 1000L
    }
}

/**
 * 見つからなかった [missed] 回目に確認するページ。[anchor] から 1 つ前・1 つ後・2 つ前…と交互に広げ、
 * 1〜[lastPage] の外は飛ばす。全ページを回ったら [anchor] からやり直す。
 */
internal fun searchPage(anchor: Int, missed: Int, lastPage: Int): Int {
    val last = maxOf(lastPage, 1)
    val start = anchor.coerceIn(1, last)
    val order = buildList {
        add(start)
        for (d in 1 until last) {
            if (start - d >= 1) add(start - d)
            if (start + d <= last) add(start + d)
        }
    }
    return order[missed.mod(order.size)]
}

/** 順番待ちを SharedPreferences に JSON で残す。読めない記録は捨てる。 */
class WaitlistStore(private val prefs: android.content.SharedPreferences) : WaitlistPersistence {
    override fun load(): List<WaitlistEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { runCatching { decode(array.getJSONObject(it)) }.getOrNull() }
        }.getOrElse {
            prefs.edit().remove(KEY).apply()
            emptyList()
        }
    }

    override fun save(entries: List<WaitlistEntry>) {
        prefs.edit().putString(KEY, JSONArray(entries.map(::encode)).toString()).apply()
    }

    private fun encode(e: WaitlistEntry) = JSONObject()
        .put("room", waitlistRoomJson(e.room)).put("query", radarQueryJson(e.query))
        .put("registeredAt", e.registeredAt).put("expiresAt", e.expiresAt).put("status", e.status.name)
        .put("lastSeenAt", e.lastSeenAt).put("missed", e.missed).put("changedAt", e.changedAt)
        .put("openedRoom", e.openedRoom?.let(::waitlistRoomJson)).put("noticed", e.noticed).put("anchorPage", e.anchorPage)

    private fun decode(o: JSONObject): WaitlistEntry {
        val room = readWaitlistRoom(o.getJSONObject("room"))
        fun long(key: String) = if (o.isNull(key)) null else o.getLong(key)
        val query = readRadarQuery(o.optJSONObject("query")) ?: RoomQuery(requireNotNull(Genres[room.genreKey]))
        return WaitlistEntry(
            room = room,
            query = query,
            registeredAt = o.getLong("registeredAt"),
            expiresAt = o.getLong("expiresAt"),
            status = WaitlistStatus.valueOf(o.getString("status")),
            lastSeenAt = long("lastSeenAt"),
            missed = o.optInt("missed"),
            openedRoom = o.optJSONObject("openedRoom")?.let(::readWaitlistRoom),
            changedAt = long("changedAt"),
            noticed = o.optBoolean("noticed"),
            anchorPage = o.optInt("anchorPage", query.page),
        )
    }

    companion object {
        const val PREFS = "waitlist"
        private const val KEY = "entries"
    }
}

private fun waitlistRoomJson(r: Room) = JSONObject().put("id", r.id).put("genre", r.genreKey).put("status", r.status.name).put("action", r.action.name)
    .put("name", r.name).put("gender", r.gender.name).put("age", r.age).put("area", r.area).put("message", r.message)

private fun readWaitlistRoom(j: JSONObject) = Room(j.getLong("id"), j.getString("genre"), RoomStatus.valueOf(j.getString("status")), RoomAction.valueOf(j.getString("action")), null,
    if (j.isNull("name")) null else j.getString("name"), Gender.valueOf(j.getString("gender")), if (j.isNull("age")) null else j.getInt("age"),
    if (j.isNull("area")) null else j.getString("area"), j.getString("message"))
