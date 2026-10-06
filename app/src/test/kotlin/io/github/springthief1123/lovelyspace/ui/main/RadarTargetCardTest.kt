package io.github.springthief1123.lovelyspace.ui.main

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.core.RoomStatus
import io.github.springthief1123.lovelyspace.data.TrackedRoom
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RadarTargetCardTest {
    @get:Rule val compose = createComposeRule()

    private val target = TrackedRoom(
        room = Room(42, "zenkoku", RoomStatus.WAITING, RoomAction.ENTER, null, "合成", Gender.FEMALE, 25, null, "本文"),
    )

    @Test fun detailsRemainAvailableWhenTargetEditingIsDisabled() {
        var opened = false
        compose.setContent {
            MaterialTheme {
                RadarTargetCard(
                    target = target,
                    openEnabled = true,
                    editEnabled = false,
                    onOpen = { opened = true },
                    onPin = {},
                    onNote = {},
                    onRemove = {},
                )
            }
        }

        compose.onNodeWithText("詳細を確認").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(opened) }
        compose.onNodeWithText("ピン留め").assertIsNotEnabled()
        compose.onNodeWithText("メモを追加").assertIsNotEnabled()
        compose.onNodeWithText("追跡を解除").assertIsNotEnabled()
    }
}
