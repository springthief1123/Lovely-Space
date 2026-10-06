package io.github.springthief1123.lovelyspace.ui.components

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import io.github.springthief1123.lovelyspace.settings.ThemeMode
import io.github.springthief1123.lovelyspace.ui.shell.LovelyBottomNavigation
import io.github.springthief1123.lovelyspace.ui.shell.MainDestinations
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpaceTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1200dp-h900dp")
@LooperMode(LooperMode.Mode.PAUSED)
class QuietLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun resizeCapsReadingWidthAndKeepsInputDraft() {
        val width = mutableStateOf(320.dp)
        compose.setContent {
            LovelySpaceTheme {
                Box(Modifier.requiredSize(width.value, 600.dp)) {
                    QuietPage {
                        var draft by rememberSaveable { mutableStateOf("") }
                        Box(Modifier.fillMaxSize().testTag("reading-page")) {
                            OutlinedTextField(draft, { draft = it }, modifier = Modifier.testTag("draft"))
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("reading-page").assertWidthIsEqualTo(320.dp)
        compose.onNodeWithTag("draft").performTextInput("合成の下書き")
        compose.runOnIdle { width.value = 900.dp }
        compose.onNodeWithTag("reading-page").assertWidthIsEqualTo(720.dp)
        compose.onNodeWithTag("draft").assertTextContains("合成の下書き")
        compose.runOnIdle { width.value = 320.dp }
        compose.onNodeWithTag("reading-page").assertWidthIsEqualTo(320.dp)
        compose.onNodeWithTag("draft").assertTextContains("合成の下書き")
    }

    @Test fun ageFieldsStackAtLargeTextAndReturnToOneRow() {
        val scale = mutableFloatStateOf(2f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.floatValue)) {
                LovelySpaceTheme {
                    Box(Modifier.width(400.dp)) {
                        QuietFieldPair(
                            first = { OutlinedTextField("18", {}, label = { androidx.compose.material3.Text("最低年齢") }, modifier = it.testTag("min")) },
                            second = { OutlinedTextField("99", {}, label = { androidx.compose.material3.Text("最高年齢") }, modifier = it.testTag("max")) },
                        )
                    }
                }
            }
        }
        val stackedMin = compose.onNodeWithTag("min").fetchSemanticsNode().boundsInRoot
        val stackedMax = compose.onNodeWithTag("max").fetchSemanticsNode().boundsInRoot
        assertTrue(stackedMax.top >= stackedMin.bottom)
        compose.runOnIdle { scale.floatValue = 1f }
        val sideMin = compose.onNodeWithTag("min").fetchSemanticsNode().boundsInRoot
        val sideMax = compose.onNodeWithTag("max").fetchSemanticsNode().boundsInRoot
        assertEquals(sideMin.top, sideMax.top, 1f)
        assertTrue(sideMax.left >= sideMin.right)
    }

    @Test fun narrowLargeTextNavigationKeepsEveryDestinationClickable() {
        var selected = ""
        var measured = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                LovelySpaceTheme(themeMode = ThemeMode.DARK) {
                    Box(Modifier.size(320.dp, 500.dp)) {
                        LovelyBottomNavigation(MainDestinations.first().route, { selected = it.route },
                            Modifier.align(Alignment.BottomCenter), onHeight = { measured = it })
                    }
                }
            }
        }
        MainDestinations.forEach { destination ->
            compose.onNodeWithText(destination.label).assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(destination.route, selected) }
        }
        compose.runOnIdle { assertTrue(measured > 0) }
    }

    @Test fun supplementaryTextMaintainsContrastInBothThemes() {
        val theme = mutableStateOf(ThemeMode.LIGHT)
        var contrasts = emptyList<Double>()
        compose.setContent {
            LovelySpaceTheme(themeMode = theme.value) {
                val colors = MaterialTheme.colorScheme
                SideEffect {
                    contrasts = listOf(colors.surface, colors.background, colors.surfaceVariant).map {
                        ColorUtils.calculateContrast(colors.onSurfaceVariant.toArgb(), it.toArgb())
                    }
                }
            }
        }
        compose.runOnIdle { assertTrue(contrasts.all { it >= 4.5 }); theme.value = ThemeMode.DARK }
        compose.runOnIdle { assertTrue(contrasts.all { it >= 4.5 }) }
    }
}
