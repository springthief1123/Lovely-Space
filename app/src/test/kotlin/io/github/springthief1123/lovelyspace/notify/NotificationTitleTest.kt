package io.github.springthief1123.lovelyspace.notify

import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.core.RoomStatus
import io.github.springthief1123.lovelyspace.settings.NotificationPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTitleTest {
    // 合成データ。本家の実データは使わない。
    private fun room(name: String?) = Room(1, "zenkoku", RoomStatus.WAITING, RoomAction.ENTER, null, name, Gender.FEMALE, 25, null, "合成の待機メッセージ")

    @Test fun ownerNameGoesIntoTheTitleUnlessMessagesAreHidden() {
        val n = AppNotification("a", NotificationKind.WAITLIST, "ピンした部屋が空きました", "全国", NotificationTarget.Radar, 0L,
            namedTitle = "${room("合成の名前").ownerLabel()}の部屋が空きました")
        assertEquals("合成の名前さんの部屋が空きました", n.displayTitle)
        assertEquals("合成の名前さんの部屋が空きました", n.title(NotificationPreview.FULL))
        assertEquals("合成の名前さんの部屋が空きました", n.title(NotificationPreview.HIDE_ON_LOCK_SCREEN))
        // 待機メッセージを出さない設定では、名前も出さない。
        assertEquals("ピンした部屋が空きました", n.title(NotificationPreview.NO_MESSAGE))
    }

    @Test fun roomsWithoutAVisibleNameKeepTheGenericTitle() {
        assertNull(room(null).ownerLabel())
        assertNull(room("  ").ownerLabel())
    }

    @Test fun testNotificationsLookLikeRealOnes() {
        val pin = NotificationSamples.of(NotificationKind.WAITLIST)
        assertEquals("テストさんの部屋が空きました", pin.displayTitle)
        assertEquals("全国", pin.text)
        assertEquals("テスト", pin.room?.name)
        // 押しても本家の部屋を開かない。
        assertFalse(pin.target is NotificationTarget.Room)
        assertTrue(NotificationSamples.isSample(pin))
        assertEquals("全国で1件", NotificationSamples.of(NotificationKind.RADAR_MATCH).text)
    }
}
