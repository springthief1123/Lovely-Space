package io.github.springthief1123.lovelyspace.core

import org.junit.Assert.*
import org.junit.Test

class RoomPageWindowTest {
    private fun room(id: Long) = Room(id, "zenkoku", RoomStatus.WAITING, RoomAction.ENTER, null, "合成$id", Gender.MALE, 25, null, "合成募集文")
    private fun page(number: Int, rooms: List<Room>, last: Int = 3) = RoomListPage("zenkoku", rooms, null, null, number, last, emptyMap(), null)

    @Test fun refreshReplacesOnePagePreservesOthersAndUsesLatestStatusAcrossPageMoves() {
        val first = room(1)
        val full = first.copy(status = RoomStatus.FULL, action = RoomAction.NONE, name = null)
        val window = RoomPageWindow().observe(page(1, listOf(first, room(2))))
            .observe(page(2, listOf(room(3), full)))
            .observe(page(1, listOf(room(4))))
        assertEquals(listOf(4L, 3L, 1L), window.rooms.map { it.id })
        assertEquals(RoomAction.NONE, window.rooms.last().action)
        assertFalse(window.rooms.any { it.id == 2L })
    }
    @Test fun duplicateOnOlderPageCannotOverrideNewerProfileAndReducedPageCountDropsOldPages() {
        val original = room(1)
        val newer = original.copy(name = "変更した合成")
        val window = RoomPageWindow().observe(page(2, listOf(original, room(2))))
            .observe(page(1, listOf(newer)))
        assertEquals("変更した合成", window.rooms.first().name)
        assertEquals(2, window.rooms.size)
        val shrunk = window.observe(page(1, listOf(newer), last = 1))
        assertEquals(listOf(newer), shrunk.rooms)
        assertEquals(setOf(1), shrunk.pages.keys)
    }
    @Test fun olderCachedPageCannotOverrideTheLatestActionOrReExpandThePageCount() {
        val old = room(1)
        val latest = old.copy(status = RoomStatus.FULL, action = RoomAction.PEEK, name = null)
        val window = RoomPageWindow().observe(page(1, listOf(latest), last = 2), observationRevision = 10)
            .observe(page(2, listOf(old, room(2)), last = 3), observationRevision = 2)
        assertEquals(latest, window.rooms.first())
        assertEquals(2, window.lastPage)
        val shrunk = window.observe(page(1, listOf(latest), last = 1), observationRevision = 11)
            .observe(page(2, listOf(old), last = 3), observationRevision = 2)
        assertEquals(listOf(latest), shrunk.rooms)
        assertEquals(1, shrunk.lastPage)
        assertEquals(setOf(1), shrunk.pages.keys)
    }
    @Test fun delayedOlderObservationOfTheSamePageDoesNotReplaceFreshContents() {
        val latest = room(1).copy(message = "新しい合成")
        val window = RoomPageWindow().observe(page(1, listOf(latest)), observationRevision = 10)
        assertSame(window, window.observe(page(1, listOf(room(1))), observationRevision = 2))
        assertEquals(listOf(latest), window.rooms)
    }
    @Test fun schedulePrioritizesNewRoomsWithoutRestartingTheRemainingPageCursor() {
        var now = 0L
        val schedule = RoomPageSchedule { now }
        assertEquals(1, schedule.next(10)); schedule.completed(1, 10)
        now = 3_000
        assertEquals(2, schedule.next(10)); schedule.completed(2, 10)
        now = 20_000
        assertEquals(1, schedule.next(10)); schedule.completed(1, 10)
        now = 23_000
        assertEquals(3, schedule.next(10))
        assertEquals(3, schedule.next(10)) // 失敗したページは完了を通知せず同じ位置で再試行。
    }
    @Test fun scheduleReadsEveryUnreadPageFirstAndKeepsTheHeadAtTheSlowerSweepInterval() {
        var now = 0L
        val schedule = RoomPageSchedule { now }
        assertEquals(1, schedule.next(4, emptySet())); schedule.completed(1, 4)
        now = 3_000
        assertEquals(2, schedule.next(4, setOf(1)))
        now = 6_000
        assertEquals(2, schedule.next(4, setOf(1))) // 失敗したページは読めるまで同じ位置。
        now = 9_000
        assertEquals(2, schedule.next(4, setOf(1))); schedule.completed(2, 4)
        now = 12_000
        assertEquals(3, schedule.next(4, setOf(1, 2))); schedule.completed(3, 4)
        now = 21_000
        assertEquals(1, schedule.next(4, setOf(1, 2, 3))); schedule.completed(1, 4)
        now = 24_000
        assertEquals(4, schedule.next(4, setOf(1, 2, 3))); schedule.completed(4, 4)
        // 全ページを読んだら、1 ページ目を 4 秒ごとに優先する通常の巡回に戻る。
        now = 24_500
        assertEquals(2, schedule.next(4, setOf(1, 2, 3, 4)))
        now = 25_000
        assertEquals(1, schedule.next(4, setOf(1, 2, 3, 4)))
    }
}
