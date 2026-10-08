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

    @Test fun trackingExplainsWhyItIsUnavailable() {
        assertNull(trackUnavailableReason(room, allowEntry = true, tracking = false))
        assertEquals("名前が表示されていない部屋（会話中・満室）は追跡できません。",
            trackUnavailableReason(room.copy(name = null), allowEntry = true, tracking = false))
        assertEquals("最新の一覧でこの部屋を確認できていないため、追跡できません。",
            trackUnavailableReason(room, allowEntry = false, tracking = false))
        // 追跡中は解除できるので理由を出さない。
        assertNull(trackUnavailableReason(room.copy(name = null), allowEntry = false, tracking = true))
    }

    @Test fun waitlistAndRadarWordingFollowsTheNotificationPermission() {
        assertEquals("順番待ちに登録しました。空いたら通知します（6時間まで）。", waitlistNoticeText(true, 6))
        assertEquals("順番待ちに登録しました。通知がオフのため、空いても知らせられません。", waitlistNoticeText(false, 6))
        assertTrue("通知がオフ" in backgroundRadarNoticeText())
    }

    @Test fun cardsShowWhatTappingDoes() {
        assertEquals("入室へ", cardActionLabel(RoomAction.ENTER))
        assertEquals("覗く", cardActionLabel(RoomAction.PEEK))
        assertNull(cardActionLabel(RoomAction.NONE))
    }
}
