package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** ピンした部屋の知らせ方。 */
enum class PinAlert {
    /** 見張って記録するだけ。 */
    OFF,
    /** 空いた・似た部屋が建ったら知らせる。 */
    NOTIFY,
    /** 空いたら入室画面を自動で開く（[PinRecord.autoUntil] まで。歯止めは [AutoEntryController]）。 */
    AUTO,
}

/**
 * ピンの見張りの記録。ピンした部屋そのもの（名前・募集文など）は保存の記録（[RoomPreference.favorite]）にあり、[key]（[roomIdentity]）で結び付ける。
 * 部屋が立て直されて保存の記録が新しい部屋へ移ると（[RoomPreferenceRepository.moved]）、この記録も移す。
 */
data class PinRecord(
    val key: String,
    val alert: PinAlert = PinAlert.OFF,
    /** 自動で入室をオンにした期限。過ぎたら「空いたら知らせる」と同じに扱う。 */
    val autoUntil: Long? = null,
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
) {
    fun autoActive(now: Long): Boolean = alert == PinAlert.AUTO && (autoUntil ?: 0L) > now
    /** 知らせるか（自動で入室の期限が過ぎても知らせる）。 */
    val notifies: Boolean get() = alert != PinAlert.OFF

    companion object {
        const val MAX_VACANCIES = 200
        const val MAX_MEMO = 500
    }
}

/**
 * レーダーに出すピン 1 件。[room] は一覧で見た最新の姿（満室で隠れた名前などはピンしたときの値で補う）、
 * [page] はその部屋が載っていたページ、[similar] は閉じた部屋に似た新しい部屋の候補。
 */
data class PinView(val preference: RoomPreference, val record: PinRecord, val state: PinState, val room: Room, val page: Int?, val similar: SimilarRoom?) {
    val key: String get() = record.key
    val name: String get() = room.name ?: preference.identityName ?: preference.snapshotName ?: "名前の見えない部屋"
}

/** ピンの見張りで起きたこと。知らせるピン（[PinRecord.notifies]）だけ。 */
sealed interface PinEvent {
    val pin: PinView
    /** 満室・閉じた部屋が空いた（立て直した部屋が待機中で現れたときも）。[registered] はピンしたときの内容。 */
    data class Opened(override val pin: PinView, val registered: Room, val autoEnter: Boolean) : PinEvent
    /** 閉じた部屋に似た部屋が建った。 */
    data class Similar(override val pin: PinView, val similar: SimilarRoom) : PinEvent
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

private fun RoomPreference.pinKey(): String = "$host/$roomId"

/**
 * ピンした部屋の見張り。保存（[RoomPreference.favorite]）した部屋をピンとして、どの画面・背景の確認が読んだ一覧（[lists]）からも
 * 空き・満室・閉じたを追い、空いた時刻を残す。知らせるピンは空いたとき・閉じた部屋に似た部屋が建ったときに [onEvent] を呼ぶ。
 * 同じ名前・募集文の立て直しは保存の記録ごと新しい部屋へ移り（[RoomPreferenceRepository.followRebuilds]）、ここでは記録を引き継ぐ。
 * 通信はしない。満室のピンの空きを早く知るための確認は、順番待ち（[WaitlistRepository]）に頼む（[fastWatch]）。
 */
class PinRepository(
    private val persistence: PinPersistence,
    private val preferences: RoomPreferenceStore,
    private val lists: ObservedRoomListSource,
    private val onEvent: suspend (PinEvent) -> Unit,
    moved: Flow<Pair<String, Room>> = emptyFlow(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
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

    init {
        scope.launch {
            combine(favorites.filterNotNull(), lists.observations, _records) { favs, obs, _ -> favs to obs }
                .conflate()
                .collect { (favs, obs) ->
                    try { process(favs, obs) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                }
        }
        scope.launch { moved.collect { (from, to) -> try { move(from, to) } catch (e: CancellationException) { throw e } catch (_: Exception) { } } }
    }

    /** 知らせるピンのジャンル。1 ページ目を新着の確認で読み、立て直された部屋にすぐ気づく。 */
    val genres: Flow<Set<Genre>> = pins.map { list -> list.filter { it.record.notifies }.mapNotNullTo(HashSet()) { Genres[it.room.genreKey] } }

    /** 空きを早く確かめたい満室のピン（知らせるもの）。順番待ちに登録して、部屋のページを設定の間隔で読む。 */
    val fastWatch: Flow<List<PinView>> = pins.map { list -> list.filter { it.record.notifies && it.state == PinState.FULL } }

    /** いま手元にある一覧で見張りを進め、起きたことを知らせ終えるまで待つ（背景の実行が終わる前に呼ぶ）。 */
    suspend fun refresh() {
        process(favorites.filterNotNull().first(), lists.observations.value)
    }

    fun record(key: String): PinRecord = _records.value[key] ?: PinRecord(key)

    /** [room] をピンし、知らせ方を [alert] にする。 */
    suspend fun pin(room: Room, alert: PinAlert = PinAlert.OFF) {
        preferences.setFavorite(room, true)
        setAlert(roomIdentity(room), alert)
    }

    suspend fun setAlert(key: String, alert: PinAlert) = edit(key) {
        it.copy(alert = alert, autoUntil = if (alert == PinAlert.AUTO) now() + AutoEntryController.PINNED_MS else null)
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

    /** 立て直しで保存の記録が [from] から新しい部屋 [to] へ移った。見張りの記録を引き継ぎ、空いていれば知らせる。 */
    private suspend fun move(from: String, to: Room) {
        val events = mutableListOf<PinEvent>()
        mutex.withLock {
            val old = _records.value[from] ?: return
            val key = roomIdentity(to)
            val current = _records.value[key]
            val state = if (to.status == RoomStatus.FULL) PinState.FULL else PinState.OPEN
            val at = now()
            var next = old.copy(key = key, rebuilds = old.rebuilds + 1, suggested = null, state = state, stateAt = at,
                vacancies = (old.vacancies + (current?.vacancies.orEmpty())).distinct().sorted().takeLast(PinRecord.MAX_VACANCIES))
            if (state == PinState.OPEN && old.state != PinState.OPEN && current?.state != PinState.OPEN) {
                next = next.copy(vacancies = (next.vacancies + at).takeLast(PinRecord.MAX_VACANCIES))
                favorites.value?.firstOrNull { it.pinKey() == key }?.let { pref ->
                    if (next.notifies) events += PinEvent.Opened(PinView(pref, next, state, to, null, null), pref.identityRoom(), next.autoActive(at))
                }
            }
            save(_records.value - from + (key to next))
        }
        deliver(events)
    }

    private suspend fun process(favs: List<RoomPreference>, observations: Map<RoomQuery, ObservedRoomPage>) {
        val events = mutableListOf<PinEvent>()
        mutex.withLock {
            val at = now()
            val records = _records.value.toMutableMap()
            val claimed = favs.mapTo(HashSet()) { it.pinKey() }
            val windows = favs.mapNotNull { Genres[it.genreKey] }.distinct().associateWith { observations.genreWindow(it) }
            val views = favs.map { pref ->
                val key = pref.pinKey()
                val identity = pref.identityRoom()
                val window = Genres[pref.genreKey]?.let(windows::get)
                val (seen, observed) = window?.pinState(identity) ?: (PinState.UNSEEN to null)
                val before = records[key] ?: PinRecord(key)
                var record = before
                if (seen != PinState.UNSEEN && seen != before.state) {
                    record = record.copy(state = seen, stateAt = at)
                    // 初めて確かめたときは記録するだけ。満室・閉じた部屋が待機中になったら空いたとする。
                    if (seen == PinState.OPEN && before.state != null) record = record.copy(vacancies = (record.vacancies + at).takeLast(PinRecord.MAX_VACANCIES))
                }
                val state = if (seen == PinState.UNSEEN) before.state ?: PinState.UNSEEN else seen
                val similar = if (state == PinState.CLOSED) window?.similarRoomOf(identity, record.dismissed + claimed) else null
                val room = observed?.let { o ->
                    o.copy(name = o.name ?: identity.name, gender = if (o.gender == Gender.UNKNOWN) identity.gender else o.gender, age = o.age ?: identity.age)
                } ?: identity
                val view = PinView(pref, record, state, room, window?.roomPages?.get(key), similar)
                if (record.notifies && seen == PinState.OPEN && before.state != null && before.state != PinState.OPEN && observed != null) {
                    events += PinEvent.Opened(view, identity, record.autoActive(at))
                }
                if (record.notifies && similar != null && record.suggested != roomIdentity(similar.room)) {
                    record = record.copy(suggested = roomIdentity(similar.room))
                    events += PinEvent.Similar(view.copy(record = record), similar)
                }
                if (record != before || key !in records) records[key] = record
                view.copy(record = record)
            }
            // ピンを外して時間が経った記録は消す（外してすぐは「元に戻す」で知らせ方などを戻せるよう残す）。
            records.entries.removeAll { (key, r) -> key !in claimed && (r.stateAt ?: 0L) < at - PRUNE_MS }
            if (records != _records.value) save(records)
            _pins.value = views.sortedWith(compareBy<PinView> { order(it) }.thenByDescending { it.record.stateAt ?: 0L })
            _loaded.value = true
        }
        deliver(events)
    }

    private fun order(pin: PinView): Int = when {
        pin.state == PinState.OPEN -> 0
        pin.similar != null -> 1
        pin.state == PinState.FULL -> 2
        pin.state == PinState.UNSEEN -> 3
        else -> 4
    }

    private suspend fun deliver(events: List<PinEvent>) {
        events.forEach { event ->
            try { onEvent(event) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    private fun save(records: Map<String, PinRecord>) {
        _records.value = records
        runCatching { persistence.save(records) }
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
        prefs.edit().putString(KEY, JSONArray(records.values.map(::encode)).toString()).apply()
    }

    /** 以前の順番待ち・追跡をピンへ移し終えたか。 */
    var migrated: Boolean
        get() = prefs.getBoolean(MIGRATED, false)
        set(value) { prefs.edit().putBoolean(MIGRATED, value).apply() }

    private fun encode(r: PinRecord) = JSONObject()
        .put("key", r.key).put("alert", r.alert.name).put("autoUntil", r.autoUntil).put("memo", r.memo)
        .put("vacancies", JSONArray(r.vacancies)).put("rebuilds", r.rebuilds).put("dismissed", JSONArray(r.dismissed.toList()))
        .put("state", r.state?.name).put("stateAt", r.stateAt).put("suggested", r.suggested)

    private fun decode(o: JSONObject): PinRecord {
        fun long(key: String) = if (o.isNull(key)) null else o.getLong(key)
        fun string(key: String) = if (o.isNull(key)) null else o.getString(key)
        val vacancies = o.optJSONArray("vacancies")
        val dismissed = o.optJSONArray("dismissed")
        return PinRecord(
            key = o.getString("key"),
            alert = PinAlert.entries.firstOrNull { it.name == o.optString("alert") } ?: PinAlert.OFF,
            autoUntil = long("autoUntil"),
            memo = o.optString("memo"),
            vacancies = vacancies?.let { a -> (0 until a.length()).map { a.getLong(it) } }.orEmpty(),
            rebuilds = o.optInt("rebuilds"),
            dismissed = dismissed?.let { a -> (0 until a.length()).mapTo(HashSet()) { a.getString(it) } }.orEmpty(),
            state = string("state")?.let { s -> PinState.entries.firstOrNull { it.name == s } },
            stateAt = long("stateAt"),
            suggested = string("suggested"),
        )
    }

    companion object {
        const val PREFS = "pins"
        private const val KEY = "records"
        private const val MIGRATED = "migrated_v1"
    }
}
