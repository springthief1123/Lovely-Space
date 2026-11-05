package io.github.springthief1123.lovelyspace.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * 混んだジャンルの一覧（部屋が開いては閉じ、ページが次々にずれる）を合成で動かし、巡回（[RoomPageSchedule]）と
 * 新着の確認で読んだページから組み立てた一覧（[RoomPageWindow.rooms]）が、本家の並び（立てた順）と部屋を保つかを見る。
 * 本家の実データは使わない。
 */
class WindowSimulationTest {
    private class SiteRoom(val id: Long, val created: Long, val closeAt: Long)

    @Test fun busyGenreKeepsTheSiteOrderAndDoesNotLoseRoomsPushedPastUnreadPages() {
        val rnd = Random(7)
        var tick = 0L // 1 tick = 1.5 秒（通信の最小間隔）
        var nextId = 900_000_000L
        val site = ArrayList<SiteRoom>()
        // 部屋は最長 8 時間（19200 tick）で閉じる。6 秒ごとに 1 部屋開き、2000 部屋あまり（30 ページ以上）になる。
        fun open(created: Long) {
            site += SiteRoom(nextId, created, created + rnd.nextLong(200, 19_200)); nextId += rnd.nextLong(1, 30)
        }
        repeat(1_500) { i -> open(-(1_500 - i).toLong() * 4) }
        fun listed() = site.filter { it.closeAt > tick }.sortedByDescending { it.id }
        fun read(n: Int): RoomListPage {
            val all = listed()
            val rooms = all.drop((n - 1) * 60).take(60).map {
                Room(it.id, "chah", RoomStatus.WAITING, RoomAction.ENTER, ((tick - it.created) * 1.5).seconds, "合成${it.id}", Gender.FEMALE, 20, null, "合成募集文")
            }
            return RoomListPage("chah", rooms, null, null, n, ((all.size + 59) / 60).coerceAtLeast(1), emptyMap(), null)
        }
        var window = RoomPageWindow()
        val schedule = RoomPageSchedule(clock = { tick * 1_500 }, lapInterval = { 4_000 })
        var worstMissing = 0
        repeat(12_000) { step ->
            tick++
            if (tick % 4 == 0L) open(tick)
            // 新着の確認（1 ページ目）と巡回が交互に読む。
            if (tick % 2 == 0L) window = window.observe(read(1))
            else if (schedule.waitMs(window) == 0L) {
                val p = schedule.next(window)
                window = window.observe(read(p))
                schedule.completed(p, window)
            }
            if (step > 3_000 && step % 50 == 0) {
                val shown = window.rooms.map { it.id }
                // 本家と同じく、立てた順（ID の大きい順）に並ぶ。
                assertEquals(shown.sortedDescending(), shown)
                assertEquals(shown.size, shown.toSet().size)
                worstMissing = maxOf(worstMissing, (listed().map { it.id }.toSet() - shown.toSet()).size)
            }
        }
        // 読んでいないページへ何ページも押し出された部屋を落とさない（直す前は 1 回に 150 部屋以上が抜けた）。
        assertTrue("一覧から抜けた部屋 $worstMissing", worstMissing <= 60)
    }
}
