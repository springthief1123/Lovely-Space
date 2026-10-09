package io.github.springthief1123.lovelyspace.ui.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpaceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsChoiceRowTest {
    @get:Rule val compose = createComposeRule()

    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    /** 設定の選択肢はラジオボタンとして読まれ、まとまりの中で選択状態が移る。 */
    @Test fun choicesAreRadioButtonsInAGroup() {
        compose.setContent {
            LovelySpaceTheme {
                var selected by remember { mutableStateOf("A") }
                SettingsChoiceGroup {
                    SettingsChoiceRow("合成の選択肢A", selected == "A", { selected = "A" })
                    SettingsChoiceRow("合成の選択肢B", selected == "B", { selected = "B" }, description = "合成の説明")
                }
            }
        }
        compose.onNodeWithText("合成の選択肢A").assert(radio).assertIsSelected()
            .onParent().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
        compose.onNodeWithText("合成の選択肢B").assert(radio).assertIsNotSelected().performClick()
        compose.onNodeWithText("合成の選択肢B").assertIsSelected()
        compose.onNodeWithText("合成の選択肢A").assertIsNotSelected()
    }

    /** 選択肢ではない行（端末の通知設定を開くなど）はボタンとして読まれ、選択状態を持たない。 */
    @Test fun actionRowIsAButton() {
        var clicks = 0
        compose.setContent {
            LovelySpaceTheme {
                SettingsChoiceRow("合成の操作", selected = true, onClick = { clicks++ }, radio = false)
            }
        }
        compose.onNodeWithText("合成の操作")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
            .performClick()
        compose.runOnIdle { check(clicks == 1) }
    }
}
