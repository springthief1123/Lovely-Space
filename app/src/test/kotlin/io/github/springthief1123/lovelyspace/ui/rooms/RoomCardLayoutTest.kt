package io.github.springthief1123.lovelyspace.ui.rooms

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertIsDisplayed
import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.core.RoomStatus
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomCardLayoutTest {
    @get:Rule val compose = createComposeRule()

    // 合成データ。本家の実データは使わない。
    private val room = Room(
        id = 1L,
        genreKey = "zenkoku",
        status = RoomStatus.WAITING,
        action = RoomAction.ENTER,
        elapsed = 12.minutes,
        name = "テスト",
        gender = Gender.FEMALE,
        age = 24,
        area = "東京",
        message = "一行目\n二行目\n三行目\n四行目\n五行目",
    )

    @Test fun actionsMoveFromButtonsToLongPressMenu() {
        compose.setContent {
            LovelySpaceTheme {
                RoomCard(room = room, onClick = {}, onFavoriteClick = {}, onHideClick = {}, onDetailsClick = {})
            }
        }
        compose.onAllNodesWithContentDescription("部屋の操作").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("部屋を保存").assertCountEquals(0)
        compose.onNodeWithText("テスト").performTouchInput { longClick() }
        compose.onNodeWithText("部屋を保存").assertIsDisplayed()
        compose.onNodeWithText("非表示にする").assertIsDisplayed()
    }

    @Test fun cardShowsGenderAndElapsedLabel() {
        compose.setContent { LovelySpaceTheme { RoomCard(room = room, onClick = {}) } }
        compose.onNodeWithText("女性").assertIsDisplayed()
        compose.onNodeWithText("24歳・東京").assertIsDisplayed()
        compose.onNodeWithText("12分前").assertIsDisplayed()
        compose.onNodeWithText("待機中").assertIsDisplayed()
        compose.onNodeWithText("非公開").assertIsDisplayed()
    }

    @Test fun messageLinesFollowDisplaySetting() {
        compose.setContent {
            LovelySpaceTheme {
                CompositionLocalProvider(LocalRoomMessageMaxLines provides 3) {
                    RoomCard(room = room, onClick = {})
                }
            }
        }
        val node = compose.onNodeWithText(room.message, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        assertEquals(3, results.single().lineCount)
    }

    @Test fun elapsedLabelDistinguishesWaitingAndConversation() {
        assertEquals("12分前", cardElapsedLabel(RoomStatus.WAITING, 12.minutes))
        assertEquals("会話 1時間5分", cardElapsedLabel(RoomStatus.FULL, 65.minutes))
        assertEquals("たった今", cardElapsedLabel(RoomStatus.PUBLIC_WAITING, 0.minutes))
    }
}
