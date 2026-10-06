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
}
