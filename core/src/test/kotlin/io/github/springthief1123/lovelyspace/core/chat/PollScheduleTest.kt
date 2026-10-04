package io.github.springthief1123.lovelyspace.core.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class PollScheduleTest {
    private val filled = ChatState(fromSize = 0, isFilledRoom = true, loadAverage = 368)
    private val waiting = ChatState(fromSize = 0, isFilledRoom = false, loadAverage = 50)

    private fun update(state: ChatState, lines: Int) = ChatUpdate(
        state = state,
        newLines = List(lines) { ChatLine("a", false, "t", null) },
        clearLog = false,
        someoneEntered = false,
        guestLeft = false,
        canBanGuest = false,
        endMessage = null,
        information = "",
        romCount = null,
        isPublic = null,
    )

    @Test
    fun backsOffBySecondWhenNothingNew() {
        val s = PollSchedule(waiting)
        val delays = List(4) {
            s.onPollStarted(waiting)
            s.onResponse(update(waiting, 0))
        }
        assertEquals(listOf(6_000L, 7_000L, 8_000L, 9_000L), delays)
    }

    @Test
    fun twoSecondsAfterNewLinesInFilledRoom() {
        val s = PollSchedule(filled)
        s.onPollStarted(filled)
        assertEquals(2_000L, s.onResponse(update(filled, 1)))
        // 新着がなければ延びた間隔に戻る。
        s.onPollStarted(filled)
        assertEquals(7_000L, s.onResponse(update(filled, 0)))
    }

    @Test
    fun highLoadStretchesWaitingRoom() {
        val busy = waiting.copy(loadAverage = 434)
        val s = PollSchedule(busy)
        s.onPollStarted(busy)
        assertEquals(10_000L, s.onResponse(update(busy, 0)))
    }

    @Test
    fun sendingResetsInterval() {
        val s = PollSchedule(waiting)
        repeat(5) { s.onPollStarted(waiting); s.onResponse(update(waiting, 0)) }
        s.onSend()
        assertEquals(5_000L, s.onResponse(update(waiting, 1)))
    }

    @Test
    fun capsAtMax() {
        val small = waiting.copy(reloadIntervalMaxSeconds = 7)
        val s = PollSchedule(small)
        val last = List(5) { s.onPollStarted(small); s.onResponse(update(small, 0)) }.last()
        assertEquals(7_000L, last)
    }
}
