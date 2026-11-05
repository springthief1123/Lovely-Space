package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.core.RoomStatus
import io.github.springthief1123.lovelyspace.core.chat.ChatRoomRef
import io.github.springthief1123.lovelyspace.core.roomIdentity
import io.github.springthief1123.lovelyspace.notify.NotificationTarget
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 空いた部屋への自動入室（Yuya の決定、2026-10-18。Chrome 拡張の自動入室と同じ）。
 * 空きを見つけたら [requests] に入室画面を開く依頼を出し、画面側がそれを受けて入力済みの入室画面を「自動」で開く。
 *
 * 入室は既定ではワンタップまでで、自動にするのは利用者が 1 件ずつオンにしたものだけ（Yuya の決定、2026-11-05）。
 * - ピンの知らせ方「自動で入室」（[offerPin]）
 * - 名前で追う 1 件（[CandidateRule.autoEnterActive]、名前が完全に一致する新しい部屋。[offerNamed]）
 * - 以前の順番待ちで 1 件ずつオンにしたもの（[WaitlistEntry.autoEnter]。[offer]）
 * 回数や期限では止めない。代わりに、その部屋へ入室できたら（[entered]）その 1 件の自動入室を終え（[finished]）、
 * 追い出されたり退室したりした後に入り直し続けないようにする。
 *
 * 見ていない間に入室して相手を待たせないよう、どれも次のときだけ依頼を出す。
 * - アプリが前面で一覧などの主画面を見ていて、会話中でもロックもされていない（[ready]、画面側が更新する）
 * - ピン・順番待ちは、満室の間は名前が一覧に出ないので、待機メッセージが登録時と同じ（空でない）部屋
 * - 同じ部屋への依頼から [COOLDOWN_MS] 経っている
 */
class AutoEntryController(
    /** 部屋ごとの最後の依頼を残す。アプリが落ちて開き直されても、同じ部屋への待ちを引き継ぐ。 */
    private val persistence: AutoEntryPersistence = AutoEntryPersistence.None,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val _requests = MutableSharedFlow<NotificationTarget.Room>(extraBufferCapacity = 1)
    /** 開く入室画面。受け取った画面は入力済みの入室画面を自動の入室で開く。 */
    val requests: SharedFlow<NotificationTarget.Room> = _requests.asSharedFlow()

    private val _finished = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** 入室できて自動入室を終える 1 件（[pinTarget]・[candidateTarget]・[waitlistTarget]）。受け取った側がその 1 件をオフにする。 */
    val finished: SharedFlow<String> = _finished.asSharedFlow()

    @Volatile var ready: Boolean = false

    private val saved = runCatching { persistence.load() }.getOrNull() ?: AutoEntryRecord()
    private val lastRequested = saved.lastRequested.toMutableMap()
    /** 自動で入室画面を開いた部屋（[roomKey]）と、それをオンにしていた 1 件。 */
    private val openedTargets = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun save() {
        runCatching { persistence.save(AutoEntryRecord(lastRequested.toMap())) }
    }

    /** 空いた順番待ち [entry]（1 件ずつ自動入室をオンにしたもの）について依頼を出したら true。 */
    suspend fun offer(entry: WaitlistEntry): Boolean = mutex.withLock {
        val opened = entry.openedRoom ?: return@withLock false
        if (!entry.autoEnter) return@withLock false
        if (entry.room.message.isBlank() || entry.room.message != opened.message) return@withLock false
        request(opened, entry.key, waitlistTarget(entry.key))
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
     * 「自動で入室」にしたピン [key] の部屋が空いた（立て直した部屋が待機中で現れたときも）とき。依頼を出したら true。
     * 順番待ちと同じく、待機メッセージがピンしたとき（[registered]）と同じ部屋に限る。
     */
    suspend fun offerPin(key: String, registered: Room, opened: Room): Boolean = mutex.withLock {
        if (registered.message.isBlank() || registered.message != opened.message) return@withLock false
        if (opened.status == RoomStatus.FULL || opened.action != RoomAction.ENTER) return@withLock false
        request(opened, roomIdentity(opened), pinTarget(key))
    }

    /**
     * 入室できたとき（自動でも手でも）。自動で入室画面を開いていた部屋なら、その 1 件の自動入室を終える（[finished]）。
     * 入れなかった回（満室に戻っていた・断られた）は終えないので、次に空いたらまた開く。
     */
    fun entered(room: ChatRoomRef) {
        val target = openedTargets.remove(roomKey(room.genreKey, room.roomId)) ?: return
        _finished.tryEmit(target)
    }

    /** [room] の入室画面を開く依頼。[target] は自動入室をオンにした 1 件。 */
    private fun request(room: Room, key: String, target: String): Boolean {
        val host = Genres[room.genreKey]?.host ?: return false
        val at = now()
        if (!ready) return false
        if (lastRequested[key]?.let { at - it < COOLDOWN_MS } == true) return false
        // 受け取る画面が無いと依頼は捨てられるので、その場合は待ちに数えない。
        if (_requests.subscriptionCount.value == 0 || !_requests.tryEmit(NotificationTarget.Room(host, room.genreKey, room.id))) return false
        openedTargets[roomKey(room.genreKey, room.id)] = target
        lastRequested[key] = at
        lastRequested.entries.removeAll { at - it.value >= COOLDOWN_MS * 10 }
        save()
        return true
    }

    companion object {
        /** 同じ部屋へ続けて自動で入らない間。 */
        const val COOLDOWN_MS = 60_000L

        fun waitlistTarget(key: String) = "waitlist/$key"
        fun candidateTarget(id: String) = "candidate/$id"
        fun pinTarget(key: String) = "pin/$key"

        private fun roomKey(genreKey: String, roomId: Long) = "$genreKey/$roomId"
    }
}

/** [AutoEntryController] の同じ部屋への待ち（部屋ごとの最後の依頼の時刻）。 */
data class AutoEntryRecord(val lastRequested: Map<String, Long> = emptyMap())

interface AutoEntryPersistence {
    fun load(): AutoEntryRecord?
    fun save(record: AutoEntryRecord)

    object None : AutoEntryPersistence {
        override fun load(): AutoEntryRecord? = null
        override fun save(record: AutoEntryRecord) {}
    }
}

/** 自動入室の同じ部屋への待ちを SharedPreferences に JSON で残す。読めない記録（以前の回数つきの形も読める）は捨てる。 */
class AutoEntryStore(private val prefs: android.content.SharedPreferences) : AutoEntryPersistence {
    override fun load(): AutoEntryRecord? {
        val raw = prefs.getString(KEY, null) ?: return null
        return runCatching {
            val last = org.json.JSONObject(raw).optJSONObject("lastRequested")
            AutoEntryRecord(last?.keys()?.asSequence()?.associateWith { last.getLong(it) }.orEmpty())
        }.getOrNull()
    }

    override fun save(record: AutoEntryRecord) {
        val last = org.json.JSONObject().apply { record.lastRequested.forEach { (k, v) -> put(k, v) } }
        prefs.edit().putString(KEY, org.json.JSONObject().put("lastRequested", last).toString()).apply()
    }

    companion object {
        const val PREFS = "auto_entry"
        private const val KEY = "record"
    }
}
