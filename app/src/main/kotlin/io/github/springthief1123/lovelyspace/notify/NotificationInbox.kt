package io.github.springthief1123.lovelyspace.notify

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
class NotificationInbox(private val persistence: NotificationInboxPersistence? = null) {
    private val _entries = MutableStateFlow(runCatching { persistence?.load() }.getOrNull().orEmpty())
    val entries: StateFlow<List<AppNotification>> = _entries.asStateFlow()

    private val _opened = MutableStateFlow<AppNotification?>(null)
    val opened: StateFlow<AppNotification?> = _opened.asStateFlow()

    /** 履歴に加える。常駐通知など履歴に残さない種類は何もしない。 */
    fun add(notification: AppNotification) {
        if (!notification.kind.logged) return
        change { entries -> (listOf(notification) + entries.filterNot { it.id == notification.id }).take(MAX_ENTRIES) }
    }

    operator fun get(id: String): AppNotification? = _entries.value.firstOrNull { it.id == id }

    fun markRead(ids: Set<String>) {
        if (_entries.value.none { it.id in ids && !it.read }) return
        change { entries -> entries.map { if (it.id in ids) it.copy(read = true) else it } }
    }

    fun markAllRead() = markRead(_entries.value.map { it.id }.toSet())

    /** お知らせを開く。既読にして、画面側に開く先を渡す。履歴に無いものは何もしない。 */
    fun open(id: String): AppNotification? {
        val entry = get(id) ?: return null
        markRead(setOf(id))
        return entry.copy(read = true).also { _opened.value = it }
    }

    fun consumeOpened() {
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
