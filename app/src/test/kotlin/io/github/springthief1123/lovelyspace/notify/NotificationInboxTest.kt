package io.github.springthief1123.lovelyspace.notify

import io.github.springthief1123.lovelyspace.settings.NotificationPreview
import io.github.springthief1123.lovelyspace.settings.NoticeRetention
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

    @Test fun enteringFromTheBellIsHandedToTheScreenOnce() {
        val inbox = NotificationInbox()
        inbox.add(entry("a"))
        inbox.open("a", enter = true)
        assertTrue(inbox.openedToEnter)
        inbox.consumeOpened()
        assertFalse(inbox.openedToEnter)
        inbox.open("a")
        assertFalse(inbox.openedToEnter)
    }

    @Test fun removingOneEntryKeepsTheOthers() {
        val disk = MemoryPersistence()
        val inbox = NotificationInbox(disk)
        inbox.add(entry("a"))
        inbox.add(entry("b"))
        inbox.remove("a")
        assertNull(inbox["a"])
        assertEquals(listOf("b"), disk.saved.map { it.id })
    }

    @Test fun noticesExpireByTheirKindsRetention() {
        var now = 0L
        val disk = MemoryPersistence()
        val inbox = NotificationInbox(disk) { now }
        inbox.add(entry("pin", NotificationKind.WAITLIST, at = 0))
        inbox.add(entry("match", NotificationKind.RADAR_MATCH, at = 0))
        inbox.add(entry("entry", NotificationKind.ROOM_ENTRY, at = 0))
        now = 2 * 60 * 60_000L
        // 設定を読むまでは消さない（既定より長く残す人のお知らせを、起動直後に消さない）。
        inbox.removeExpired()
        assertEquals(3, inbox.entries.value.size)
        // 既定: 空き 1 時間・条件の新着 1 時間・入室者あり 消さない。条件の新着だけ 3 時間に延ばす。
        inbox.retention = NoticeRetention.defaults + (NotificationKind.RADAR_MATCH to NoticeRetention.HOURS_3)
        assertEquals(listOf("entry", "match"), inbox.entries.value.map { it.id })
        assertEquals(listOf("entry", "match"), disk.saved.map { it.id })
        now = 3 * 60 * 60_000L
        inbox.removeExpired()
        assertEquals(listOf("entry"), inbox.entries.value.map { it.id })
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
