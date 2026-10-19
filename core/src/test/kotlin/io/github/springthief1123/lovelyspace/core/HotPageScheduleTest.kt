package io.github.springthief1123.lovelyspace.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HotPageScheduleTest {
    private var now = 1_000_000L
    private val kanto = Genres["kanto"]!!
    private val kinki = Genres["kinki"]!!

    @Test
    fun readsTheFirstPageAtTheSetInterval() {
        val schedule = HotPageSchedule({ now }) { RefreshPacing() }
        assertNull(schedule.next(emptyList()))
        assertEquals(kanto to 0L, schedule.next(listOf(kanto)))
        schedule.completed(kanto, success = true)
        now += 1_000
        assertEquals(kanto to 2_000L, schedule.next(listOf(kanto)))
        now += 2_000
        assertEquals(kanto to 0L, schedule.next(listOf(kanto)))
    }

    @Test
    fun takesTurnsBetweenGenresAndStretchesTheInterval() {
        val schedule = HotPageSchedule({ now }) { RefreshPacing() }
        assertEquals(kanto to 0L, schedule.next(listOf(kanto, kinki)))
        schedule.completed(kanto, success = true)
        // まだ読んでいない近畿が先。
        assertEquals(kinki to 0L, schedule.next(listOf(kanto, kinki)))
        now += 1_500
        schedule.completed(kinki, success = true)
        // 2 ジャンルなら 1 ジャンルあたり 6 秒ごと。関東は読んでから 1.5 秒経っている。
        assertEquals(kanto to 4_500L, schedule.next(listOf(kanto, kinki)))
    }

    @Test
    fun waitsTwentySecondsAfterAFailure() {
        val schedule = HotPageSchedule({ now }) { RefreshPacing() }
        schedule.completed(kanto, success = false)
        assertEquals(kanto to RoomPageSchedule.ERROR_INTERVAL_MS, schedule.next(listOf(kanto)))
    }

    @Test
    fun forgetsGenresNoLongerWatched() {
        val schedule = HotPageSchedule({ now }) { RefreshPacing() }
        schedule.completed(kanto, success = true)
        schedule.next(listOf(kinki))
        // 見なくなったジャンルを見直したら、すぐ読む。
        assertEquals(kanto to 0L, schedule.next(listOf(kanto)))
    }
}
