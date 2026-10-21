package io.github.springthief1123.lovelyspace.notify

import io.github.springthief1123.lovelyspace.data.RadarEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** [RadarNotices] が読む・書く、まだお知らせに出していない一致の置き場（`RadarRepository`）。 */
interface PendingRadarNotices {
    fun pendingNotices(): List<RadarEvent>
    suspend fun markNoticed(ids: Set<String>)
    /** 記録に残っている一致のうち [ids] のもの（まとめたお知らせを作り直すのに使う）。 */
    fun events(ids: Set<String>): List<RadarEvent>
}

/** まとめているお知らせ（[RadarNotices]）を端末内に残す先。背景の実行・プロセスの作り直しをまたいで、まとめと音の間隔を守る。 */
interface RadarNoticePersistence {
    fun load(): String?
    fun save(value: String)
}

/** まとめているお知らせを SharedPreferences に JSON で残す。 */
class RadarNoticeStore(private val prefs: android.content.SharedPreferences) : RadarNoticePersistence {
    override fun load(): String? = prefs.getString(KEY, null)
    override fun save(value: String) { prefs.edit().putString(KEY, value).apply() }
    companion object {
        const val PREFS = "radar_notices"
        private const val KEY = "bursts"
    }
}

/**
 * 条件・名前で待つの新しい一致を、お知らせ（通知ベル）と端末通知に出す。前面・背景のどちらで見つけた一致も同じように出す。
 *
 * - 一致はすべてお知らせに残す（レーダーの履歴の代わり）。端末通知は「新着を知らせる」条件・知らせる名前で待つ（[RadarEvent.alert]）だけ。
 * - 同じ条件で続けて見つけた一致は、そのお知らせが未読で [BURST_MS] 以内なら 1 件にまとめて差し替える（新着の確認は数秒ごとなので）。
 * - 立て直しだけの条件の一致（[RadarEvent.onlyRebuilds]）は音を鳴らさない。
 * - 音・バイブは同じお知らせで [ALERT_GAP_MS] に 1 回まで。会話中（[quiet]）は端末通知を出さずにお知らせにだけ残す。
 */
class RadarNotices(
    private val source: PendingRadarNotices,
    private val inbox: NotificationInbox,
    private val post: suspend (AppNotification, silent: Boolean, alertAgain: Boolean) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val persistence: RadarNoticePersistence? = null,
) {
    /** 会話の画面を見ている。端末通知を出さず、お知らせにだけ残す。 */
    @Volatile var quiet: Boolean = false

    /** まとめているお知らせ。[eventIds] はまとめた一致（記録から作り直す）、[alertedAt] は最後に音を鳴らした時刻。 */
    private class Burst(val id: String, val startedAt: Long, val eventIds: List<String>, val alertedAt: Long?)

    private val mutex = Mutex()
    private val bursts: HashMap<String, Burst> = runCatching { persistence?.load()?.let(::decode) }.getOrNull() ?: HashMap()

    suspend fun deliver() = mutex.withLock {
        val pending = source.pendingNotices()
        if (pending.isEmpty()) return@withLock
        val at = now()
        bursts.values.removeAll { at - it.startedAt >= BURST_MS }
        pending.sortedBy { it.at }.groupBy { it.origin?.key ?: it.id }.forEach { (key, events) ->
            val previous = bursts[key]?.takeIf { inbox[it.id]?.read == false }
            val id = previous?.id ?: "$ID_PREFIX$key$SEPARATOR${events.first().at}"
            val earlier = previous?.let { burst -> source.events(burst.eventIds.toSet()).sortedBy { it.at } }.orEmpty()
            val all = earlier + events
            val notification = all.toMatchNotification(id)
            if (notification == null) {
                source.markNoticed(events.mapTo(HashSet()) { it.id })
                return@forEach
            }
            var alertedAt = previous?.alertedAt
            if (events.any { it.alert } && !quiet) {
                // 立て直しだけの条件の一致は鳴らさずに出す。名前で待つは同じ人を待っているので鳴らす。
                val sound = events.any { it.alert && !it.onlyRebuilds } && (alertedAt == null || at - alertedAt >= ALERT_GAP_MS)
                post(notification, !sound, sound && previous != null)
                if (sound) alertedAt = at
            } else {
                inbox.add(notification)
            }
            bursts[key] = Burst(id, previous?.startedAt ?: at, all.takeLast(MAX_BURST_EVENTS).map { it.id }, alertedAt)
            save()
            source.markNoticed(events.mapTo(HashSet()) { it.id })
        }
    }

    private fun save() {
        val store = persistence ?: return
        val json = JSONObject()
        bursts.forEach { (key, b) ->
            json.put(key, JSONObject().put("id", b.id).put("startedAt", b.startedAt).put("events", JSONArray(b.eventIds)).put("alertedAt", b.alertedAt))
        }
        runCatching { store.save(json.toString()) }
    }

    private fun decode(value: String): HashMap<String, Burst> {
        val json = JSONObject(value)
        val result = HashMap<String, Burst>()
        json.keys().forEach { key ->
            val b = json.optJSONObject(key) ?: return@forEach
            val ids = b.optJSONArray("events")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
            result[key] = Burst(b.getString("id"), b.getLong("startedAt"), ids, if (b.isNull("alertedAt")) null else b.getLong("alertedAt"))
        }
        return result
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
