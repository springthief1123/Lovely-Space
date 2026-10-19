package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.Genres
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
 * 見ていない間に入室して相手を待たせないよう、次のときだけ依頼を出す。
 * - 設定で有効にしてから [DURATION_MS] 以内（[startedAt]）で、その間の依頼が [MAX_ATTEMPTS] 回未満
 * - アプリが前面で一覧などの主画面を見ていて、会話中でもロックもされていない（[ready]、画面側が更新する）
 * - 満室の間は名前が一覧に出ないので、待機メッセージが登録時と同じ（空でない）部屋
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

    /** 有効にし直したとき。回数と同じ部屋の待ちを数え直す。 */
    fun reset() {
        period = 0L
        _attempts.value = 0
        lastRequested.clear()
        save()
    }

    private fun save() {
        runCatching { persistence.save(AutoEntryRecord(period, _attempts.value, lastRequested.toMap())) }
    }

    /** 空いた順番待ち [entry] について依頼を出したら true。 */
    suspend fun offer(entry: WaitlistEntry): Boolean = mutex.withLock {
        val opened = entry.openedRoom ?: return@withLock false
        val host = Genres[opened.genreKey]?.host ?: return@withLock false
        val started = startedAt()
        val at = now()
        if (!active(started, at) || !ready) return@withLock false
        if (started != period) { period = started; _attempts.value = 0; lastRequested.clear() }
        if (_attempts.value >= MAX_ATTEMPTS) return@withLock false
        if (entry.room.message.isBlank() || entry.room.message != opened.message) return@withLock false
        if (lastRequested[entry.key]?.let { at - it < COOLDOWN_MS } == true) return@withLock false
        // 受け取る画面が無いと依頼は捨てられるので、その場合は回数に数えない。
        if (_requests.subscriptionCount.value == 0 || !_requests.tryEmit(NotificationTarget.Room(host, opened.genreKey, opened.id))) return@withLock false
        _attempts.value++
        lastRequested[entry.key] = at
        save()
        true
    }

    companion object {
        /** 有効にしてから自動で止まるまで。 */
        const val DURATION_MS = 30 * 60_000L
        /** 1 回有効にしている間に自動で入室画面を開く回数の上限。 */
        const val MAX_ATTEMPTS = 5
        /** 同じ部屋へ続けて自動で入らない間。 */
        const val COOLDOWN_MS = 60_000L

        /** [startedAt] に有効にした自動入室が [at] の時点でまだ動いているか。0 は無効。 */
        fun active(startedAt: Long, at: Long): Boolean = startedAt > 0 && at - startedAt in 0 until DURATION_MS
    }
}

/** [AutoEntryController] の回数と待ち。[period] は数え始めた有効期間（有効にした時刻）。 */
data class AutoEntryRecord(val period: Long = 0L, val attempts: Int = 0, val lastRequested: Map<String, Long> = emptyMap())

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
            AutoEntryRecord(o.getLong("period"), o.getInt("attempts"),
                last?.keys()?.asSequence()?.associateWith { last.getLong(it) }.orEmpty())
        }.getOrNull()
    }

    override fun save(record: AutoEntryRecord) {
        val last = org.json.JSONObject().apply { record.lastRequested.forEach { (k, v) -> put(k, v) } }
        prefs.edit().putString(KEY, org.json.JSONObject().put("period", record.period).put("attempts", record.attempts)
            .put("lastRequested", last).toString()).apply()
    }

    companion object {
        const val PREFS = "auto_entry"
        private const val KEY = "record"
    }
}
