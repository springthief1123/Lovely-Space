package io.github.springthief1123.lovelyspace.ui.shell

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.core.RoomStatus
import io.github.springthief1123.lovelyspace.notify.AppNotification
import io.github.springthief1123.lovelyspace.notify.NotificationKind
import io.github.springthief1123.lovelyspace.notify.NotificationTarget
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NotificationPanelTest {
    @get:Rule val compose = createComposeRule()

    private val room = Room(101, "zenkoku", RoomStatus.WAITING, RoomAction.ENTER, null, "合成の部屋主", Gender.FEMALE, 25, null, "合成の待機メッセージ")

    @Test fun showsWhoseRoomAndOpensOrEntersIt() {
        val entries = listOf(
            AppNotification("pin", NotificationKind.WAITLIST, "ピンした部屋が空きました", "全国",
                NotificationTarget.Room("example.invalid", "zenkoku", 101, verified = true), at = 2_000L, room = room),
        ) + (1..30).map { i ->
            AppNotification("n$i", NotificationKind.entries.first(), "合成の通知 $i", "合成の本文",
                NotificationTarget.Radar, at = 1_000L * i, read = i > 2)
        }
        var opened: String? = null
        var entered: String? = null
        compose.setContent {
            LovelySpaceTheme {
                NotificationPanel(entries, onOpen = { opened = it.id }, onEnter = { entered = it.id }, onRemove = {},
                    onMarkAllRead = {}, onOpenSettings = {})
            }
        }
        compose.onNodeWithText("すべて既読").assertIsDisplayed()
        // 誰のどんな部屋かを見出しと待機メッセージで出し、ピンしたときと同じ旨の文は出さない。
        compose.onNodeWithText("合成の部屋主").assertIsDisplayed()
        compose.onNodeWithText("合成の待機メッセージ").assertIsDisplayed()
        compose.onNodeWithText("入室する").performClick()
        compose.runOnIdle { assertEquals("pin", entered) }
        compose.onNodeWithTag("notification-pin").performClick()
        compose.runOnIdle { assertEquals("pin", opened) }
    }

    @Test fun swipingACardRemovesIt() {
        var entries by mutableStateOf(listOf(
            AppNotification("a", NotificationKind.WAITLIST, "合成の通知", "合成の本文", NotificationTarget.Radar, at = 1_000L),
        ))
        var removed: String? = null
        compose.setContent {
            LovelySpaceTheme {
                NotificationPanel(entries, onOpen = {}, onEnter = {}, onRemove = { n -> removed = n.id; entries = entries - n },
                    onMarkAllRead = {}, onOpenSettings = {})
            }
        }
        compose.onNodeWithTag("notification-a").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals("a", removed) }
        compose.onNodeWithText("お知らせはまだありません").assertIsDisplayed()
    }
}
