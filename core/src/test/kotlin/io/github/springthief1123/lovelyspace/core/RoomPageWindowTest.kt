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
        // 1 ページ目から消えた 2 は後ろへずれただけかもしれないので、もう一度 1 ページ目を読むまで残す。
        assertEquals(listOf(4L, 2L, 3L, 1L), window.rooms.map { it.id })
        assertEquals(RoomAction.NONE, window.rooms.last().action)
        assertFalse(window.observe(page(1, listOf(room(4)))).rooms.any { it.id == 2L })
    }
    @Test fun roomsThatMoveToAnotherPageBetweenReadsStayInTheList() {
        // 前のページの部屋が閉じて 3 が 1 ページ目へ繰り上がった。1 ページ目を読んだ後に 2 ページ目を読むと、3 はどちらにも無い。
        val window = RoomPageWindow().observe(page(1, listOf(room(1), room(2))))
            .observe(page(2, listOf(room(3), room(4))))
            .observe(page(2, listOf(room(4), room(5))))
        assertEquals(listOf(1L, 2L, 4L, 5L, 3L), window.rooms.map { it.id })
        // 次の周で 1 ページ目に見つかれば、そこに並ぶ。
        val next = window.observe(page(1, listOf(room(3), room(1))))
        assertEquals(listOf(3L, 1L, 2L, 4L, 5L), next.rooms.map { it.id })
        assertTrue(next.carried.keys.none { it.endsWith("/3") })
        // 新しい部屋が開いて後ろのページへずれた部屋は、上から順に読めば同じ周で見つかる。
        val shifted = RoomPageWindow().observe(page(1, listOf(room(1), room(2))))
            .observe(page(2, listOf(room(3))))
            .observe(page(1, listOf(room(9), room(1))))
        assertEquals(listOf(9L, 1L, 2L, 3L), shifted.rooms.map { it.id })
        assertEquals(listOf(9L, 1L, 2L, 3L), shifted.observe(page(2, listOf(room(2), room(3)))).rooms.map { it.id })
        assertTrue(shifted.observe(page(2, listOf(room(2), room(3)))).carried.isEmpty())
        // 1 ページだけの一覧では、消えた部屋は閉じた部屋なので残さない。
        val single = RoomPageWindow().observe(page(1, listOf(room(1), room(2)), last = 1)).observe(page(1, listOf(room(1)), last = 1))
        assertEquals(listOf(1L), single.rooms.map { it.id })
        // 同じページをもう一度読んでも無ければ、閉じた部屋として外す。
        assertEquals(listOf(1L, 2L, 4L, 5L), window.observe(page(2, listOf(room(4), room(5)))).rooms.map { it.id })
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
    @Test fun scheduleReadsEveryPageInOrderAndStartsTheNextLapAfterTheSettingInterval() {
        var now = 0L
        var lap = 4_000L
        val schedule = RoomPageSchedule(clock = { now }) { lap }
        assertEquals(1, schedule.next(3)); assertEquals(0, schedule.waitMs(3)); schedule.completed(1, 3)
        now = 3_000
        assertEquals(2, schedule.next(3)); assertEquals(0, schedule.waitMs(3)); schedule.completed(2, 3)
        now = 6_000
        assertEquals(3, schedule.next(3))
        assertEquals(3, schedule.next(3)) // 失敗したページは完了を通知せず同じ位置で再試行。
        schedule.completed(3, 3)
        // 全ページを読んだら 1 ページ目に戻る。前の周の始まりから 4 秒経っているので待たない。
        assertEquals(1, schedule.next(3)); assertEquals(0, schedule.waitMs(3))
        // 設定の間隔が長ければ、新しい周の始まりだけを待つ。
        lap = 10_000
        assertEquals(4_000, schedule.waitMs(3))
        // ページ数が減って次のページが無くなったら 1 ページ目へ。
        schedule.completed(1, 3)
        assertEquals(1, schedule.next(1))
    }
    @Test fun singlePageListIsReadAgainAtTheSettingInterval() {
        var now = 0L
        val schedule = RoomPageSchedule(clock = { now }) { 4_000 }
        assertEquals(1, schedule.next(1)); schedule.completed(1, 1)
        now = 3_000
        assertEquals(1, schedule.next(1)); assertEquals(1_000, schedule.waitMs(1))
        now = 4_000
        assertEquals(0, schedule.waitMs(1))
    }
}
