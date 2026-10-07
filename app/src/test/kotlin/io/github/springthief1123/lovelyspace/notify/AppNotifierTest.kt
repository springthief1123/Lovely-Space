package io.github.springthief1123.lovelyspace.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.springthief1123.lovelyspace.settings.NotificationPreview
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppNotifierTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val inbox = NotificationInbox()
    private var preview = NotificationPreview.HIDE_ON_LOCK_SCREEN
    private val notifier = AppNotifier(app, inbox) { preview }

    // 合成データ。本家の実データは使わない。
    private val match = AppNotification("match-1", NotificationKind.RADAR_MATCH, "条件に合う部屋があります", "全国で 1 件",
        NotificationTarget.Room("2shot.chat.shalove.net", "zenkoku", 900000001L), 1_000L, message = "合成の待機メッセージ")

    @Before fun setUp() = notifier.ensureChannels()

    private fun grant() = shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    @Test fun eachKindHasItsOwnChannel() {
        val ids = manager.notificationChannels.map { it.id }.toSet()
        assertEquals(NotificationKind.entries.map { it.channelId }.toSet(), ids)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(NotificationKind.ROOM_ENTRY.channelId).importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(NotificationKind.ONGOING.channelId).importance)
    }

    @Test fun postsToTheKindsChannelAndHidesContentOnTheLockScreen() = runTest {
        grant()
        notifier.post(match)

        val n = posted().single()
        assertEquals(NotificationKind.RADAR_MATCH.channelId, n.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals(AppNotifier.LOCKED_TEXT, n.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("合成の待機メッセージ"))
        assertEquals("match-1", inbox.entries.value.single().id)

        // タップすると MainActivity にお知らせの ID が届く。
        val tap = shadowOf(n.contentIntent).savedIntent
        assertEquals("match-1", AppNotifier.notificationId(tap))
    }

    @Test fun messageIsLeftOutOfTheNotificationWhenAsked() = runTest {
        grant()
        preview = NotificationPreview.NO_MESSAGE
        notifier.post(match)
        val text = posted().single().extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertEquals("全国で 1 件", text)
    }

    @Test fun fullPreviewShowsOnTheLockScreen() = runTest {
        grant()
        preview = NotificationPreview.FULL
        notifier.post(match)
        val n = posted().single()
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertNull(n.publicVersion)
    }

    @Test fun withoutPermissionItIsOnlyKeptInTheInbox() = runTest {
        notifier.post(match)
        assertTrue(posted().isEmpty())
        assertEquals("match-1", inbox.entries.value.single().id)
    }

    @Test fun openingFromTheAppClearsTheDeviceNotification() = runTest {
        grant()
        notifier.post(match)
        notifier.open("match-1")
        assertTrue(posted().isEmpty())
        assertNotNull(inbox.opened.value)
    }

    @Test fun relaunchFromRecentsDoesNotReopenTheNotification() {
        val intent = AppNotifier.openIntent(app, match)
        assertEquals("match-1", AppNotifier.notificationId(intent))
        intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(AppNotifier.notificationId(intent))
        assertNull(AppNotifier.notificationId(Intent(Intent.ACTION_MAIN)))
    }

    @Test fun tappingAnUnloggedNotificationStillOpensItsTarget() = runTest {
        grant()
        val status = NotificationSamples.of(NotificationKind.ONGOING)
        notifier.post(status)
        assertTrue(inbox.entries.value.isEmpty())

        notifier.open(shadowOf(posted().single().contentIntent).savedIntent)

        assertEquals(NotificationTarget.Radar, inbox.opened.value?.target)
    }

    @Test fun everyKindHasATestNotification() = runTest {
        grant()
        NotificationKind.entries.forEach { notifier.post(NotificationSamples.of(it)) }
        assertEquals(NotificationKind.entries.map { it.channelId }.toSet(), posted().map { it.channelId }.toSet())
        // 常駐通知はお知らせの履歴に残さない。
        assertEquals(NotificationKind.entries.size - 1, inbox.entries.value.size)
    }
}
