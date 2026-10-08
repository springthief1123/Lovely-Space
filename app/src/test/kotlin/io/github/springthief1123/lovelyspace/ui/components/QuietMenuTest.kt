package io.github.springthief1123.lovelyspace.ui.components

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.springthief1123.lovelyspace.settings.ThemeMode
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
class QuietMenuTest {
    @get:Rule val compose = createComposeRule()

    /** ︙メニューは選んだら閉じ、無効な項目は押せない。ダークでも同じ部品で開ける。 */
    @Test fun overflowMenuClosesAfterSelectingAndKeepsDisabledItemsInert() {
        val chosen = mutableListOf<String>()
        compose.setContent {
            LovelySpaceTheme(themeMode = ThemeMode.DARK) {
                QuietOverflowMenu(listOf(
                    QuietMenuItem("合成の編集") { chosen += "edit" },
                    QuietMenuItem("合成の無効", enabled = false) { chosen += "disabled" },
                    QuietMenuItem("合成の削除", destructive = true) { chosen += "delete" },
                ))
            }
        }
        compose.onNodeWithContentDescription("その他の操作").performClick()
        compose.onNodeWithText("合成の無効").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("合成の削除").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("delete"), chosen) }
        compose.onNodeWithText("合成の削除").assertDoesNotExist()
    }

    /** 選択メニューの現在の値は、読み上げでも選択中と分かる。 */
    @Test fun selectedRowIsExposedAsSelected() {
        compose.setContent {
            LovelySpaceTheme {
                QuietDropdownMenu(expanded = true, onDismissRequest = {}) {
                    QuietMenuRow("合成の選択中", supporting = "合成の補足", selected = true, onClick = {})
                    QuietMenuRow("合成のほか", onClick = {})
                }
            }
        }
        compose.onNodeWithText("合成の選択中", substring = true).assertIsSelected()
        compose.onNodeWithText("合成の補足", substring = true).assertIsDisplayed()
        // 選択の意味を持たない行には「選択されていません」を読ませない。
        compose.onNodeWithText("合成のほか").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
    }
}
