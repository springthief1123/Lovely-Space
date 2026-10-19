package io.github.springthief1123.lovelyspace.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshPacingTest {
    @Test
    fun defaultsKeepTheCurrentPace() {
        val pacing = RefreshPacing()
        assertEquals(1_500L, pacing.minIntervalMs)
        assertEquals(3_000L, pacing.hotMs)
        assertEquals(4_000L, pacing.searchHeadMs)
        assertEquals(20_000L, pacing.publicRoomMs)
        assertEquals(4_000L, pacing.listCacheTtlMs)
    }

    @Test
    fun valuesBelowTheFloorAreRaised() {
        val pacing = RefreshPacing(minIntervalMs = 200, hotMs = 100, searchHeadMs = 500, radarHeadMs = 0, waitlistMs = -1, publicRoomMs = 1_000).sanitized()
        assertEquals(RefreshPacing.FLOOR_MIN_INTERVAL_MS, pacing.minIntervalMs)
        assertEquals(RefreshPacing.FLOOR_REFRESH_MS, pacing.hotMs)
        assertEquals(RefreshPacing.FLOOR_REFRESH_MS, pacing.searchHeadMs)
        assertEquals(RefreshPacing.FLOOR_REFRESH_MS, pacing.radarHeadMs)
        assertEquals(RefreshPacing.FLOOR_REFRESH_MS, pacing.waitlistMs)
        assertEquals(RefreshPacing.FLOOR_REFRESH_MS, pacing.publicRoomMs)
    }

    @Test
    fun cacheFollowsTheShortestListRefreshButNeverExceedsTwentySeconds() {
        assertEquals(2_000L, RefreshPacing(waitlistMs = 2_000).listCacheTtlMs)
        assertEquals(20_000L, RefreshPacing(searchHeadMs = 30_000, radarHeadMs = 30_000, waitlistMs = 30_000).listCacheTtlMs)
    }

    @Test
    fun everyChoiceRespectsTheFloors() {
        assertTrue(RefreshPacing.MIN_INTERVAL_CHOICES.all { it >= RefreshPacing.FLOOR_MIN_INTERVAL_MS })
        val refresh = RefreshPacing.HOT_CHOICES + RefreshPacing.LIST_CHOICES + RefreshPacing.WAITLIST_CHOICES + RefreshPacing.PUBLIC_ROOM_CHOICES
        assertTrue(refresh.all { it >= RefreshPacing.FLOOR_REFRESH_MS })
    }

    @Test
    fun theFirstPageCheckUsesAtMostHalfOfTheRequests() {
        // 既定（1.5 秒間隔）なら 1 ジャンルは 3 秒ごと。2 ジャンルなら 1 ページ目だけで半分を超えないよう 6 秒ごとに伸ばす。
        assertEquals(3_000L, RefreshPacing().hotIntervalMs(1))
        assertEquals(6_000L, RefreshPacing().hotIntervalMs(2))
        // 最小間隔を 3 秒のままにしていれば、1 ジャンルでも 6 秒ごと。
        assertEquals(6_000L, RefreshPacing(minIntervalMs = 3_000).hotIntervalMs(1))
        // 下限は 2 秒。
        assertEquals(2_000L, RefreshPacing(minIntervalMs = 1_000, hotMs = 500).hotIntervalMs(1))
    }
}
