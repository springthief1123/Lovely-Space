package io.github.springthief1123.lovelyspace.notify

import io.github.springthief1123.lovelyspace.data.RadarEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** [RadarNotices] が読む・書く、まだお知らせに出していない一致の置き場（`RadarRepository`）。 */
interface PendingRadarNotices {
    fun pendingNotices(): List<RadarEvent>
    suspend fun markNoticed(ids: Set<String>)
}

/**
 * 条件・名前で待つの新しい一致を、お知らせ（通知ベル）と端末通知に出す。前面・背景のどちらで見つけた一致も同じように出す。
 *
 * - 一致はすべてお知らせに残す（レーダーの履歴の代わり）。端末通知は「新着を知らせる」条件・知らせる名前で待つ（[RadarEvent.alert]）だけ。
 * - 同じ条件で続けて見つけた一致は、そのお知らせが未読で [BURST_MS] 以内なら 1 件にまとめて差し替える（新着の確認は数秒ごとなので）。
 * - 音・バイブは同じお知らせで [ALERT_GAP_MS] に 1 回まで。会話中（[quiet]）は端末通知を出さずにお知らせにだけ残す。
 */
class RadarNotices(
    private val source: PendingRadarNotices,
    private val inbox: NotificationInbox,
    private val post: suspend (AppNotification, silent: Boolean, alertAgain: Boolean) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** 会話の画面を見ている。端末通知を出さず、お知らせにだけ残す。 */
    @Volatile var quiet: Boolean = false

    private class Burst(val id: String, val startedAt: Long, val events: List<RadarEvent>, val alertedAt: Long?)

    private val mutex = Mutex()
    private val bursts = HashMap<String, Burst>()

    suspend fun deliver() = mutex.withLock {
        val pending = source.pendingNotices()
        if (pending.isEmpty()) return@withLock
        val at = now()
        bursts.values.removeAll { at - it.startedAt >= BURST_MS }
        pending.sortedBy { it.at }.groupBy { it.origin?.key ?: it.id }.forEach { (key, events) ->
            val previous = bursts[key]?.takeIf { inbox[it.id]?.read == false }
            val id = previous?.id ?: "$ID_PREFIX$key$SEPARATOR${events.first().at}"
            val all = previous?.events.orEmpty() + events
            val notification = all.toMatchNotification(id)
            if (notification == null) {
                source.markNoticed(events.mapTo(HashSet()) { it.id })
                return@forEach
            }
            var alertedAt = previous?.alertedAt
            if (events.any { it.alert } && !quiet) {
                val sound = (alertedAt == null || at - alertedAt >= ALERT_GAP_MS)
                post(notification, !sound, sound && previous != null)
                if (sound) alertedAt = at
            } else {
                inbox.add(notification)
            }
            bursts[key] = Burst(id, previous?.startedAt ?: at, all.takeLast(MAX_BURST_EVENTS), alertedAt)
            source.markNoticed(events.mapTo(HashSet()) { it.id })
        }
    }

    companion object {
        /** 続けて見つけた一致を 1 件のお知らせにまとめる間。 */
        const val BURST_MS = 10 * 60_000L
        /** 同じお知らせで音・バイブを鳴らし直すまでの間。 */
        const val ALERT_GAP_MS = 30_000L
        private const val MAX_BURST_EVENTS = 50
        private const val ID_PREFIX = "radar|"
        private const val SEPARATOR = "|"

        /** 一致のお知らせの ID から、その一致の条件（`RadarEventOrigin.key`）と、まとめ始めた一致の時刻を取り出す。ほかのお知らせは null。 */
        fun originOf(id: String): Pair<String, Long>? {
            if (!id.startsWith(ID_PREFIX)) return null
            val rest = id.removePrefix(ID_PREFIX)
            val split = rest.lastIndexOf(SEPARATOR).takeIf { it > 0 } ?: return null
            val since = rest.substring(split + 1).toLongOrNull() ?: return null
            return rest.substring(0, split) to since
        }
    }
}
