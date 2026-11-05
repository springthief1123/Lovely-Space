package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.core.chat.ChatRoomRef
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
    private val pinKey = roomIdentity(full)

    private class Memory : AutoEntryPersistence {
        var record: AutoEntryRecord? = null
        override fun load() = record
        override fun save(record: AutoEntryRecord) { this.record = record }
    }

    private fun TestScope.controller(persistence: AutoEntryPersistence = AutoEntryPersistence.None): Triple<AutoEntryController, MutableList<NotificationTarget.Room>, MutableList<String>> {
        val c = AutoEntryController(persistence = persistence, now = { now })
        val received = mutableListOf<NotificationTarget.Room>()
        val finished = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.requests.toList(received) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.finished.toList(finished) }
        c.ready = true
        return Triple(c, received, finished)
    }

    private fun ref(room: Room) = ChatRoomRef(zenkoku.host, room.id, "合成", room.genreKey)

    @Test fun opensTheEntryOfAnAutoPinThatOpened() = runTest {
        val (c, received) = controller()
        assertTrue(c.offerPin(pinKey, full, open))
        assertEquals(listOf(NotificationTarget.Room(zenkoku.host, "zenkoku", 42)), received)
    }

    @Test fun doesNothingWhenNotReady() = runTest {
        val (c, received) = controller()
        c.ready = false
        assertFalse("会話中・背景・ロック中", c.offerPin(pinKey, full, open))
        assertTrue(received.isEmpty())
    }

    @Test fun onlyEntersWhenTheWaitingMessageIsUnchanged() = runTest {
        val (c, received) = controller()
        assertFalse("募集文が変わった（別の部屋かもしれない）", c.offerPin(pinKey, full, open.copy(message = "別の募集文")))
        assertFalse("募集文が空で照合できない", c.offerPin(pinKey, full.copy(message = ""), open.copy(message = "")))
        assertFalse("満室", c.offerPin(pinKey, full, full))
        assertTrue(received.isEmpty())
    }

    @Test fun waitsBeforeTheSameRoomButHasNoAttemptLimit() = runTest {
        val (c, received) = controller()
        assertTrue(c.offerPin(pinKey, full, open))
        assertFalse("同じ部屋へは 60 秒空ける", c.offerPin(pinKey, full, open))
        // 入れなかった回は数えないので、何度空いても開く。
        repeat(10) {
            now += AutoEntryController.COOLDOWN_MS
            assertTrue(c.offerPin(pinKey, full, open))
        }
        assertEquals(11, received.size)
    }

    @Test fun enteringTheRoomFinishesThatOneTarget() = runTest {
        val (c, _, finished) = controller()
        c.entered(ref(open))
        assertTrue("自動で開いていない部屋への入室では何も終えない", finished.isEmpty())
        assertTrue(c.offerPin(pinKey, full, open))
        c.entered(ref(open.copy(id = 7)))
        assertTrue("別の部屋", finished.isEmpty())
        c.entered(ref(open))
        assertEquals(listOf(AutoEntryController.pinTarget(pinKey)), finished)
        c.entered(ref(open))
        assertEquals("一度だけ", 1, finished.size)
    }

    @Test fun doesNotCountWhenNoScreenIsListening() = runTest {
        val c = AutoEntryController(now = { now }).apply { ready = true }
        assertFalse(c.offerPin(pinKey, full, open))
    }

    @Test fun keepsTheWaitAcrossARestart() = runTest {
        val memory = Memory()
        val (first) = controller(memory)
        assertTrue(first.offerPin(pinKey, full, open))
        // アプリが落ちて開き直された。
        val (restarted) = controller(memory)
        assertFalse("同じ部屋への 60 秒の待ちも引き継ぐ", restarted.offerPin(pinKey, full, open))
        now += AutoEntryController.COOLDOWN_MS
        assertTrue(restarted.offerPin(pinKey, full, open))
    }

    @Test fun aWaitlistEntryEntersOnlyWhenTurnedOnForIt() = runTest {
        val (c, received, finished) = controller()
        assertFalse("1 件ずつオンにしていない", c.offer(opened()))
        val pinned = opened().copy(autoEnter = true)
        assertFalse("募集文が変わったら入らない", c.offer(pinned.copy(openedRoom = open.copy(message = "別の募集文"))))
        assertTrue(c.offer(pinned))
        assertEquals(1, received.size)
        c.entered(ref(open))
        assertEquals(listOf(AutoEntryController.waitlistTarget(pinned.key)), finished)
    }

    @Test fun aNamedPersonsNewRoomEntersOnlyForAnExactNameWhileEnabled() = runTest {
        val (c, received, finished) = controller()
        val rule = CandidateRule(id = "r", label = "合成", genreKey = "zenkoku", term = "合成の名前", autoEnterUntil = CandidateRule.AUTO_ENTER_ON)
        assertTrue(c.offerNamed(rule, open))
        assertEquals(listOf(NotificationTarget.Room(zenkoku.host, "zenkoku", 42)), received)
        val other = open.copy(id = 43)
        assertFalse("自動入室がオフ", c.offerNamed(rule.copy(autoEnterUntil = 0L), other))
        assertFalse("表示名に含むだけ", c.offerNamed(rule.copy(mode = CandidateMode.DISPLAY_TEXT, term = "合成"), other))
        assertFalse("名前が違う", c.offerNamed(rule, other.copy(name = "別の名前")))
        assertFalse("満室", c.offerNamed(rule, other.copy(status = RoomStatus.FULL, action = RoomAction.NONE)))
        now += 30L * 24 * 3_600_000
        assertTrue("期限はない", c.offerNamed(rule, other))
        assertFalse("以前の 3 時間の期限が過ぎた記録", c.offerNamed(rule.copy(autoEnterUntil = now - 1), open.copy(id = 44)))
        c.entered(ref(other))
        assertEquals(listOf(AutoEntryController.candidateTarget("r")), finished)
    }
}
