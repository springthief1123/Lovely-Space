package io.github.springthief1123.lovelyspace.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.graphics.ColorUtils
import io.github.springthief1123.lovelyspace.settings.ThemeMode
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpaceTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class QuietControlsTest {
    @get:Rule val compose = createComposeRule()

    /** 文字を押してもチェックが切り替わり、読み上げでは文字と状態が 1 つの項目になる。 */
    @Test fun checkboxRowTogglesFromItsLabel() {
        compose.setContent {
            LovelySpaceTheme {
                var checked by remember { mutableStateOf(false) }
                QuietCheckboxRow(checked, { checked = it }, "合成のチェック")
            }
        }
        compose.onNodeWithText("合成のチェック").assertIsOff().performClick()
        compose.onNodeWithText("合成のチェック").assertIsOn()
    }

    @Test fun disabledCheckboxRowDoesNotToggle() {
        compose.setContent {
            LovelySpaceTheme {
                var checked by remember { mutableStateOf(false) }
                QuietCheckboxRow(checked, { checked = it }, "合成の無効なチェック", enabled = false)
            }
        }
        compose.onNodeWithText("合成の無効なチェック").performClick()
        compose.onNodeWithText("合成の無効なチェック").assertIsOff()
    }

    /** 説明つきのスイッチは行全体で切り替わり、読み上げではスイッチの役割を持つ 1 つの項目になる。 */
    @Test fun switchRowTogglesFromItsTitle() {
        compose.setContent {
            LovelySpaceTheme {
                var checked by remember { mutableStateOf(false) }
                QuietSwitchRow(checked, { checked = it }, "合成のスイッチ", description = "合成の説明")
            }
        }
        compose.onNodeWithText("合成のスイッチ")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOff()
            .performClick()
        compose.onNodeWithText("合成のスイッチ").assertIsOn()
    }

    /** 画面内の切り替えは、見た目がチップでも読み上げではタブとして選択状態を伝える。 */
    @Test fun tabsAreReadAsTabs() {
        compose.setContent {
            LovelySpaceTheme {
                var selected by remember { mutableStateOf(1) }
                QuietTabs(listOf(1 to "合成のタブ1", 2 to "合成のタブ2"), selected) { selected = it }
            }
        }
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onNodeWithText("合成のタブ1").assert(tab).assertIsSelected()
        compose.onNodeWithText("合成のタブ2").assert(tab).assertIsNotSelected().performClick()
        compose.onNodeWithText("合成のタブ2").assertIsSelected()
        compose.onNodeWithText("合成のタブ1").assertIsNotSelected()
    }

    /** 絞り込みのチップは選択状態を読み上げに出す。 */
    @Test fun filterChipExposesSelection() {
        compose.setContent {
            LovelySpaceTheme(themeMode = ThemeMode.DARK) {
                var selected by remember { mutableStateOf(false) }
                QuietFilterChip(selected, { selected = !selected }, label = "合成のチップ")
            }
        }
        compose.onNodeWithText("合成のチップ").assertIsNotSelected().performClick()
        compose.onNodeWithText("合成のチップ").assertIsSelected()
    }

    /**
     * 選択中のチップ・セグメントは淡い面（secondaryContainer）だけでは背景と見分けにくいので primary で縁取る。
     * その縁取りと、入力欄と並ぶセグメントの境界（outline）が、ライト・ダークとも背景と 3:1 以上あること。
     */
    @Test fun selectionBordersStandOutInBothThemes() {
        val theme = mutableStateOf(ThemeMode.LIGHT)
        var contrasts = emptyList<Double>()
        compose.setContent {
            LovelySpaceTheme(themeMode = theme.value) {
                val c = MaterialTheme.colorScheme
                SideEffect {
                    contrasts = listOf(
                        ColorUtils.calculateContrast(c.primary.toArgb(), c.surface.toArgb()),
                        ColorUtils.calculateContrast(c.primary.toArgb(), c.secondaryContainer.toArgb()),
                        ColorUtils.calculateContrast(c.outline.toArgb(), c.surface.toArgb()),
                    )
                }
            }
        }
        compose.runOnIdle { assertTrue(contrasts.toString(), contrasts.all { it >= 3.0 }); theme.value = ThemeMode.DARK }
        compose.runOnIdle { assertTrue(contrasts.toString(), contrasts.all { it >= 3.0 }) }
    }
}
