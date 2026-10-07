package io.github.springthief1123.lovelyspace.notify

import io.github.springthief1123.lovelyspace.settings.NotificationPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationInboxTest {
    private class MemoryPersistence(var saved: List<AppNotification> = emptyList()) : NotificationInboxPersistence {
        override fun load() = saved
        override fun save(entries: List<AppNotification>) { saved = entries }
    }

    // 合成データ。本家の実データは使わない。
    private fun entry(id: String, kind: NotificationKind = NotificationKind.RADAR_MATCH, at: Long = 1_000L) =
        AppNotification(id, kind, "合成の見出し", "合成の本文", NotificationTarget.Radar, at, message = "合成の待機メッセージ")

    @Test fun newestComesFirstAndSurvivesRestart() {
        val disk = MemoryPersistence()
        val inbox = NotificationInbox(disk)
        inbox.add(entry("a"))
        inbox.add(entry("b"))
        assertEquals(listOf("b", "a"), inbox.entries.value.map { it.id })
        assertEquals(listOf("b", "a"), NotificationInbox(disk).entries.value.map { it.id })
    }

    @Test fun keepsOnlyTheNewestEntries() {
        val inbox = NotificationInbox()
        repeat(NotificationInbox.MAX_ENTRIES + 5) { inbox.add(entry("n$it")) }
        assertEquals(NotificationInbox.MAX_ENTRIES, inbox.entries.value.size)
        assertEquals("n${NotificationInbox.MAX_ENTRIES + 4}", inbox.entries.value.first().id)
    }

    @Test fun ongoingStatusIsNotLogged() {
        val inbox = NotificationInbox()
        inbox.add(entry("status", NotificationKind.ONGOING))
        assertTrue(inbox.entries.value.isEmpty())
    }

    @Test fun openingMarksReadAndHandsTheTargetToTheScreen() {
        val disk = MemoryPersistence()
        val inbox = NotificationInbox(disk)
        inbox.add(entry("a"))
        inbox.add(entry("b"))
        assertEquals(2, inbox.entries.value.unreadCount)

        inbox.open("a")

        assertEquals("a", inbox.opened.value?.id)
        assertTrue(inbox["a"]!!.read)
        assertFalse(inbox["b"]!!.read)
        assertTrue(disk.saved.first { it.id == "a" }.read)
        inbox.consumeOpened()
        assertNull(inbox.opened.value)
    }

    @Test fun openingAnUnknownEntryDoesNothing() {
        val inbox = NotificationInbox()
        assertNull(inbox.open("missing"))
        assertNull(inbox.opened.value)
    }

    @Test fun markAllRead() {
        val inbox = NotificationInbox()
        inbox.add(entry("a"))
        inbox.add(entry("b"))
        inbox.markAllRead()
        assertEquals(0, inbox.entries.value.unreadCount)
    }

    @Test fun storageFailureDoesNotBreakTheInbox() {
        val broken = object : NotificationInboxPersistence {
            override fun load(): List<AppNotification> = throw IllegalStateException("storage")
            override fun save(entries: List<AppNotification>): Unit = throw IllegalStateException("storage")
        }
        val inbox = NotificationInbox(broken)
        inbox.add(entry("a"))
        assertEquals(listOf("a"), inbox.entries.value.map { it.id })
    }

    @Test fun messageIsLeftOutWhenTheUserAsksSo() {
        val n = entry("a")
        assertEquals("合成の本文\n合成の待機メッセージ", n.body(NotificationPreview.FULL))
        assertEquals("合成の本文\n合成の待機メッセージ", n.body(NotificationPreview.HIDE_ON_LOCK_SCREEN))
        assertEquals("合成の本文", n.body(NotificationPreview.NO_MESSAGE))
        assertTrue(n.publicOnLockScreen(NotificationPreview.FULL))
        assertFalse(n.publicOnLockScreen(NotificationPreview.HIDE_ON_LOCK_SCREEN))
        assertFalse(n.publicOnLockScreen(NotificationPreview.NO_MESSAGE))
    }
}
