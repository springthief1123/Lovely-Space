package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface RoomListSource {
    suspend fun fetch(query: RoomQuery, force: Boolean): RoomListPage
    fun observation(query: RoomQuery): ObservedRoomPage? = null
    /** 利用者が更新ボタンを押した取り直し（本家の「最新更新」）。押すたびに、前の通信から間隔を空けずに取り直す（[ShaloveClient.fetchRoomList] の manual）。 */
    suspend fun fetchNow(query: RoomQuery): RoomListPage = fetch(query, true)
    /** 新着の確認（1 ページ目）と順番待ちのページ。設定の最小間隔は空けるが、後ろのページの巡回より先に通す（[ShaloveClient.fetchRoomList] の priority）。 */
    suspend fun fetchPriority(query: RoomQuery, force: Boolean): RoomListPage = fetch(query, force)
}

data class ObservedRoomPage(val query: RoomQuery, val page: RoomListPage, val confirmedAt: Long, val revision: Long)

/** 手元にある [genre] のジャンル全体の一覧（条件を付けずに読んだページを古い順に重ねたもの）。 */
fun Map<RoomQuery, ObservedRoomPage>.genreWindow(genre: Genre): RoomPageWindow = values
    .filter { it.query == RoomQuery(genre, page = it.query.page) }
    .sortedBy { it.revision }
    .fold(RoomPageWindow()) { w, observed -> w.observe(observed.page, observed.revision) }

interface ObservedRoomListSource : RoomListSource {
    val observations: kotlinx.coroutines.flow.StateFlow<Map<RoomQuery, ObservedRoomPage>>
}

/**
 * 名前が見えていた部屋の姿（[RoomListRepository.lastNamed]）の保存先。満室・会話中になって一覧から名前が消えた後や、
 * アプリを起動し直した後も、部屋主の名前・性別・年齢を補えるようにする。端末の中だけに残す。
 */
interface NamedRoomPersistence {
    fun load(): List<NamedRoom>
    fun save(rooms: List<NamedRoom>)
}

/** 名前が見えていた部屋の姿と、最後に見た時刻。 */
data class NamedRoom(val room: Room, val seenAt: Long)

/**
 * 名前の見えない [room]（満室・会話中）に、同じ部屋の名前が見えていた姿 [named] から名前・性別・年齢・地域を補う。
 * 部屋の番号は使い回されるので、待機メッセージが同じで、名前・性別・年齢が食い違わないときだけ補う。補えなければ null。
 */
fun borrowNamed(room: Room, named: Room?): Room? {
    if (room.name != null || named?.name == null || roomIdentity(named) != roomIdentity(room)) return null
    if (room.message.isBlank() || named.message != room.message || roomIdentityEvidence(room, named) == RoomIdentityEvidence.REUSED) return null
    return room.copy(
        name = named.name,
        gender = if (room.gender == Gender.UNKNOWN) named.gender else room.gender,
        age = room.age ?: named.age,
        area = room.area ?: named.area,
    )
}

/** 発見・巡回・追跡が同じ通信結果を使う。キャッシュ再利用を新しい観測とは数えない。 */
class RoomListRepository(
    private val client: ShaloveClient,
    private val namedStore: NamedRoomPersistence? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
) : ObservedRoomListSource {
    private val mutex = Mutex()
    private val _observations = MutableStateFlow<Map<RoomQuery, ObservedRoomPage>>(emptyMap())
    override val observations = _observations.asStateFlow()
    private var revision = 0L
    private val attempts = java.util.concurrent.atomic.AtomicLong()
    /** 一覧の取得を試みた回数（失敗・キャッシュ再利用も含む）。背景の 1 回の実行で取得したページ数の上限を、機能をまたいで守るのに使う。 */
    val fetchAttempts: Long get() = attempts.get()
    override fun observation(query: RoomQuery) = _observations.value[query]

    /**
     * 名前が見えていた最後の姿（部屋ごと、新しく見た [MAX_NAMED] 部屋まで）。満室になると一覧から名前・性別が消え、
     * 一覧はページごとに最新の取得結果しか持たないので、入室されたばかりの部屋を覗いたときやピンの表示などで補うのに使う。
     * [namedStore] があれば端末に残し（[NAMED_KEEP_MS] まで）、起動し直しても使えるようにする。
     */
    private val named = object : LinkedHashMap<String, NamedRoom>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NamedRoom>?) = size > MAX_NAMED
    }
    private val _namedRevision = MutableStateFlow(0L)
    /** 名前が見えていた姿が増えた・変わったときに進む。補った表示を作り直すのに使う。 */
    val namedRevision: StateFlow<Long> = _namedRevision.asStateFlow()
    private val saveScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        namedStore?.let { store ->
            scope.launch {
                val loaded = runCatching { store.load() }.getOrNull().orEmpty()
                val cutoff = now() - NAMED_KEEP_MS
                synchronized(named) {
                    // 古いものから入れて、新しく見たものほど後ろ（消されにくい側）に並べる。読み込む前にこの起動で見た部屋はその後ろに置き直す。
                    val live = named.values.toList()
                    named.clear()
                    loaded.filter { it.seenAt >= cutoff && it.room.name != null }.sortedBy { it.seenAt }.forEach { named[roomIdentity(it.room)] = it }
                    live.forEach { named[roomIdentity(it.room)] = it }
                }
                _namedRevision.update { it + 1 }
            }
        }
    }

    fun lastNamed(genreKey: String, roomId: Long): Room? =
        synchronized(named) { named["${Genres[genreKey]?.host ?: genreKey}/$roomId"]?.room }

    /** [room] と同じ部屋の、名前が見えていた最後の姿。 */
    fun lastNamed(room: Room): Room? = synchronized(named) { named[roomIdentity(room)]?.room }

    private fun rememberNamed(rooms: List<Room>) {
        val seen = rooms.filter { it.name != null }
        if (seen.isEmpty()) return
        val at = now()
        val changed = synchronized(named) {
            seen.count { room ->
                val key = roomIdentity(room)
                val before = named[key]?.room
                named[key] = NamedRoom(room, at)
                before == null || before.name != room.name || before.gender != room.gender || before.age != room.age || before.message != room.message
            }
        }
        if (changed > 0) _namedRevision.update { it + 1 }
        scheduleSave()
    }

    /** 端末への書き出しは、続けて読んだページをまとめて [SAVE_DELAY_MS] ごとに 1 回にする。 */
    private fun scheduleSave() {
        val store = namedStore ?: return
        if (!saveScheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(SAVE_DELAY_MS)
            saveScheduled.set(false)
            val snapshot = synchronized(named) { named.values.toList() }
            runCatching { store.save(snapshot) }
        }
    }

    override suspend fun fetch(query: RoomQuery, force: Boolean): RoomListPage = record(query) { client.fetchRoomList(query, forceRefresh = force) }

    override suspend fun fetchNow(query: RoomQuery): RoomListPage = record(query) { client.fetchRoomList(query, manual = true) }

    override suspend fun fetchPriority(query: RoomQuery, force: Boolean): RoomListPage =
        record(query) { client.fetchRoomList(query, forceRefresh = force, priority = true) }

    private suspend fun record(query: RoomQuery, load: suspend () -> RoomListPage): RoomListPage {
        attempts.incrementAndGet()
        val result = load()
        currentCoroutineContext().ensureActive()
        mutex.withLock {
            val previous = observation(query)
            if (previous?.page !== result) {
                // キャッシュを再利用した結果は新しい観測ではないので、名前の記録を古い姿へ戻さない。
                rememberNamed(result.rooms)
                _observations.value = _observations.value + (query to ObservedRoomPage(query, result, System.currentTimeMillis(), ++revision))
            }
        }
        return result
    }

    companion object {
        const val MAX_NAMED = 2_000
        /** 名前が見えていた姿を端末に残す間。 */
        const val NAMED_KEEP_MS = 14 * 24 * 60 * 60_000L
        private const val SAVE_DELAY_MS = 5_000L
    }
}

/** 名前が見えていた部屋の姿をアプリの内部ファイルに JSON で残す。読めないファイルは捨てる。 */
class NamedRoomFile(private val file: java.io.File) : NamedRoomPersistence {
    private val atomic = android.util.AtomicFile(file)

    override fun load(): List<NamedRoom> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(String(atomic.readFully(), Charsets.UTF_8))
            (0 until array.length()).mapNotNull { runCatching { decode(array.getJSONObject(it)) }.getOrNull() }
        }.getOrElse {
            atomic.delete()
            emptyList()
        }
    }

    override fun save(rooms: List<NamedRoom>) {
        val bytes = JSONArray(rooms.map(::encode)).toString().toByteArray(Charsets.UTF_8)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    private fun encode(entry: NamedRoom): JSONObject {
        val r = entry.room
        return JSONObject().put("id", r.id).put("genre", r.genreKey).put("status", r.status.name).put("action", r.action.name)
            .put("name", r.name).put("gender", r.gender.name).put("age", r.age).put("area", r.area).put("message", r.message)
            .put("seenAt", entry.seenAt)
    }

    private fun decode(o: JSONObject): NamedRoom {
        fun string(key: String) = if (o.isNull(key)) null else o.getString(key)
        val room = Room(
            id = o.getLong("id"),
            genreKey = o.getString("genre"),
            status = RoomStatus.valueOf(o.getString("status")),
            action = RoomAction.valueOf(o.getString("action")),
            elapsed = null,
            name = string("name"),
            gender = Gender.valueOf(o.getString("gender")),
            age = if (o.isNull("age")) null else o.getInt("age"),
            area = string("area"),
            message = o.optString("message"),
        )
        return NamedRoom(room, o.getLong("seenAt"))
    }

    companion object {
        const val FILE = "named_rooms.json"
    }
}
