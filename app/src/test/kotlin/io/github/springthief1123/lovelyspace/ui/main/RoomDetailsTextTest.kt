package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.ui.rooms.cardActionLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomDetailsTextTest {
    // 合成データ。本家の実データは使わない。
    private val room = Room(1, "zenkoku", RoomStatus.WAITING, RoomAction.ENTER, null, "合成", Gender.FEMALE, 24, "東京", "合成の募集文")

    @Test fun subtitleListsGenderBeforeAgeAndAreaLikeTheCard() {
        val subtitle = roomDetailsSubtitle(room)
        assertTrue(subtitle.endsWith("女性 · 24歳 · 東京"))
        assertTrue("男性" !in roomDetailsSubtitle(room.copy(gender = Gender.UNKNOWN)))
    }

    @Test fun pinAndRadarWordingFollowsTheNotificationPermission() {
        assertTrue("空いたら通知します" in pinNoticeText(true))
        assertTrue("通知がオフ" in pinNoticeText(false))
        assertTrue("通知がオフ" in backgroundRadarNoticeText())
    }

    @Test fun cardsShowWhatTappingDoes() {
        assertEquals("入室する", cardActionLabel(RoomAction.ENTER))
        assertEquals("覗く", cardActionLabel(RoomAction.PEEK))
        assertNull(cardActionLabel(RoomAction.NONE))
    }
}
