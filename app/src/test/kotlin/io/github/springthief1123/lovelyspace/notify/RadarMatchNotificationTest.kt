package io.github.springthief1123.lovelyspace.notify

import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.core.RoomStatus
import io.github.springthief1123.lovelyspace.data.RadarEvent
import io.github.springthief1123.lovelyspace.data.RadarEventKind
import io.github.springthief1123.lovelyspace.data.RadarEventOrigin
import io.github.springthief1123.lovelyspace.data.RadarOriginType
import io.github.springthief1123.lovelyspace.settings.NotificationPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarMatchNotificationTest {
    // 合成データ。本家の実データは使わない。
    private val room = Room(42, "zenkoku", RoomStatus.WAITING, RoomAction.ENTER, null, "合成の名前", Gender.FEMALE, 25, null, "合成の待機メッセージ")
    private val origin = RadarEventOrigin(RadarOriginType.PLAN, "plan", "合成の条件")
    private fun event(vararg rooms: Room, kind: RadarEventKind = RadarEventKind.SEARCH_MATCH) =
        RadarEvent(1_000L, "合成の条件：1ページで新しい一致を${rooms.size}件確認", id = "e-1", rooms = rooms.toList(), page = 1, kind = kind, origin = origin)

    @Test fun aSingleEnterableMatchOpensItsEntryScreen() {
        val n = event(room).toMatchNotification()!!
        assertEquals(NotificationKind.RADAR_MATCH, n.kind)
        assertEquals(NotificationTarget.Room(Genres["zenkoku"]!!.host, "zenkoku", 42), n.target)
        assertEquals("合成の条件：新しい一致", n.title)
        assertEquals(1_000L, n.at)
    }

    @Test fun otherUsersNamesAndMessagesFollowThePreviewSetting() {
        val n = event(room).toMatchNotification()!!
        assertFalse("合成の名前" in n.title || "合成の名前" in n.text)
        assertTrue("合成の待機メッセージ" in n.body(NotificationPreview.HIDE_ON_LOCK_SCREEN))
        assertFalse("合成の名前" in n.body(NotificationPreview.NO_MESSAGE))
        assertFalse("合成の待機メッセージ" in n.body(NotificationPreview.NO_MESSAGE))
    }

    @Test fun severalMatchesOrAPeekOnlyRoomOpenTheRadar() {
        assertEquals(NotificationTarget.Radar, event(room, room.copy(id = 43)).toMatchNotification()!!.target)
        assertEquals(NotificationTarget.Radar, event(room.copy(action = RoomAction.PEEK)).toMatchNotification()!!.target)
    }

    @Test fun onlyNewMatchesAreNotified() {
        assertNull(event(room, kind = RadarEventKind.ROOM_STATUS).toMatchNotification())
        assertNull(event().toMatchNotification())
    }

    @Test fun namedMatchesUseTheirOwnKind() {
        val n = event(room, kind = RadarEventKind.CANDIDATE_MATCH).toMatchNotification()!!
        assertEquals(NotificationKind.NAME_MATCH, n.kind)
        assertTrue("合成の条件" in n.title)
    }

    @Test fun eventsOfOneConditionAreMergedIntoOneNotification() {
        val first = event(room)
        val second = first.copy(id = "e-2", at = 2_000L, rooms = listOf(room.copy(id = 43), room))
        val n = listOf(first, second).toMatchNotification("merged")!!
        assertEquals("merged", n.id)
        assertEquals("全国で2件", n.text)
        assertEquals(NotificationTarget.Radar, n.target)
        assertEquals(2_000L, n.at)
    }

    @Test fun aReusedIdShowsTheNewerRoom() {
        val first = event(room)
        val reused = room.copy(name = "合成の別の名前", age = 30, message = "合成の別の待機メッセージ")
        val n = listOf(first, first.copy(id = "e-2", at = 2_000L, rooms = listOf(reused))).toMatchNotification("merged")!!
        assertEquals("全国で1件", n.text)
        assertEquals("合成の別の名前\n合成の別の待機メッセージ", n.message)
    }
}
