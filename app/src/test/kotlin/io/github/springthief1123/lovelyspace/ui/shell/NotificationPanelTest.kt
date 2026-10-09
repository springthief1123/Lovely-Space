package io.github.springthief1123.lovelyspace.ui.shell

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.springthief1123.lovelyspace.notify.AppNotification
import io.github.springthief1123.lovelyspace.notify.NotificationKind
import io.github.springthief1123.lovelyspace.notify.NotificationTarget
import io.github.springthief1123.lovelyspace.ui.components.QuietDropdownMenu
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

    /** ベルのメニュー（QuietDropdownMenu = DropdownMenu）は中身を固有サイズで測る。お知らせがあっても開けること。 */
    @Test fun opensInsideTheBellMenuWithEntries() {
        val entries = (1..30).map { i ->
            AppNotification("n$i", NotificationKind.entries.first(), "合成の通知 $i", "合成の本文",
                NotificationTarget.Radar, at = 1_000L * i, read = i > 2)
        }
        var opened: String? = null
        compose.setContent {
            LovelySpaceTheme {
                QuietDropdownMenu(expanded = true, onDismissRequest = {}) {
                    NotificationPanel(entries, onOpen = { opened = it.id }, onMarkAllRead = {}, onOpenSettings = {})
                }
            }
        }
        compose.onNodeWithText("すべて既読").assertIsDisplayed()
        compose.onNodeWithTag("notification-n1").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("n1", opened) }
    }
}
