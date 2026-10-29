package io.github.springthief1123.lovelyspace.ui.shell

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import io.github.springthief1123.lovelyspace.notify.NotificationSamples
import io.github.springthief1123.lovelyspace.ui.settings.FloatingPreviewTestRow
import io.github.springthief1123.lovelyspace.Routes
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
        // 待機メッセージが変わったら、入室前画面で確かめる（ワンタップでは入らない）。
        assertEquals(PinVacancyAction.REVIEW, pinVacancyAction(notice, listOf(view(live = room.copy(message = "別人の待機メッセージ")))))
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
        compose.onNodeWithText("埋まりました", substring = true).assertIsDisplayed()
        compose.onNodeWithText("入室する").assertDoesNotExist()
    }
    @Test fun statusFollowsTheLatestPinState() {
        assertEquals(PinVacancyStatus.OPEN, pinVacancyStatus(notice, listOf(view())))
        assertEquals(PinVacancyStatus.FILLED, pinVacancyStatus(notice, listOf(view(state = PinState.FULL))))
        assertEquals(PinVacancyStatus.FILLED, pinVacancyStatus(notice, listOf(view(live = room.copy(action = RoomAction.NONE)))))
        assertEquals(PinVacancyStatus.CLOSED, pinVacancyStatus(notice, listOf(view(state = PinState.CLOSED))))
        assertEquals(PinVacancyStatus.UNCHECKED, pinVacancyStatus(notice, listOf(view(state = PinState.UNSEEN))))
        assertEquals(PinVacancyStatus.UNCHECKED, pinVacancyStatus(notice, listOf(view(listed = false))))
        assertEquals(PinVacancyStatus.GONE, pinVacancyStatus(notice, emptyList()))
        assertEquals(PinVacancyStatus.CHANGED, pinVacancyStatus(notice, listOf(view(live = room.copy(message = "別の待機メッセージ")))))
        assertTrue(PinVacancyStatus.FILLED.stale)
        assertFalse(PinVacancyStatus.UNCHECKED.stale)
    }

    @Test fun sameRoomVacancyReplacesTheOldCard() {
        val old = notice.copy(id = "pin-${host}/101-1000", at = 1_000L)
        val other = notice.copy(id = "pin-${host}/202-1500", at = 1_500L,
            target = NotificationTarget.Room(host, room.genreKey, 202, verified = true))
        val fresh = notice.copy(id = "pin-${host}/101-3000", at = 3_000L)
        val entries = listOf(fresh, other, old)
        val targetOf = { id: String -> entries.firstOrNull { it.id == id }?.target }
        val state = FloatingNoticeState().arrive(listOf(old), targetOf, 1_000L)
            .arrive(listOf(other), targetOf, 1_500L)
            .arrive(listOf(fresh), targetOf, 3_000L)
        assertEquals(listOf(fresh.id, other.id), state.ids)
        assertEquals(3_000L, state.shownAt[fresh.id])
    }

    @Test fun filledCardsLingerBrieflyThenLeave() {
        val state = FloatingNoticeState(listOf("a", "b"), mapOf("a" to 0L, "b" to 0L))
        val filled = state.tick(mapOf("a" to PinVacancyStatus.FILLED, "b" to PinVacancyStatus.OPEN), 1_000L, expanded = false)
        assertEquals(listOf("a", "b"), filled.ids)
        assertEquals(1_000L, filled.staleSince["a"])
        val later = filled.tick(mapOf("a" to PinVacancyStatus.FILLED, "b" to PinVacancyStatus.OPEN), 1_000L + STALE_LINGER_MS, expanded = false)
        assertEquals(listOf("b"), later.ids)
        // 空き直したら「埋まった時刻」は消え、片付けの対象から外れる。
        val reopened = filled.tick(mapOf("a" to PinVacancyStatus.OPEN, "b" to PinVacancyStatus.OPEN), 2_000L, expanded = false)
        assertTrue(reopened.staleSince.isEmpty())
        // 広げて操作している間は並びを変えない。
        assertEquals(listOf("a", "b"), filled.tick(mapOf("a" to PinVacancyStatus.FILLED, "b" to PinVacancyStatus.OPEN),
            1_000L + STALE_LINGER_MS, expanded = true).ids)
        // 空いたままでも 5 分で片付ける。既読などで状態が無くなったものも外す。
        assertTrue(state.tick(mapOf("a" to PinVacancyStatus.OPEN), VACANT_TTL_MS, expanded = false).ids.isEmpty())
        assertEquals(listOf("a"), state.tick(mapOf("a" to PinVacancyStatus.OPEN), 1_000L, expanded = false).ids)
    }

    @Test fun cardSwitchesToFilledInPlace() {
        var pins by mutableStateOf(listOf(view()))
        compose.setContent {
            LovelySpaceTheme {
                PinVacancyToastStack(listOf(notice), pins, expanded = false, onExpandedChange = {},
                    onEnter = {}, onReview = {}, onDismiss = {}, now = 4_000L)
            }
        }
        compose.onNodeWithText("入室できます", substring = true).assertIsDisplayed()
        compose.onNodeWithText("入室する").assertIsDisplayed()
        pins = listOf(view(state = PinState.FULL))
        compose.onNodeWithText("埋まりました", substring = true).assertIsDisplayed()
        compose.onNodeWithText("入室する").assertDoesNotExist()
    }

    @Test fun evenPreviewSamplesFillAfterAWhile() {
        val odd = NotificationSamples.floatingPreview(1)
        val even = NotificationSamples.floatingPreview(2)
        assertEquals(PinVacancyStatus.OPEN, previewVacancyStatus(odd, 0L, PREVIEW_FILL_MS * 10))
        assertEquals(PinVacancyStatus.OPEN, previewVacancyStatus(even, 0L, PREVIEW_FILL_MS - 1))
        assertEquals(PinVacancyStatus.FILLED, previewVacancyStatus(even, 0L, PREVIEW_FILL_MS))
    }

    @Test fun onlyTheLatestFiveUniqueNoticesAreFloated() {
        val entries = (1..7).reversed().map { notice.copy(id = "pin-${host}/101-${it}") }
        val ids = listOf(entries.first().id, entries.first().id) + entries.map { it.id }
        val floating = floatingPinNotices(ids, entries)
        assertEquals(5, floating.size)
        assertEquals(entries.take(5).map { it.id }, floating.map { it.id })
        assertTrue(floatingPinNotices(ids, entries.map { it.copy(read = true) }).isEmpty())
    }

    @Test fun multipleNoticesAreInitiallyStackedAndExpandOnTap() {
        val notices = (1..5).reversed().map { notice.copy(id = "pin-${host}/101-${it}") }
        var expanded by mutableStateOf(false)
        compose.setContent {
            LovelySpaceTheme {
                PinVacancyToastStack(notices, listOf(view()), expanded, { expanded = it },
                    onEnter = {}, onReview = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("ほか4件").assertIsDisplayed()
        compose.onNodeWithText("ほか4件").performClick()
        compose.onNodeWithText("まとめる").assertIsDisplayed()
        compose.onNodeWithText("まとめる").performClick()
        compose.onNodeWithText("ほか4件").assertIsDisplayed()
    }

    @Test fun swipingLeftRemovesOnlyFloatingNotice() {
        val inboxEntries = (1..3).reversed().map { notice.copy(id = "pin-${host}/101-${it}") }
        var visible by mutableStateOf(inboxEntries)
        compose.setContent {
            LovelySpaceTheme {
                PinVacancyToastStack(visible, listOf(view()), expanded = false, onExpandedChange = {},
                    onEnter = {}, onReview = {}, onDismiss = { visible = visible - it })
            }
        }
        compose.onNodeWithTag("pin-vacancy-toast").performTouchInput { swipeLeft() }
        compose.runOnIdle {
            assertEquals(2, visible.size)
            assertEquals(3, inboxEntries.size) // ベル側の履歴は削除しない
        }
    }

    @Test fun swipingRightRemovesFloatingNotice() {
        var visible by mutableStateOf(listOf(notice))
        compose.setContent {
            LovelySpaceTheme {
                PinVacancyToastStack(visible, listOf(view()), expanded = false, onExpandedChange = {},
                    onEnter = {}, onReview = {}, onDismiss = { visible = visible - it })
            }
        }
        compose.onNodeWithTag("pin-vacancy-toast").performTouchInput { swipeRight() }
        compose.runOnIdle { assertTrue(visible.isEmpty()) }
    }

    @Test fun findingPreviewUsesOnlySamplesAndSafeTargets() {
        val samples = (1..6).map(NotificationSamples::floatingPreview)
        assertTrue(samples.all(NotificationSamples::isSample))
        assertTrue(samples.all { it.target == NotificationTarget.Rooms })
        assertEquals(6, samples.map { it.id }.distinct().size)
        assertTrue(samples.all { it.room?.name?.startsWith("サンプル") == true })
    }

    @Test fun settingsTestButtonQueuesUpToFiveCardsForFindingPageOnly() {
        var notices by mutableStateOf(emptyList<AppNotification>())
        var next = 0
        var route by mutableStateOf(Routes.SETTINGS_NOTIFICATIONS)
        var expanded by mutableStateOf(false)
        var tappedEnter = 0
        compose.setContent {
            LovelySpaceTheme {
                Column {
                    if (route == Routes.SETTINGS_NOTIFICATIONS) {
                        FloatingPreviewTestRow(onSendTest = {
                            next++
                            notices = (listOf(NotificationSamples.floatingPreview(next)) + notices)
                                .take(MAX_FLOATING_PIN_NOTICES)
                        }, queuedCount = notices.size, onViewPreview = { route = Routes.ROOMS })
                    }
                    if (shouldShowFloatingPreview(route, notices.size)) {
                        PinVacancyToastStack(notices, emptyList(), expanded, { expanded = it },
                            onEnter = { tappedEnter++ }, onReview = {},
                            onDismiss = { notices = notices - it }, preview = true)
                    }
                }
            }
        }
        repeat(6) { compose.onNodeWithContentDescription("フローティング通知をテスト").performClick() }
        compose.runOnIdle { assertEquals(5, notices.size) }
        compose.onNodeWithTag("pin-vacancy-toast").assertDoesNotExist()
        compose.onNodeWithText("見つけるで確認（5件）").performClick()
        compose.onNodeWithText("ほか4件").assertIsDisplayed()
        compose.onNodeWithText("ほか4件").performClick()
        compose.onNodeWithText("まとめる").assertIsDisplayed()
        compose.onNodeWithText("まとめる").performClick()
        compose.onNodeWithText("入室する").performClick()
        compose.runOnIdle { assertEquals(1, tappedEnter) }
        // 呼び出し元はサンプル操作を実際の入室・サイト通信に接続しない。
    }

}
