package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface RoomListSource {
    suspend fun fetch(query: RoomQuery, force: Boolean): RoomListPage
    fun observation(query: RoomQuery): ObservedRoomPage? = null
    /** 利用者が更新ボタンを押した取り直し（本家の「最新更新」）。押すたびに、前の通信から間隔を空けずに取り直す（[ShaloveClient.fetchRoomList] の manual）。 */
    suspend fun fetchNow(query: RoomQuery): RoomListPage = fetch(query, true)
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

/** 発見・巡回・追跡が同じ通信結果を使う。キャッシュ再利用を新しい観測とは数えない。 */
class RoomListRepository(private val client: ShaloveClient) : ObservedRoomListSource {
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
     * 一覧はページごとに最新の取得結果しか持たないので、入室されたばかりの部屋を覗いたときなどに補うのに使う。
     */
    private val named = object : LinkedHashMap<String, Room>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Room>?) = size > MAX_NAMED
    }

    fun lastNamed(genreKey: String, roomId: Long): Room? =
        synchronized(named) { named["${Genres[genreKey]?.host ?: genreKey}/$roomId"] }
    override suspend fun fetch(query: RoomQuery, force: Boolean): RoomListPage = record(query) { client.fetchRoomList(query, forceRefresh = force) }

    override suspend fun fetchNow(query: RoomQuery): RoomListPage = record(query) { client.fetchRoomList(query, manual = true) }

    private suspend fun record(query: RoomQuery, load: suspend () -> RoomListPage): RoomListPage {
        attempts.incrementAndGet()
        val result = load()
        currentCoroutineContext().ensureActive()
        mutex.withLock {
            val previous = observation(query)
            if (previous?.page !== result) {
                // キャッシュを再利用した結果は新しい観測ではないので、名前の記録を古い姿へ戻さない。
                synchronized(named) { result.rooms.forEach { if (it.name != null) named[roomIdentity(it)] = it } }
                _observations.value = _observations.value + (query to ObservedRoomPage(query, result, System.currentTimeMillis(), ++revision))
            }
        }
        return result
    }

    companion object {
        const val MAX_NAMED = 2_000
    }
}
