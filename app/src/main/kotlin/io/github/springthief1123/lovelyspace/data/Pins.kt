package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * ピンした部屋の知らせ方。ピンはどれも空いた・似た部屋が建ったら知らせ、利用者が選ぶのは自動で入室するかだけ（Yuya の決定、2026-11-06）。
 * 以前の「知らせない」（OFF）は読み込むと [NOTIFY] になる。
 */
enum class PinAlert {
    /** 空いた・似た部屋が建ったら知らせる。 */
    NOTIFY,
    /** 空いたら入室画面を自動で開く（入室できたら [NOTIFY] に戻る。歯止めは [AutoEntryController]）。 */
    AUTO,
}

/** 通知前に保存するイベント。再起動・再送でも同じ通知IDと検出時刻を使う。 */
enum class PinNoticeType { OPENED, SIMILAR }

data class PendingPinNotice(
    val id: String,
    val type: PinNoticeType,
    val room: Room,
    val at: Long,
    val registered: Room? = null,
    val similarity: Double = 0.0,
    val autoEnter: Boolean = false,
)

/**
 * ピンの見張りの記録。ピンした部屋そのもの（名前・募集文など）は保存の記録（[RoomPreference.favorite]）にあり、[key]（[roomIdentity]）で結び付ける。
 * 部屋が立て直されて保存の記録が新しい部屋へ移ると（[RoomPreferenceRepository.moved]）、この記録も移す。
 */
data class PinRecord(
    val key: String,
    val alert: PinAlert = PinAlert.NOTIFY,
    val memo: String = "",
    /** 空いた（満室・閉じた → 待機中）時刻。新しい [MAX_VACANCIES] 件まで。 */
    val vacancies: List<Long> = emptyList(),
    /** 立て直しを追って新しい部屋へ移した回数。 */
    val rebuilds: Int = 0,
    /** 似た部屋の候補に「別の人」と答えた部屋（[roomIdentity]）。 */
    val dismissed: Set<String> = emptySet(),
    /** 最後に一覧で確かめた様子（[PinState.UNSEEN] は記録しない）と、その時刻。 */
    val state: PinState? = null,
    val stateAt: Long? = null,
    /** 似た部屋として知らせた部屋。同じ部屋を何度も知らせない。 */
    val suggested: String? = null,
    /** ピンを外したと分かった時刻。[PinRepository.PRUNE_MS] 経ったら記録を消す。ピンし直したら null に戻す。 */
    val removedAt: Long? = null,
    /** 保存済みだが通知処理が完了していない空き・類似部屋イベント。 */
    val pendingNotices: List<PendingPinNotice> = emptyList(),
    /** 最後に一覧で実際に見つけたページ。再起動後も後ろのページから探し直せる。 */
    val lastKnownPage: Int? = null,
    /** 最後に取得したジャンルの総ページ数。 */
    val lastPageCount: Int? = null,
    /** 見失った空きピンの探査順（基準ページ・1つ前・1つ後…）。 */
    val scanMissed: Int = 0,
    /** 次に調べるページが最後に観測された時刻。新しい取得だけで探査を進める。 */
    val scanAt: Long = 0L,
) {
    val autoEnters: Boolean get() = alert == PinAlert.AUTO

    companion object {
        const val MAX_VACANCIES = 200
        const val MAX_MEMO = 500
    }
}

/**
 * レーダーに出すピン 1 件。[room] は一覧で見た最新の姿（満室で隠れた名前などは、ピンしたときの値や一覧で名前が見えていた姿で補う）、
 * [page] はその部屋が載っていたページ、[similar] は閉じた部屋に似た新しい部屋の候補。[listed] は [room] がいま手元の一覧に載っているか。
 */
data class PinView(val preference: RoomPreference, val record: PinRecord, val state: PinState, val room: Room, val page: Int?, val similar: SimilarRoom?,
    val listed: Boolean = false) {
    val key: String get() = record.key
    val name: String get() = room.name ?: preference.identityName ?: preference.snapshotName ?: "名前の見えない部屋"
}

/** ピンの見張りで起きたこと。ピンはどれも知らせる。 */
sealed interface PinEvent {
    val pin: PinView
    /** 通知済みマークの保存に失敗して再送しても、同じ端末通知を更新する。 */
    val noticeId: String? get() = null
    val noticeAt: Long? get() = null

    /** 満室・閉じた部屋が空いた（立て直した部屋が待機中で現れたときも）。[registered] はピンしたときの内容。 */
    data class Opened(
        override val pin: PinView, val registered: Room, val autoEnter: Boolean,
        /** 以前の未配信イベントは再送だけにし、古い空きから自動入室を始めない。 */
        val replayed: Boolean = false,
        override val noticeId: String? = null,
        override val noticeAt: Long? = null,
    ) : PinEvent

    /** 閉じた部屋に似た部屋が建った。 */
    data class Similar(
        override val pin: PinView, val similar: SimilarRoom,
        override val noticeId: String? = null,
        override val noticeAt: Long? = null,
    ) : PinEvent
}

interface PinPersistence {
    fun load(): Map<String, PinRecord>
    fun save(records: Map<String, PinRecord>)
}

/** 保存の記録で、照合に使う名前・性別・年齢（満室の一覧で隠れた後も残る値）を戻した部屋。 */
internal fun RoomPreference.identityRoom(): Room {
    val snapshot = toRoom()
    return snapshot.copy(name = identityName ?: snapshot.name,
        gender = identityGender?.let(Gender::valueOf) ?: snapshot.gender, age = identityAge ?: snapshot.age)
}

/**
 * 前回の一覧（[previous]、まだ無ければ null）から増えたピン。立て直し・似た部屋への引き継ぎは 1 件外れて 1 件増えるので、
 * 外れた数より多く増えたときだけ、利用者が新しくピンしたとみなす。
 */
internal fun newlyPinnedKeys(previous: Set<String>?, current: Set<String>): Set<String> {
    if (previous == null) return emptySet()
    val added = current - previous
    return if (added.size > (previous - current).size) added else emptySet()
}

private fun RoomPreference.pinKey(): String = "$host/$roomId"

/**
 * ピンした部屋の見張り。保存（[RoomPreference.favorite]）した部屋をピンとして、どの画面・背景の確認が読んだ一覧（[lists]）からも
 * 空き・満室・閉じたを追い、空いた時刻を残す。知らせるピンは空いたとき・閉じた部屋に似た部屋が建ったときに [onEvent] を呼ぶ。
 * 同じ名前・募集文の立て直しは保存の記録ごと新しい部屋へ移り（[RoomPreferenceRepository.followRebuilds]）、ここでは記録を引き継ぐ。
 * 通信はしない。満室のピンの空きを早く知るための確認は、順番待ち（[WaitlistRepository]）に頼む（[fastWatch]）。
 * 満室でピンした部屋など名前の記録がないピンは、一覧で名前が見えていた姿（[named]、[RoomListRepository.lastNamed]）から補い、
 * 保存の記録の照合用の値（[RoomPreferenceStore.rememberIdentity]）にも残して、立て直しの照合に使えるようにする。
 */
class PinRepository(
    private val persistence: PinPersistence,
    private val preferences: RoomPreferenceStore,
    private val lists: ObservedRoomListSource,
    private val onEvent: suspend (PinEvent) -> Unit,
    moved: Flow<PendingPinMove> = emptyFlow(),
    private val named: (Room) -> Room? = { null },
    namedRevision: Flow<Long> = flowOf(0L),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    /** 新規イベントを記録した処理より先に、別の観測が未配信扱いで再送しないようにする。 */
    private val processMutex = Mutex()
    /** 変化イベントを順に処理し終えた最新の観測。途中の「空き」を StateFlow の conflation で落とさない。 */
    private val processedRevision = MutableStateFlow(0L)
    /** 順序付きイベントを持つ一覧では、他の更新も最後に処理済みの観測だけを使う。 */
    private var chronologicalPages: Map<RoomQuery, ObservedRoomPage> = emptyMap()
    /** 通知コールバック中は状態のmutexを保持しない。再送同士だけを直列化する。 */
    private val noticeMutex = Mutex()
    /** 類似部屋の引き継ぎ種別は DB の永続イベントから判断する（再起動後も失われない）。 */
    /** ページ番号と探査位置は PinRecord に永続化し、プロセス終了後も復元する。 */
    private val _records = MutableStateFlow(runCatching { persistence.load() }.getOrNull().orEmpty())
    val records: StateFlow<Map<String, PinRecord>> = _records.asStateFlow()

    private val favorites: StateFlow<List<RoomPreference>?> = preferences.preferences
        .map { all -> all.filter { it.favorite } }
        .catch { emit(emptyList()) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _pins = MutableStateFlow<List<PinView>>(emptyList())
    /** ピンの一覧。空いている部屋・似た部屋の候補がある部屋を先に、次に満室、閉じた部屋の順。 */
    val pins: StateFlow<List<PinView>> = _pins.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
    /** 前回の一覧に無かったピン（key）。読み込み直後の 1 回目と、立て直し・似た部屋への引き継ぎ（外れたピンと同じ数だけ増えた）は含めない。 */
    private var processedKeys: Set<String>? = null
    private val _added = MutableSharedFlow<Set<String>>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** 新しくピンした部屋。ボトムナビのピンのアイコンを刺すように動かすのに使う（[newlyPinnedKeys]）。 */
    val added: SharedFlow<Set<String>> = _added.asSharedFlow()

    init {
        val changes = lists.changes
        if (changes != null) {
            // 観測の中間状態を捨てずに処理する。満室→空き→満室が一度に来ても空きを通知する。
            scope.launch {
                changes.collect { change ->
                    while (true) {
                        try {
                            process(favorites.filterNotNull().first(), change.pages, ordered = true)
                            processedRevision.value = maxOf(processedRevision.value, change.observation.revision)
                            break
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { delay(500) } // 保存できるまで同じ観測を再試行する。
                    }
                }
            }
            scope.launch {
                combine(favorites.filterNotNull(), _records, namedRevision) { favs, _, _ -> favs }
                    .conflate().collect { favs ->
                        try { process(favs, emptyMap()) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { }
                    }
            }
        } else {
            // テスト用など、順序付きイベントを提供しない Source の互換経路。
            scope.launch {
                combine(favorites.filterNotNull(), lists.observations, _records, namedRevision) { favs, obs, _, _ -> favs to obs }
                    .conflate().collect { (favs, obs) ->
                        try { process(favs, obs) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                    }
            }
        }
        scope.launch {
            // 購読自体が（DB クローズと競合するなどして）例外で終わっても未処理例外として漏らさず、張り直す。
            while (true) {
                try {
                    moved.collect { pending ->
                        // 前回の保存成功・確認済み書き込み前に終了した場合も再実行できる（move は冪等）。
                        while (true) {
                            try {
                                move(pending.from, pending.to, pending.kind)
                                preferences.acknowledgePinMove(pending.id)
                                break
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { delay(500) }
                        }
                    }
                    break
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { delay(500) }
            }
        }
    }

    /** ピンのジャンル。1 ページ目を新着の確認で読み、立て直された部屋にすぐ気づく。 */
    val genres: Flow<Set<Genre>> = pins.map { list -> list.mapNotNullTo(HashSet()) { Genres[it.room.genreKey] } }

    /**
     * 空きを早く確かめたい満室のピン。ピンは入りたい部屋なので、知らせ方に関係なくすべて含める（Yuya の決定、2026-11-06）。
     * 順番待ちに登録して、部屋のページを設定の「空き枠の確認」の間隔で読む（同じページのピンは 1 回の取得にまとまる）。
     * まだ一覧で確かめていないピンも、ピンしたときに満室だったものは含める（起動直後に以前の順番待ちの登録を外さない）。
     */
    val fastWatch: Flow<List<PinView>> = pins.map { list ->
        list.filter { it.state == PinState.FULL || it.state == PinState.UNSEEN && it.room.status == RoomStatus.FULL }
    }

    /**
     * 空いているピンの載るページ。空いた部屋はすぐ埋まる・閉じるので、満室のピン（[fastWatch]）と同じ間隔で読み直す
     * （[WaitlistRepository.extraQueries]）。読み直さないと、閉じた部屋が「空いています」のまま残る（2026-11-06 Yuya の報告）。
     */
    fun openPages(): List<RoomQuery> = _pins.value
        // 一覧から消えて閉じたと決めきれないピン（未確認）も、空いていたなら探し続ける。
        .filter { it.state == PinState.OPEN || it.state == PinState.UNSEEN && it.record.state == PinState.OPEN }
        .flatMap { pin ->
            val genre = Genres[pin.room.genreKey] ?: return@flatMap emptyList()
            val last = maxOf(pin.record.lastPageCount ?: 1, 1)
            val anchor = (pin.page ?: pin.record.lastKnownPage ?: 1).coerceIn(1, last)
            val checked = if (pin.state == PinState.OPEN) {
                // ふだんは載っているページと押し出された先を確認する。
                listOf(anchor, anchor + 1).filter { it <= last }
            } else {
                // 見失ったら、起点から前後へ1ページずつ広げて全ページを巡る。
                listOf(anchor, searchPage(anchor, pin.record.scanMissed, last))
            }
            checked.distinct().map { RoomQuery(genre, page = it) }
        }.distinct()

    /** いま手元にある一覧で見張りを進め、起きたことを知らせ終えるまで待つ（背景の実行が終わる前に呼ぶ）。 */
    suspend fun refresh() {
        if (lists.changes != null) {
            // Worker が終わる前に今回の全観測の通知を確定する。追いつけなければ Worker 側に再試行させる。
            val target = lists.observations.value.values.maxOfOrNull { it.revision } ?: 0L
            withTimeout(10_000L) { processedRevision.first { it >= target } }
        }
        process(favorites.filterNotNull().first(), lists.observations.value)
    }

    fun record(key: String): PinRecord = _records.value[key] ?: PinRecord(key)

    /** [room] をピンし、知らせ方を [alert] にする。 */
    suspend fun pin(room: Room, alert: PinAlert = PinAlert.NOTIFY) {
        preferences.setFavorite(room, true)
        setAlert(roomIdentity(room), alert)
    }

    /** 知らせ方を変える。知らせるピンの数に上限はない（満室のピンはすべて空きを確かめるため。[fastWatch]）。 */
    suspend fun setAlert(key: String, alert: PinAlert) = mutex.withLock {
        val record = _records.value[key] ?: PinRecord(key)
        save(_records.value + (key to record.copy(alert = alert)))
    }

    /**
     * [genreKey] の部屋 [roomId] に入室できたとき。その部屋の「自動で入室」のピンを「空いたら知らせる」に戻す。
     * 自動で開いた入室画面からでなく手で入室したときも戻し、退室・追い出しで部屋がまた空いても自動で入り直さない（Yuya の決定、2026-11-05）。
     */
    suspend fun finishAutoEntry(genreKey: String, roomId: Long) = mutex.withLock {
        val keys = _pins.value.filter { it.room.genreKey == genreKey && it.room.id == roomId }.map { it.key }
            .filter { _records.value[it]?.alert == PinAlert.AUTO }
        if (keys.isNotEmpty()) save(_records.value + keys.associateWith { _records.value.getValue(it).copy(alert = PinAlert.NOTIFY) })
    }

    suspend fun setMemo(key: String, memo: String) = edit(key) { it.copy(memo = memo.trim().take(PinRecord.MAX_MEMO)) }

    /** 似た部屋 [similar] を同じ人の部屋として、ピンを移す。 */
    suspend fun adopt(pin: PinView, similar: Room) {
        preferences.moveFavorite(pin.preference, similar)
    }

    /** 似た部屋 [roomKey] を別の人の部屋として、以後候補にしない。 */
    suspend fun dismiss(key: String, roomKey: String) = edit(key) { it.copy(dismissed = it.dismissed + roomKey) }

    /** ピンを外す。見張りの記録は「元に戻す」のために残し、[PRUNE_MS] 後に消す。 */
    suspend fun unpin(pin: PinView) {
        preferences.clearFavorite(pin.preference.host, pin.preference.roomId)
    }

    private suspend fun edit(key: String, block: (PinRecord) -> PinRecord) = mutex.withLock {
        save(_records.value + (key to block(_records.value[key] ?: PinRecord(key))))
    }

    /**
     * 保存の記録が [from] から新しい部屋 [to] へ移った（同じ名前・募集文の立て直し、または似た部屋の引き継ぎ）。見張りの記録を引き継ぐ。
     * 立て直しは、移る前の様子（閉じた・満室）を残しておき、次に一覧で確かめたときに空いたと分かるようにする（[process]）。
     * 手動承認の類似部屋は空いたと数えず、移動先の状態を初期値にする。種別は再起動後も [PendingPinMove] で復元できる。
     */
    private suspend fun move(from: String, to: Room, kind: PinMoveKind) = mutex.withLock {
        val old = _records.value[from] ?: return@withLock
        val key = roomIdentity(to)
        val current = _records.value[key]
        var next = old.copy(key = key, rebuilds = old.rebuilds + 1, suggested = null, removedAt = null,
            vacancies = (old.vacancies + current?.vacancies.orEmpty()).distinct().sorted().takeLast(PinRecord.MAX_VACANCIES),
            // 古い部屋への未配信通知を、新しい部屋の通知として再送しない。移った先の部屋を「似た部屋」と知らせる通知も、
            // 引き継いだ時点で済んでいる（配信直後に引き継ぐと、配信済みの印を付ける前の通知が残って二度知らせてしまう）。
            pendingNotices = old.pendingNotices.filter { roomIdentity(it.room) == key && it.type != PinNoticeType.SIMILAR })
        if (kind == PinMoveKind.ADOPTED) {
            // 元が満室・閉鎖でも、承認した時点の待機中の部屋を「新たな空き」とはみなさない。
            // AUTO のピンでも、ここで空き通知や自動入室を発生させない。
            next = next.copy(state = if (to.status == RoomStatus.FULL) PinState.FULL else PinState.OPEN,
                stateAt = now())
        }
        // DB 側に未適用イベントが残るので、保存に失敗したらそのまま再試行する。
        // 成功後に限り確認済みにすれば、再起動してもメモ・通知設定・履歴を失わない。
        save(_records.value - from + (key to next))
    }

    private suspend fun process(favs: List<RoomPreference>, observations: Map<RoomQuery, ObservedRoomPage>, ordered: Boolean = false) = processMutex.withLock {
        val pages = when {
            lists.changes == null -> observations
            ordered -> observations.also { chronologicalPages = it }
            else -> chronologicalPages
        }
        val freshIds = HashSet<String>()
        val learned = mutableListOf<Pair<RoomPreference, Room>>()
        mutex.withLock {
            val at = now()
            val records = _records.value.toMutableMap()
            val claimed = favs.mapTo(HashSet()) { it.pinKey() }
            val windows = favs.mapNotNull { Genres[it.genreKey] }.distinct().associateWith { pages.genreWindow(it) }
            val views = favs.map { pref ->
                val key = pref.pinKey()
                val stored = pref.identityRoom()
                // 満室でピンした部屋は名前の記録がない。一覧で名前が見えていた姿から、同じ部屋と分かるときだけ補う。
                val identity = if (stored.name != null) stored else borrowNamed(stored, named(stored)) ?: stored
                val window = Genres[pref.genreKey]?.let(windows::get)
                val (seen, observed) = window?.pinState(identity) ?: (PinState.UNSEEN to null)
                val before = records[key] ?: PinRecord(key)
                var record = before.copy(removedAt = null)
                val page = window?.roomPages?.get(key)
                val known = if (observed != null) page ?: before.lastKnownPage else before.lastKnownPage
                val last = window?.takeIf { it.pages.isNotEmpty() }?.lastPage ?: before.lastPageCount
                record = record.copy(lastKnownPage = known, lastPageCount = last)
                if (seen != PinState.UNSEEN) {
                    record = record.copy(scanMissed = 0, scanAt = 0)
                } else if (before.state == PinState.OPEN) {
                    val genre = Genres[pref.genreKey]
                    if (genre != null) {
                        val count = maxOf(last ?: 1, 1)
                        val anchor = (known ?: 1).coerceIn(1, count)
                        val probe = RoomQuery(genre, page = searchPage(anchor, before.scanMissed, count))
                        val observedAt = observations[probe]?.confirmedAt ?: 0L
                        if (observedAt > before.scanAt) {
                            record = record.copy(scanMissed = before.scanMissed + 1, scanAt = observedAt)
                        }
                    }
                }
                if (seen != PinState.UNSEEN && seen != before.state) {
                    record = record.copy(state = seen, stateAt = at)
                    // 初めて確かめたときは記録するだけ。満室・閉じた部屋が待機中になったら空いたとする。
                    if (seen == PinState.OPEN && before.state != null) record = record.copy(vacancies = (record.vacancies + at).takeLast(PinRecord.MAX_VACANCIES))
                }
                // 一覧で見えなくなっただけのピンは前に確かめた様子のまま出すが、「空いています」だけは一覧に載っているときに限る
                // （閉じたと決めきれずに消えた部屋を、空いているように見せない。記録の様子は残し、また見えたときに空いたと数えない）。
                val anonymousConflict = identity.name == null &&
                    window?.listedRooms?.any { it.id == identity.id && it.genreKey == identity.genreKey &&
                        it.message != identity.message } == true
                val state = when {
                    seen != PinState.UNSEEN -> seen
                    // 同じ部屋番号に違う募集文が載った匿名ピンは、以前の「満室」を確定表示しない。
                    anonymousConflict -> PinState.UNSEEN
                    before.state == PinState.OPEN -> PinState.UNSEEN
                    else -> before.state ?: PinState.UNSEEN
                }
                val similar = if (state == PinState.CLOSED) window?.similarRoomOf(identity, record.dismissed + claimed) else null
                val room = observed?.let { o ->
                    o.copy(name = o.name ?: identity.name, gender = if (o.gender == Gender.UNKNOWN) identity.gender else o.gender, age = o.age ?: identity.age)
                } ?: identity
                // 部屋の番号は使い回されるので、ピンしたときと待機メッセージが同じ部屋からだけ覚える。
                if (pref.identityName == null) (observed?.takeIf { it.name != null && it.message == pref.snapshotMessage } ?: identity.takeIf { it.name != null })
                    ?.let { learned += pref to it }
                val view = PinView(pref, record, state, room, page, similar, listed = observed != null)
                if (seen == PinState.OPEN && before.state != null && before.state != PinState.OPEN && observed != null) {
                    val notice = PendingPinNotice("pin-$key-$at", PinNoticeType.OPENED, room, at, registered = identity,
                        autoEnter = record.autoEnters)
                    record = record.copy(pendingNotices = record.pendingNotices + notice)
                    freshIds += notice.id
                }
                if (similar != null && record.suggested != roomIdentity(similar.room)) {
                    // 初めて確かめたときは空きと同じく記録するだけにし、知らせは出さない。引き継ぎ済みのピンが
                    // お気に入り側の反映遅れで一瞬だけ古い部屋として処理されても、同じ候補を二度知らせないため。
                    record = record.copy(suggested = roomIdentity(similar.room))
                    if (before.state != null) {
                        val notice = PendingPinNotice("pin-similar-$key-${similar.room.id}", PinNoticeType.SIMILAR, similar.room, at,
                            similarity = similar.similarity)
                        record = record.copy(pendingNotices = record.pendingNotices + notice)
                        freshIds += notice.id
                    }
                }
                if (record != before || key !in records) records[key] = record
                view.copy(record = record)
            }
            // ピンを外して時間が経った記録は消す（外してすぐは「元に戻す」で知らせ方などを戻せるよう残す）。
            records.keys.filter { it !in claimed }.forEach { key ->
                val record = records.getValue(key)
                if (record.removedAt == null || record.pendingNotices.isNotEmpty()) {
                    // ピン解除後に古い通知が出ないよう、元に戻す用の記録だけを残す。
                    records[key] = record.copy(removedAt = record.removedAt ?: at, pendingNotices = emptyList())
                }
            }
            records.entries.removeAll { (key, r) -> key !in claimed && (r.removedAt ?: at) < at - PRUNE_MS }
            // 未配信イベントも保存してから通知する。通知中は状態のロックを保持しない。
            if (records != _records.value) save(records)
            _pins.value = views.sortedWith(compareBy<PinView> { order(it) }.thenByDescending { it.record.stateAt ?: 0L })
            _loaded.value = true
            newlyPinnedKeys(processedKeys, claimed).takeIf { it.isNotEmpty() }?.let(_added::tryEmit)
            processedKeys = claimed
        }
        // 補った名前は保存の記録にも残す（記録が変わると一覧を作り直すので、ロックの外で）。
        learned.forEach { (pref, room) ->
            try { preferences.rememberIdentity(pref, room) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        deliverPending(freshIds)
    }

    private fun order(pin: PinView): Int = when {
        pin.state == PinState.OPEN -> 0
        pin.similar != null -> 1
        pin.state == PinState.FULL -> 2
        pin.state == PinState.UNSEEN -> 3
        else -> 4
    }

    /** 未配信イベントを1件ずつ再送する。再送では通知IDを再利用し、自動入室は新しい観測だけに限る。 */
    private suspend fun deliverPending(freshIds: Set<String>) = noticeMutex.withLock {
        val attempted = HashSet<String>()
        while (true) {
            val candidate = mutex.withLock {
                val activeKeys = favorites.value.orEmpty().mapTo(HashSet()) { it.pinKey() }
                _records.value.entries.asSequence()
                    .filter { (key, _) -> key in activeKeys }
                    .flatMap { (key, record) -> record.pendingNotices.asSequence().map { Triple(key, record, it) } }
                    .firstOrNull { (_, _, pending) -> pending.id !in attempted }
                    ?.let { (key, record, pending) ->
                        _pins.value.firstOrNull { it.key == key }?.let { view ->
                            // 空きはその時の部屋の姿で知らせる。似た部屋はピンした部屋のまま（通知の題を「<ピンの名前>に似た部屋」にする）。
                            val shown = if (pending.type == PinNoticeType.OPENED) view.copy(record = record, room = pending.room) else view.copy(record = record)
                            Triple(shown, key, pending)
                        }
                    }
            } ?: break
            val (view, key, pending) = candidate
            attempted += pending.id
            val event: PinEvent = when (pending.type) {
                PinNoticeType.OPENED -> PinEvent.Opened(
                    view.copy(state = PinState.OPEN), pending.registered ?: view.preference.identityRoom(), pending.autoEnter,
                    replayed = pending.id !in freshIds, noticeId = pending.id, noticeAt = pending.at,
                )
                PinNoticeType.SIMILAR -> PinEvent.Similar(
                    view, SimilarRoom(pending.room, pending.similarity), noticeId = pending.id, noticeAt = pending.at,
                )
            }
            val delivered = try { onEvent(event); true }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
            if (!delivered) continue
            mutex.withLock {
                val current = _records.value[key] ?: return@withLock
                if (current.pendingNotices.none { it.id == pending.id }) return@withLock
                save(_records.value + (key to current.copy(pendingNotices = current.pendingNotices.filterNot { it.id == pending.id })))
            }
        }
    }

    /**
     * 保存できなければメモリ上の状態を更新せず、次回の観測で再評価できる状態に保つ。
     * 保存できた後でメモリへの反映がキャンセルで抜けないよう、両方をまとめて行う。
     */
    private suspend fun save(records: Map<String, PinRecord>) = withContext(NonCancellable + Dispatchers.IO) {
        persistence.save(records)
        _records.value = records
    }

    companion object {
        /** 外したピンの見張りの記録を残す間。 */
        const val PRUNE_MS = 7 * 24 * 60 * 60_000L
    }
}

/** ピンの見張りの記録を SharedPreferences に JSON で残す。読めない記録は捨てる。 */
class PinStore(private val prefs: android.content.SharedPreferences) : PinPersistence {
    override fun load(): Map<String, PinRecord> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { runCatching { decode(array.getJSONObject(it)) }.getOrNull() }.associateBy { it.key }
        }.getOrElse {
            prefs.edit().remove(KEY).apply()
            emptyMap()
        }
    }

    override fun save(records: Map<String, PinRecord>) {
        check(prefs.edit().putString(KEY, JSONArray(records.values.map(::encode)).toString()).commit()) {
            "ピンの見張りを保存できませんでした。"
        }
    }

    /** 以前の順番待ち・追跡をピンへ移し終えたか。 */
    var migrated: Boolean
        get() = prefs.getBoolean(MIGRATED, false)
        set(value) { prefs.edit().putBoolean(MIGRATED, value).apply() }

    private fun encode(r: PinRecord) = JSONObject()
        .put("key", r.key).put("alert", r.alert.name).put("memo", r.memo)
        .put("vacancies", JSONArray(r.vacancies)).put("rebuilds", r.rebuilds).put("dismissed", JSONArray(r.dismissed.toList()))
        .put("state", r.state?.name).put("stateAt", r.stateAt).put("suggested", r.suggested).put("removedAt", r.removedAt)
        .put("pending", JSONArray(r.pendingNotices.map(::encodePending)))
        .put("lastKnownPage", r.lastKnownPage).put("lastPageCount", r.lastPageCount)
        .put("scanMissed", r.scanMissed).put("scanAt", r.scanAt)

    /**
     * 以前は「自動で入室」に 3 時間の期限（autoUntil）があり、過ぎたものは「空いたら知らせる」として表示していた。
     * 期限をなくしたので、期限の過ぎたものは見たとおり「空いたら知らせる」に戻し、知らないうちに自動入室が再開しないようにする。
     */
    private fun decodeAlert(o: JSONObject): PinAlert {
        // 以前の「知らせない」（OFF）や知らない値は、いまの既定の「空いたら知らせる」にする。
        val alert = PinAlert.entries.firstOrNull { it.name == o.optString("alert") } ?: PinAlert.NOTIFY
        val until = if (o.has("autoUntil") && !o.isNull("autoUntil")) o.getLong("autoUntil") else null
        return if (alert == PinAlert.AUTO && until != null && until <= System.currentTimeMillis()) PinAlert.NOTIFY else alert
    }

    private fun decode(o: JSONObject): PinRecord {
        fun long(key: String) = if (o.isNull(key)) null else o.getLong(key)
        fun string(key: String) = if (o.isNull(key)) null else o.getString(key)
        val vacancies = o.optJSONArray("vacancies")
        val dismissed = o.optJSONArray("dismissed")
        return PinRecord(
            key = o.getString("key"),
            alert = decodeAlert(o),
            memo = o.optString("memo"),
            vacancies = vacancies?.let { a -> (0 until a.length()).map { a.getLong(it) } }.orEmpty(),
            rebuilds = o.optInt("rebuilds"),
            dismissed = dismissed?.let { a -> (0 until a.length()).mapTo(HashSet()) { a.getString(it) } }.orEmpty(),
            state = string("state")?.let { s -> PinState.entries.firstOrNull { it.name == s } },
            stateAt = long("stateAt"),
            suggested = string("suggested"),
            removedAt = long("removedAt"),
            pendingNotices = o.optJSONArray("pending")?.let { array ->
                (0 until array.length()).mapNotNull { i -> runCatching { decodePending(array.getJSONObject(i)) }.getOrNull() }
            }.orEmpty(),
            lastKnownPage = long("lastKnownPage")?.toInt()?.takeIf { it > 0 },
            lastPageCount = long("lastPageCount")?.toInt()?.takeIf { it > 0 },
            scanMissed = o.optInt("scanMissed").coerceAtLeast(0),
            scanAt = o.optLong("scanAt"),
        )
    }

    private fun encodePending(p: PendingPinNotice): JSONObject = JSONObject()
        .put("id", p.id).put("type", p.type.name).put("room", pinRoomJson(p.room)).put("at", p.at)
        .put("registered", p.registered?.let(::pinRoomJson)).put("similarity", p.similarity).put("autoEnter", p.autoEnter)

    private fun decodePending(o: JSONObject): PendingPinNotice = PendingPinNotice(
        id = o.getString("id"), type = PinNoticeType.valueOf(o.getString("type")),
        room = readPinRoom(o.getJSONObject("room")), at = o.getLong("at"),
        registered = o.optJSONObject("registered")?.let(::readPinRoom),
        similarity = o.optDouble("similarity"), autoEnter = o.optBoolean("autoEnter"),
    ).also { require(it.type != PinNoticeType.OPENED || it.registered != null) }

    companion object {
        const val PREFS = "pins"
        private const val KEY = "records"
        private const val MIGRATED = "migrated_v1"
    }
}

/** 保存したイベントは再起動後にも同じ部屋の姿を使う。 */
internal fun pinRoomJson(r: Room) = JSONObject()
    .put("id", r.id).put("genre", r.genreKey).put("status", r.status.name).put("action", r.action.name)
    .put("name", r.name).put("gender", r.gender.name).put("age", r.age).put("area", r.area).put("message", r.message)

internal fun readPinRoom(j: JSONObject) = Room(
    j.getLong("id"), j.getString("genre"), RoomStatus.valueOf(j.getString("status")), RoomAction.valueOf(j.getString("action")), null,
    if (j.isNull("name")) null else j.getString("name"), Gender.valueOf(j.getString("gender")),
    if (j.isNull("age")) null else j.getInt("age"), if (j.isNull("area")) null else j.getString("area"), j.getString("message"),
)
