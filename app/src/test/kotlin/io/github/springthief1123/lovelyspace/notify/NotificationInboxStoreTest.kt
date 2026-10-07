package io.github.springthief1123.lovelyspace.notify

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationInboxStoreTest {
    private val prefs = ApplicationProvider.getApplicationContext<Application>()
        .getSharedPreferences(NotificationInboxStore.PREFS, Context.MODE_PRIVATE)

    @Test fun roundTripsEveryTarget() {
        // 合成データ。本家の実データは使わない。
        val entries = listOf(
            AppNotification("1", NotificationKind.RADAR_MATCH, "見出し", "本文",
                NotificationTarget.Room("2shot.chat.shalove.net", "zenkoku", 900000001L), 10L, message = "合成の待機メッセージ"),
            AppNotification("2", NotificationKind.ROOM_ENTRY, "見出し", "本文", NotificationTarget.ActiveChat, 20L, read = true),
            AppNotification("3", NotificationKind.WAITLIST, "見出し", "本文", NotificationTarget.Rooms, 30L),
            AppNotification("4", NotificationKind.RADAR_MATCH, "見出し", "本文", NotificationTarget.Radar, 40L),
        )
        NotificationInboxStore(prefs).save(entries)
        assertEquals(entries, NotificationInboxStore(prefs).load())
    }

    @Test fun unreadableRecordIsDiscarded() {
        prefs.edit().putString("inbox", "{壊れた記録").commit()
        assertTrue(NotificationInboxStore(prefs).load().isEmpty())
    }
}
