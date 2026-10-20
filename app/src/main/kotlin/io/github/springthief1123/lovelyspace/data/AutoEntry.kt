package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.core.RoomStatus
import io.github.springthief1123.lovelyspace.core.roomIdentity
import io.github.springthief1123.lovelyspace.notify.NotificationTarget
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 順番待ちの部屋が空いたときの自動入室（Yuya の決定、2026-10-18。Chrome 拡張の自動入室と同じ）。
 * 空きを見つけたら [requests] に入室画面を開く依頼を出し、画面側がそれを受けて入力済みの入室画面を「自動」で開く。
 *
 * 入室は既定ではワンタップまでで、自動にするのは次のどちらか（Yuya の決定、2026-10-19）。
 * - 設定「順番待ちの部屋が空いたら自動で入室する」をオンにしてから [DURATION_MS] 以内（[startedAt]）で、その間の依頼が [MAX_ATTEMPTS] 回未満（すべての順番待ち）
 * - 必ず入りたい部屋として、順番待ちの 1 件（[WaitlistEntry.autoEnter]、順番待ちの期限まで）か、名前で追う 1 件
 *   （[CandidateRule.autoEnterUntil]、名前が完全に一致する新しい部屋。[PINNED_MS] まで）でオンにしたもの。それぞれ [MAX_ATTEMPTS] 回まで
 *
 * 見ていない間に入室して相手を待たせないよう、どちらも次のときだけ依頼を出す。
 * - アプリが前面で一覧などの主画面を見ていて、会話中でもロックもされていない（[ready]、画面側が更新する）
 * - 順番待ちは、満室の間は名前が一覧に出ないので、待機メッセージが登録時と同じ（空でない）部屋
 * - 同じ部屋への依頼から [COOLDOWN_MS] 経っている
 */
class AutoEntryController(
    private val startedAt: suspend () -> Long,
    /** 回数と部屋ごとの最後の依頼を残す。有効にしている間にアプリが落ちて開き直されても、上限と待ちを引き継ぐ。 */
    private val persistence: AutoEntryPersistence = AutoEntryPersistence.None,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val _requests = MutableSharedFlow<NotificationTarget.Room>(extraBufferCapacity = 1)
    /** 開く入室画面。受け取った画面は入力済みの入室画面を自動の入室で開く。 */
    val requests: SharedFlow<NotificationTarget.Room> = _requests.asSharedFlow()

    @Volatile var ready: Boolean = false

    private val saved = runCatching { persistence.load() }.getOrNull() ?: AutoEntryRecord()
    private var period = saved.period
    private val _attempts = MutableStateFlow(saved.attempts)
    /** いま有効にしている間に、自動で入室画面を開いた回数。 */
    val attempts: StateFlow<Int> = _attempts.asStateFlow()
    private val lastRequested = saved.lastRequested.toMutableMap()
    /** 必ず入りたい部屋（順番待ちの 1 件・名前で追う 1 件）ごとの、自動で入室画面を開いた回数。 */
    private val pinned = saved.pinned.toMutableMap()

    /** 有効にし直したとき。回数と同じ部屋の待ちを数え直す。 */
    fun reset() {
        period = 0L
        _attempts.value = 0
        lastRequested.clear()
        save()
    }

    /** 必ず入りたい部屋 [target]（[waitlistTarget]・[candidateTarget]）をオンにし直したとき。その部屋の回数を数え直す。 */
    suspend fun resetTarget(target: String) = mutex.withLock {
        if (pinned.remove(target) != null) save()
    }

    /** 部屋が立て直されて [from] の数え方が [to] へ移ったとき（ピンの立て直し）。回数を引き継ぎ、数え直さない。 */
    suspend fun moveTarget(from: String, to: String) = mutex.withLock {
        val count = pinned.remove(from) ?: return@withLock
        pinned[to] = maxOf(count, pinned[to] ?: 0)
        save()
    }

    private fun save() {
        runCatching { persistence.save(AutoEntryRecord(period, _attempts.value, lastRequested.toMap(), pinned.toMap())) }
    }

    /** 空いた順番待ち [entry] について依頼を出したら true。 */
    suspend fun offer(entry: WaitlistEntry): Boolean = mutex.withLock {
        val opened = entry.openedRoom ?: return@withLock false
        if (entry.room.message.isBlank() || entry.room.message != opened.message) return@withLock false
        request(opened, entry.key, if (entry.autoEnter) waitlistTarget(entry.key) else null)
    }

    /**
     * 自動入室をオンにした名前で追う [rule] に、名前が完全に一致する新しい部屋 [room] が出たとき。依頼を出したら true。
     * 表示名に含むだけの条件は別の人に当たりやすいので、自動では入らない。
     */
    suspend fun offerNamed(rule: CandidateRule, room: Room): Boolean = mutex.withLock {
        if (!rule.autoEnterActive(now()) || rule.mode != CandidateMode.EXACT_NAME || !rule.matches(room)) return@withLock false
        if (room.status != RoomStatus.WAITING || room.action != RoomAction.ENTER) return@withLock false
        request(room, roomIdentity(room), candidateTarget(rule.id))
    }

    /**
     * ピン [key] の部屋が空いた（立て直した部屋が待機中で現れたときも）とき。依頼を出したら true。
     * 順番待ちと同じく、待機メッセージがピンしたとき（[registered]）と同じ部屋に限る。
     * [always] は「自動で入室」にしたピン（[pinTarget] で数える）。false なら設定の自動入室が有効な間だけ。
     */
    suspend fun offerPin(key: String, registered: Room, opened: Room, always: Boolean): Boolean = mutex.withLock {
        if (registered.message.isBlank() || registered.message != opened.message) return@withLock false
        if (opened.status == RoomStatus.FULL || opened.action != RoomAction.ENTER) return@withLock false
        request(opened, roomIdentity(opened), if (always) pinTarget(key) else null)
    }

    /** [room] の入室画面を開く依頼。[target] は必ず入りたい部屋としてオンにしたもの（null なら設定の自動入室で数える）。 */
    private suspend fun request(room: Room, key: String, target: String?): Boolean {
        val host = Genres[room.genreKey]?.host ?: return false
        val at = now()
        if (!ready) return false
        if (target != null) {
            if ((pinned[target] ?: 0) >= MAX_ATTEMPTS) return false
        } else {
            val started = startedAt()
            if (!active(started, at)) return false
            if (started != period) { period = started; _attempts.value = 0; lastRequested.clear() }
            if (_attempts.value >= MAX_ATTEMPTS) return false
        }
        if (lastRequested[key]?.let { at - it < COOLDOWN_MS } == true) return false
        // 受け取る画面が無いと依頼は捨てられるので、その場合は回数に数えない。
        if (_requests.subscriptionCount.value == 0 || !_requests.tryEmit(NotificationTarget.Room(host, room.genreKey, room.id))) return false
        if (target != null) pinned[target] = (pinned[target] ?: 0) + 1 else _attempts.value++
        lastRequested[key] = at
        save()
        return true
    }

    companion object {
        /** 有効にしてから自動で止まるまで。 */
        const val DURATION_MS = 30 * 60_000L
        /** 1 回有効にしている間に自動で入室画面を開く回数の上限。 */
        const val MAX_ATTEMPTS = 5
        /** 同じ部屋へ続けて自動で入らない間。 */
        const val COOLDOWN_MS = 60_000L
        /** 名前で追う 1 件の自動入室をオンにしてから止まるまで（順番待ちの期限と同じ 3 時間）。 */
        const val PINNED_MS = 3 * 60 * 60_000L

        fun waitlistTarget(key: String) = "waitlist/$key"
        fun candidateTarget(id: String) = "candidate/$id"
        fun pinTarget(key: String) = "pin/$key"

        /** [startedAt] に有効にした自動入室が [at] の時点でまだ動いているか。0 は無効。 */
        fun active(startedAt: Long, at: Long): Boolean = startedAt > 0 && at - startedAt in 0 until DURATION_MS
    }
}

/** [AutoEntryController] の回数と待ち。[period] は数え始めた有効期間（有効にした時刻）。 */
data class AutoEntryRecord(val period: Long = 0L, val attempts: Int = 0, val lastRequested: Map<String, Long> = emptyMap(),
    /** 必ず入りたい部屋ごとの回数（[AutoEntryController.waitlistTarget]・[AutoEntryController.candidateTarget]）。 */
    val pinned: Map<String, Int> = emptyMap())

interface AutoEntryPersistence {
    fun load(): AutoEntryRecord?
    fun save(record: AutoEntryRecord)

    object None : AutoEntryPersistence {
        override fun load(): AutoEntryRecord? = null
        override fun save(record: AutoEntryRecord) {}
    }
}

/** 自動入室の回数と待ちを SharedPreferences に JSON で残す。読めない記録は捨てる。 */
class AutoEntryStore(private val prefs: android.content.SharedPreferences) : AutoEntryPersistence {
    override fun load(): AutoEntryRecord? {
        val raw = prefs.getString(KEY, null) ?: return null
        return runCatching {
            val o = org.json.JSONObject(raw)
            val last = o.optJSONObject("lastRequested")
            val pinned = o.optJSONObject("pinned")
            AutoEntryRecord(o.getLong("period"), o.getInt("attempts"),
                last?.keys()?.asSequence()?.associateWith { last.getLong(it) }.orEmpty(),
                pinned?.keys()?.asSequence()?.associateWith { pinned.getInt(it) }.orEmpty())
        }.getOrNull()
    }

    override fun save(record: AutoEntryRecord) {
        val last = org.json.JSONObject().apply { record.lastRequested.forEach { (k, v) -> put(k, v) } }
        val pinned = org.json.JSONObject().apply { record.pinned.forEach { (k, v) -> put(k, v) } }
        prefs.edit().putString(KEY, org.json.JSONObject().put("period", record.period).put("attempts", record.attempts)
            .put("lastRequested", last).put("pinned", pinned).toString()).apply()
    }

    companion object {
        const val PREFS = "auto_entry"
        private const val KEY = "record"
    }
}
