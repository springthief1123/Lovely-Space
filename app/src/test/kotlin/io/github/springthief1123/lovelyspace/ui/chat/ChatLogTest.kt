package io.github.springthief1123.lovelyspace.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.core.chat.ChatLine
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ChatLogTest {
    @get:Rule val compose = createComposeRule()
    private fun line(id: Long) = UiLine(id, ChatLine("合成の相手", false, "合成の本文$id", null), false)

    @Test fun newMessageKeepsOlderReadingPositionUntilLatestButtonIsPressed() {
        val lines = mutableStateOf((1L..50L).reversed().map(::line))
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 500.dp)) { ChatLog(lines.value, Modifier.fillMaxSize()) }
            }
        }
        compose.onNodeWithTag("chat-log").performScrollToIndex(20)
        compose.onNodeWithText("合成の本文30").assertIsDisplayed()
        compose.runOnIdle { lines.value = listOf(line(51)) + lines.value }
        compose.onNodeWithText("合成の本文30").assertIsDisplayed()
        compose.onNodeWithText("新着 1件 · 最新へ ↓").performClick()
        compose.onNodeWithText("合成の本文51").assertIsDisplayed()
        compose.onNodeWithText("新着 1件 · 最新へ ↓").assertDoesNotExist()
    }

    @Test fun latestFollowsNewMessageWithLargeTextOnNarrowScreen() {
        val lines = mutableStateOf((1L..30L).reversed().map(::line))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.size(320.dp, 500.dp)) { ChatLog(lines.value, Modifier.fillMaxSize()) }
                }
            }
        }
        compose.runOnIdle { lines.value = listOf(line(31)) + lines.value }
        compose.onNodeWithText("合成の本文31").assertIsDisplayed()
        compose.onNodeWithText("最新へ戻る ↓").assertDoesNotExist()
    }
}
