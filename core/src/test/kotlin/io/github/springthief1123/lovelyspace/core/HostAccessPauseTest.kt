package io.github.springthief1123.lovelyspace.core

import org.junit.Assert.*
import org.junit.Test

class HostAccessPauseTest {
    private var now = -1_000_000L
    private val pauses = HostAccessPause({ now }, { 0L })

    @Test fun secondsAndHttpDatesUseTheSameMonotonicDeadline() {
        pauses.observe("chat.shalove.net", 429, "60")
        assertEquals(60_000L, pauses.remaining("chat.shalove.net"))
        pauses.observe("chat.shalove.net", 503, "Thu, 1 Jan 1970 00:02:00 GMT")
        assertEquals(120_000L, pauses.remaining("chat.shalove.net"))
        assertEquals(0L, pauses.remaining("lr.chat.shalove.net"))
        now += 120_000
        assertEquals(0L, pauses.remaining("chat.shalove.net"))
    }

    @Test fun allRestrictionStatusesFallbackToTheExistingFailureInterval() {
        for (status in listOf(403, 429, 503)) {
            pauses.observe("host$status", status, "invalid")
            assertEquals(RoomPageSchedule.ERROR_INTERVAL_MS, pauses.remaining("host$status"))
        }
        pauses.observe("normal", 200, "999")
        assertEquals(0L, pauses.remaining("normal"))
    }

    @Test fun shorterResponsesDoNotClearAnExistingPause() {
        pauses.observe("host", 429, "60")
        pauses.observe("host", 403, null)
        pauses.observe("host", 503, "0")
        assertEquals(60_000L, pauses.remaining("host"))
    }
}
