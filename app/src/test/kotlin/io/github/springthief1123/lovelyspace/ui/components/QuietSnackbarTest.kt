package io.github.springthief1123.lovelyspace.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class QuietSnackbarTest {
    @get:Rule val compose = createComposeRule()
    private val host = SnackbarHostState()
    private var result: SnackbarResult? = null

    private fun show() {
        compose.setContent {
            MaterialTheme { QuietSnackbarHost(host) }
            LaunchedEffect(Unit) {
                result = host.showSnackbar("合成の操作結果", actionLabel = "元に戻す", withDismissAction = true)
            }
        }
        compose.waitForIdle()
    }

    @Test fun swipingLeftDismissesWithoutUndo() {
        show()
        compose.onNodeWithText("合成の操作結果").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle { assertNull(host.currentSnackbarData); assertEquals(SnackbarResult.Dismissed, result) }
    }

    @Test fun swipingRightDismissesWithoutUndo() {
        show()
        compose.onNodeWithText("合成の操作結果").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.runOnIdle { assertNull(host.currentSnackbarData); assertEquals(SnackbarResult.Dismissed, result) }
    }

    @Test fun undoStillPerformsTheAction() {
        show()
        compose.onNodeWithText("元に戻す").performClick()
        compose.runOnIdle { assertEquals(SnackbarResult.ActionPerformed, result) }
    }

    @Test fun actionSnackbarExpiresWithoutUndo() {
        show()
        compose.mainClock.advanceTimeBy(10_500L)
        compose.waitForIdle()
        compose.runOnIdle { assertNull(host.currentSnackbarData); assertEquals(SnackbarResult.Dismissed, result) }
    }
}
