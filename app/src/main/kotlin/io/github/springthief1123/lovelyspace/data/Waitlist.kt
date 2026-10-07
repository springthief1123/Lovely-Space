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
) {
    val key: String get() = roomIdentity(room)
    fun active(now: Long): Boolean = status == WaitlistStatus.WATCHING && now < expiresAt
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

    /** 満室の部屋を登録する。同じ部屋の古い登録は置き換える。 */
    suspend fun register(room: Room, sourceQuery: RoomQuery? = null, hours: Int = DEFAULT_HOURS) = mutex.withLock {
        require(room.status == RoomStatus.FULL) { "満室の部屋だけ順番待ちに登録できます。" }
        val genre = requireNotNull(Genres[room.genreKey]) { "ジャンルを確認できませんでした。" }
        val at = now()
        val others = _entries.value.filterNot { it.key == roomIdentity(room) }
        require(others.count { it.active(at) } < MAX_ACTIVE) { "順番待ちは同時に${MAX_ACTIVE}件までです。" }
        val query = sourceQuery?.takeIf { it.genre.key == room.genreKey } ?: RoomQuery(genre)
        save(listOf(WaitlistEntry(room, query, at, at + hours * HOUR_MS, lastSeenAt = at)) + others)
    }

    suspend fun remove(key: String) = mutex.withLock {
        save(_entries.value.filterNot { it.key == key })
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
        // 前回、空きを保存した後に通知を出す前に止まっていれば、ここで出し直す。
        notify(_entries.value.filter { it.status == WaitlistStatus.OPENED && !it.noticed })
        val queries = mutex.withLock { _entries.value.filter { it.active(now()) }.map { it.query } }
        if (queries.isEmpty()) return
        sync.sync(queries, maxPages, force = false, onResult = { query, outcome ->
            if (outcome is ListSyncOutcome.Fetched) lists.observation(query)?.let { apply(it) }
        })
        expire()
    }

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
                    // 記録したページに無いだけでは閉鎖と判断せず、次は次のページ（最後なら 1 ページ目）を確認する。
                    if (o.query != entry.query) return@map entry
                    changed = true
                    return@map entry.copy(query = entry.query.copy(page = if (o.page.hasNextPage) o.page.page + 1 else 1), missed = entry.missed + 1)
                }
                changed = true
                when {
                    roomIdentityEvidence(entry.room, observed) == RoomIdentityEvidence.REUSED ->
                        entry.copy(status = WaitlistStatus.STOPPED, changedAt = o.confirmedAt, lastSeenAt = o.confirmedAt, query = o.query, missed = 0)
                    observed.status == RoomStatus.FULL -> entry.copy(lastSeenAt = o.confirmedAt, query = o.query, missed = 0)
                    else -> entry.copy(status = WaitlistStatus.OPENED, openedRoom = observed, changedAt = o.confirmedAt,
                        lastSeenAt = o.confirmedAt, query = o.query, missed = 0).also { opened += it }
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
        /** 背景の 1 回の実行で順番待ちのために取得するページ数の上限。 */
        const val MAX_PAGES = 3
        private const val HOUR_MS = 60 * 60 * 1000L
    }
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
        .put("openedRoom", e.openedRoom?.let(::waitlistRoomJson)).put("noticed", e.noticed)

    private fun decode(o: JSONObject): WaitlistEntry {
        val room = readWaitlistRoom(o.getJSONObject("room"))
        fun long(key: String) = if (o.isNull(key)) null else o.getLong(key)
        return WaitlistEntry(
            room = room,
            query = readRadarQuery(o.optJSONObject("query")) ?: RoomQuery(requireNotNull(Genres[room.genreKey])),
            registeredAt = o.getLong("registeredAt"),
            expiresAt = o.getLong("expiresAt"),
            status = WaitlistStatus.valueOf(o.getString("status")),
            lastSeenAt = long("lastSeenAt"),
            missed = o.optInt("missed"),
            openedRoom = o.optJSONObject("openedRoom")?.let(::readWaitlistRoom),
            changedAt = long("changedAt"),
            noticed = o.optBoolean("noticed"),
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
