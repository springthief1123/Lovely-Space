package io.github.springthief1123.lovelyspace.notify

import io.github.springthief1123.lovelyspace.settings.NoticeRetention
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** お知らせの履歴を端末内に残す先。 */
interface NotificationInboxPersistence {
    fun load(): List<AppNotification>
    fun save(entries: List<AppNotification>)
}

/**
 * お知らせ（通知ベル）の履歴と既読を持つ。端末通知と同じ内容を新しい順に [MAX_ENTRIES] 件まで残す。
 * 通知やお知らせをタップすると [opened] に入り、画面側が該当の画面へ移ってから [consumeOpened] で消す。
 */
class NotificationInbox(
    private val persistence: NotificationInboxPersistence? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _entries = MutableStateFlow(runCatching { persistence?.load() }.getOrNull().orEmpty())

    /**
     * 種類ごとにお知らせを残す時間（設定の「お知らせを残す時間」）。設定を読むまでは null で、何も消さない
     * （既定より長く残す設定の人のお知らせを、起動直後に既定の時間で消さないため）。
     */
    @Volatile var retention: Map<NotificationKind, NoticeRetention>? = null
        set(value) {
            field = value
            removeExpired()
        }

    /** その種類のお知らせを残す時間。消さない・設定をまだ読んでいないときは null。 */
    fun retentionMillis(kind: NotificationKind): Long? = retention?.get(kind)?.millis

    /** 残す時間を過ぎたお知らせか。 */
    fun isExpired(notification: AppNotification, now: Long = this.now()): Boolean =
        retentionMillis(notification.kind)?.let { now - notification.at >= it } ?: false

    /** 残す時間を過ぎて消したお知らせの ID を受け取る（端末に残っている同じ通知も消すため）。 */
    @Volatile var onExpired: ((Set<String>) -> Unit)? = null

    val entries: StateFlow<List<AppNotification>> = _entries.asStateFlow()

    private val _opened = MutableStateFlow<AppNotification?>(null)
    val opened: StateFlow<AppNotification?> = _opened.asStateFlow()

    /** [opened] をお知らせの「入室する」で開いた（入室前の確認を飛ばして入る）。[consumeOpened] で戻す。 */
    var openedToEnter: Boolean = false
        private set

    /** 履歴に加える。常駐通知など履歴に残さない種類は何もしない。 */
    fun add(notification: AppNotification) {
        if (!notification.kind.logged) return
        val at = now()
        val expired = expiredIds(at) - notification.id
        change { entries -> (listOf(notification) + entries.filterNot { it.id == notification.id || it.id in expired }).take(MAX_ENTRIES) }
        if (expired.isNotEmpty()) onExpired?.invoke(expired)
    }

    /** 残す時間を過ぎたお知らせを消す（ベルを開いたとき・画面を見ている間は 1 分ごと）。 */
    fun removeExpired() {
        val expired = expiredIds(now())
        if (expired.isEmpty()) return
        change { entries -> entries.filterNot { it.id in expired } }
        onExpired?.invoke(expired)
    }

    private fun expiredIds(at: Long): Set<String> = _entries.value.filter { isExpired(it, at) }.map { it.id }.toSet()

    operator fun get(id: String): AppNotification? = _entries.value.firstOrNull { it.id == id }

    fun markRead(ids: Set<String>) {
        if (_entries.value.none { it.id in ids && !it.read }) return
        change { entries -> entries.map { if (it.id in ids) it.copy(read = true) else it } }
    }

    fun markAllRead() = markRead(_entries.value.map { it.id }.toSet())

    /** お知らせを開く。既読にして、画面側に開く先を渡す。履歴に無いものは何もしない。[enter] は確認を飛ばして入室する。 */
    fun open(id: String, enter: Boolean = false): AppNotification? {
        val entry = get(id) ?: return null
        markRead(setOf(id))
        openedToEnter = enter
        return entry.copy(read = true).also { _opened.value = it }
    }

    /** お知らせを 1 件消す（ベルで横にスワイプしたとき）。 */
    fun remove(id: String) {
        if (get(id) == null) return
        change { entries -> entries.filterNot { it.id == id } }
    }

    /** 履歴に残していない通知（常駐など）を開く。開く先だけを画面側に渡す。 */
    fun show(notification: AppNotification) {
        openedToEnter = false
        _opened.value = notification
    }

    fun consumeOpened() {
        openedToEnter = false
        _opened.value = null
    }

    fun clear() = change { emptyList() }

    private fun change(block: (List<AppNotification>) -> List<AppNotification>) {
        _entries.update(block)
        runCatching { persistence?.save(_entries.value) }
    }

    companion object {
        const val MAX_ENTRIES = 100
    }
}

/** 未読の件数。 */
val List<AppNotification>.unreadCount: Int get() = count { !it.read }
