package io.github.springthief1123.lovelyspace.ui.main

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.springthief1123.lovelyspace.data.PinAlert
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PinControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savingOneSelectionKeepsTheOtherChipEnabled() {
        var taps = 0
        compose.setContent {
            MaterialTheme {
                Column {
                    AutoEntryChip(PinAlert.AUTO, enabled = false, onToggle = {}, modifier = Modifier.testTag("saving"), saving = true)
                    AutoEntryChip(PinAlert.NOTIFY, enabled = true, onToggle = { taps++ }, modifier = Modifier.testTag("other"))
                }
            }
        }
        compose.onNodeWithTag("saving").assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithTag("other").assertIsNotSelected().assertIsEnabled().performClick()
        assertEquals(1, taps)
    }
}
