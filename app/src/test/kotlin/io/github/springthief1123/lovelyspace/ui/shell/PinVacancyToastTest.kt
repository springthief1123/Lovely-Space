package io.github.springthief1123.lovelyspace.ui.shell

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.PinState
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.core.RoomStatus
import io.github.springthief1123.lovelyspace.data.PinRecord
import io.github.springthief1123.lovelyspace.data.PinView
import io.github.springthief1123.lovelyspace.data.RoomPreference
import io.github.springthief1123.lovelyspace.notify.AppNotification
import io.github.springthief1123.lovelyspace.notify.NotificationKind
import io.github.springthief1123.lovelyspace.notify.NotificationTarget
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class PinVacancyToastTest {
    @get:Rule val compose = createComposeRule()

    // 合成の部屋だけを使用し、本家のデータは保持しない。
    private val host = Genres["zenkoku"]!!.host
    private val room = Room(101, "zenkoku", RoomStatus.WAITING, RoomAction.ENTER, null,
        "合成の部屋主", Gender.FEMALE, 25, null, "合成の待機メッセージ")
    private val preference = RoomPreference(
        host = host, roomId = room.id, genreKey = room.genreKey, favorite = true, hidden = false,
        snapshotStatus = room.status.name, snapshotAction = room.action.name, snapshotElapsedSeconds = null,
        snapshotName = room.name, snapshotGender = room.gender.name, snapshotAge = room.age,
        snapshotArea = null, snapshotMessage = room.message, identityName = room.name,
        identityGender = room.gender.name, identityAge = room.age,
    )
    private val notice = AppNotification(
        "pin-${host}/101-2000", NotificationKind.WAITLIST, "ピンした部屋が空きました", "全国",
        NotificationTarget.Room(host, room.genreKey, room.id, verified = true), at = 2_000L, room = room,
    )
    private fun view(
        state: PinState = PinState.OPEN, listed: Boolean = true, live: Room = room,
    ) = PinView(preference, PinRecord("${host}/101"), state, live, null, null, listed)

    @Test fun onlyPinVacancyNoticesShowAsToasts() {
        assertTrue(isPinVacancyNotice(notice))
        assertFalse(isPinVacancyNotice(notice.copy(id = "pin-similar-101", target = NotificationTarget.Radar)))
        assertFalse(isPinVacancyNotice(notice.copy(kind = NotificationKind.RADAR_MATCH)))
        assertFalse(isPinVacancyNotice(notice.copy(id = "waitlist-101")))
    }

    @Test fun enteringRequiresFreshVerifiedAndListedRoom() {
        assertEquals(PinVacancyAction.ENTER, pinVacancyAction(notice, listOf(view())))
        assertEquals(PinVacancyAction.UNAVAILABLE, pinVacancyAction(notice, emptyList()))
        assertEquals(PinVacancyAction.UNAVAILABLE, pinVacancyAction(notice, listOf(view(state = PinState.FULL))))
        assertEquals(PinVacancyAction.UNAVAILABLE, pinVacancyAction(notice, listOf(view(listed = false))))
        assertEquals(PinVacancyAction.UNAVAILABLE, pinVacancyAction(notice, listOf(view(live = room.copy(action = RoomAction.NONE)))))
        assertEquals(PinVacancyAction.UNAVAILABLE, pinVacancyAction(notice, listOf(view(live = room.copy(message = "別人の待機メッセージ")))))
        assertEquals(PinVacancyAction.UNAVAILABLE, pinVacancyAction(notice, listOf(view(live = room.copy(id = 102)))))
    }

    @Test fun unverifiedRoomMustBeReviewedInsteadOfAutoEntered() {
        assertEquals(PinVacancyAction.REVIEW,
            pinVacancyAction(notice.copy(target = NotificationTarget.Room(host, room.genreKey, room.id, verified = false)),
                listOf(view())))
    }

    @Test fun toastEntersWithOneTapAndCannotEnterWhenFull() {
        var entered = 0
        var reviewed = 0
        compose.setContent {
            LovelySpaceTheme {
                PinVacancyToast(notice, listOf(view()), onEnter = { entered++ },
                    onReview = { reviewed++ }, onDismiss = {})
            }
        }
        compose.onNodeWithTag("pin-vacancy-toast").assertIsDisplayed()
        compose.onNodeWithText("入室する").performClick()
        compose.runOnIdle { assertEquals(1, entered); assertEquals(0, reviewed) }
    }

    @Test fun staleToastHasNoEnterButton() {
        compose.setContent {
            LovelySpaceTheme {
                PinVacancyToast(notice, listOf(view(state = PinState.FULL)), onEnter = {},
                    onReview = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("現在の空きは確認できません").assertIsDisplayed()
        compose.onNodeWithText("入室する").assertDoesNotExist()
    }
}
