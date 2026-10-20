package io.github.springthief1123.lovelyspace.ui.main

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.springthief1123.lovelyspace.ui.components.QuietTabs
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
class RadarSectionTest {
    @get:Rule val compose = createComposeRule()

    /** レーダーのピン・条件の切り替えは最初はピンで、選んだものは画面の作り直しのあとも残る。 */
    @Test fun selectedSectionSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            LovelySpaceTheme {
                var section by rememberRadarSection()
                QuietTabs(listOf(RadarSection.PINS to "ピン", RadarSection.CONDITIONS to "条件"), section) { section = it }
            }
        }
        compose.onNodeWithText("ピン").assertIsSelected()
        compose.onNodeWithText("条件").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("条件").assertIsSelected()
        compose.onNodeWithText("ピン").assertIsNotSelected()
    }
}
