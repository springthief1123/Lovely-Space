package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.notify.NotificationTarget
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AutoEntryControllerTest {
    // 合成データ。本家の実データは使わない。
    private val full = Room(42, "zenkoku", RoomStatus.FULL, RoomAction.NONE, null, null, Gender.UNKNOWN, null, null, "合成の募集文")
    private val open = full.copy(status = RoomStatus.WAITING, action = RoomAction.ENTER, name = "合成の名前", gender = Gender.FEMALE)
    private val zenkoku = Genres["zenkoku"]!!

    private fun opened(room: Room = full, openedRoom: Room = open) =
        WaitlistEntry(room, RoomQuery(zenkoku), 0L, 3_600_000L, WaitlistStatus.OPENED, openedRoom = openedRoom, changedAt = 0L)

    private var now = 1_000_000L
    private var startedAt = 1_000_000L

    private fun TestScope.controller(): Pair<AutoEntryController, MutableList<NotificationTarget.Room>> {
        val c = AutoEntryController(startedAt = { startedAt }, now = { now })
        val received = mutableListOf<NotificationTarget.Room>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.requests.toList(received) }
        c.ready = true
        return c to received
    }

    @Test fun opensTheEntryOfTheRoomThatOpenedWhileEnabled() = runTest {
        val (c, received) = controller()
        assertTrue(c.offer(opened()))
        assertEquals(listOf(NotificationTarget.Room(zenkoku.host, "zenkoku", 42)), received)
        assertEquals(1, c.attempts.value)
    }

    @Test fun doesNothingWhenDisabledExpiredOrNotReady() = runTest {
        val (c, received) = controller()
        startedAt = 0L
        assertFalse("無効", c.offer(opened()))
        startedAt = now - AutoEntryController.DURATION_MS
        assertFalse("30 分たった", c.offer(opened()))
        startedAt = now
        c.ready = false
        assertFalse("会話中・背景・ロック中", c.offer(opened()))
        assertTrue(received.isEmpty())
    }

    @Test fun onlyEntersWhenTheWaitingMessageIsUnchanged() = runTest {
        val (c, received) = controller()
        assertFalse("募集文が変わった（別の部屋かもしれない）", c.offer(opened(openedRoom = open.copy(message = "別の募集文"))))
        assertFalse("募集文が空で照合できない", c.offer(opened(room = full.copy(message = ""), openedRoom = open.copy(message = ""))))
        assertTrue(received.isEmpty())
    }

    @Test fun waitsBeforeTheSameRoomAndStopsAfterTheLimit() = runTest {
        val (c, received) = controller()
        assertTrue(c.offer(opened()))
        assertFalse("同じ部屋へは 60 秒空ける", c.offer(opened()))
        now += AutoEntryController.COOLDOWN_MS
        assertTrue(c.offer(opened()))
        repeat(AutoEntryController.MAX_ATTEMPTS - 2) { i ->
            assertTrue(c.offer(opened(room = full.copy(id = 100L + i), openedRoom = open.copy(id = 100L + i))))
        }
        assertFalse("上限", c.offer(opened(room = full.copy(id = 999), openedRoom = open.copy(id = 999))))
        assertEquals(AutoEntryController.MAX_ATTEMPTS, received.size)

        // オンにし直すと数え直す。
        startedAt = now
        assertTrue(c.offer(opened(room = full.copy(id = 999), openedRoom = open.copy(id = 999))))
        assertEquals(1, c.attempts.value)
    }

    @Test fun doesNotCountWhenNoScreenIsListening() = runTest {
        val c = AutoEntryController(startedAt = { startedAt }, now = { now }).apply { ready = true }
        assertFalse(c.offer(opened()))
        assertEquals(0, c.attempts.value)
    }
}
