package io.github.springthief1123.lovelyspace.ui.components

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
class QuietDialogsTest {
    @get:Rule val compose = createComposeRule()
    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    /** 見出しは読み上げで見出しになり、取り消しで閉じる。確定は呼ばれない。 */
    @Test fun confirmDialogDismissesWithoutConfirming() {
        var confirmed = 0
        compose.setContent {
            LovelySpaceTheme(themeMode = ThemeMode.DARK) {
                var open by remember { mutableStateOf(true) }
                if (open) QuietConfirmDialog("合成の確認", "合成の本文", "合成の削除",
                    onConfirm = { confirmed++ }, onDismiss = { open = false }, destructive = true)
            }
        }
        compose.onNodeWithText("合成の確認").assert(heading)
        compose.onNodeWithText("合成の本文").assertIsDisplayed()
        compose.onNodeWithText("やめる").performClick()
        compose.onNodeWithText("合成の確認").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, confirmed) }
    }

    /** 保存中は確定・取り消しの両方を押せず、失敗は本文の下に出る。 */
    @Test fun workingDialogDisablesButtonsAndShowsError() {
        compose.setContent {
            LovelySpaceTheme {
                QuietConfirmDialog("合成の確認", "合成の本文", "合成の確定", onConfirm = {}, onDismiss = {},
                    enabled = false, error = "合成の失敗")
            }
        }
        compose.onNodeWithText("合成の確定").assertIsNotEnabled()
        compose.onNodeWithText("やめる").assertIsNotEnabled()
        compose.onNodeWithText("合成の失敗").assertIsDisplayed()
    }

    @Test fun formDialogEnablesConfirmFromItsState() {
        var saved = 0
        compose.setContent {
            LovelySpaceTheme {
                QuietDialog("合成の入力", onDismissRequest = {}, confirmLabel = "合成の保存", onConfirm = { saved++ },
                    confirmEnabled = true, dismissLabel = null) { androidx.compose.material3.Text("合成の中身") }
            }
        }
        compose.onNodeWithText("合成の中身").assertIsDisplayed()
        compose.onNodeWithText("キャンセル").assertDoesNotExist()
        compose.onNodeWithText("合成の保存").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, saved) }
    }

    /** シェルの外の見出しは、題名が見出しとして読まれ、戻るの説明を変えられる。 */
    @Test fun topBarExposesHeadingAndBack() {
        var back = 0
        compose.setContent {
            LovelySpaceTheme {
                QuietTopBar("合成の題名", subtitle = "合成の補足", backDescription = "合成の戻る") { back++ }
            }
        }
        compose.onNodeWithText("合成の題名", substring = true).assert(heading)
        compose.onNodeWithContentDescription("合成の戻る").performClick()
        compose.runOnIdle { assertEquals(1, back) }
    }
}
