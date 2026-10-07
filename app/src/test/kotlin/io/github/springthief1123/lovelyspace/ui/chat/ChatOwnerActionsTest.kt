package io.github.springthief1123.lovelyspace.ui.chat

import io.github.springthief1123.lovelyspace.core.chat.ChatLine
import io.github.springthief1123.lovelyspace.core.chat.ChatPage
import io.github.springthief1123.lovelyspace.core.chat.ChatRoomRef
import io.github.springthief1123.lovelyspace.core.chat.ChatState
import io.github.springthief1123.lovelyspace.core.chat.ChatUpdate
import org.junit.Assert.*
import org.junit.Test

class ChatOwnerActionsTest {
    private val room = ChatRoomRef("2shot.chat.shalove.net", 900000001, "0123456789abcdef0123456789abcdef", "chah")
    private val owner = ChatUiState(isLoading = false, isOwner = true, isPublic = true, canChangePublic = true, waitingMessage = "合成の待機文")

    private fun update(entered: Boolean = false, guestLeft: Boolean = false, canBan: Boolean = false) = ChatUpdate(
        state = ChatState(fromSize = 0), newLines = emptyList(), clearLog = false, someoneEntered = entered,
        guestLeft = guestLeft, canBanGuest = canBan, endMessage = null, information = "", romCount = null, isPublic = null,
    )

    private fun page(isPublic: Boolean = true, message: String? = "合成の待機文", canBan: Boolean = false) = ChatPage(
        room = room, title = "チャH", myName = "タロウ", isOwner = true, isPublic = isPublic, lines = emptyList(),
        state = ChatState(fromSize = 0), waitingMessage = message, canBanGuest = canBan, canChangePublic = true,
    )

    @Test fun ownerActionsAreOnlyForOwnerInOpenRoom() {
        assertTrue(owner.showsOwnerActions)
        assertFalse(owner.copy(isOwner = false).showsOwnerActions)
        assertFalse(owner.copy(endMessage = "部屋は閉鎖されました").showsOwnerActions)
        assertFalse(owner.copy(isLeaving = true).showsOwnerActions)
        assertFalse(owner.copy(loadError = "合成のエラー").showsOwnerActions)
    }

    @Test fun banFollowsSiteRules() {
        assertTrue("入室者が来たら出す", canBanGuestAfter(false, update(entered = true)))
        assertFalse("相手が抜けたらサーバーの判定に従う", canBanGuestAfter(true, update(guestLeft = true, canBan = false)))
        assertTrue(canBanGuestAfter(false, update(guestLeft = true, canBan = true)))
        assertTrue("どちらでもなければそのまま", canBanGuestAfter(true, update()))
    }

    @Test fun clearRemovesLinesLocally() {
        val withLines = owner.copy(lines = listOf(UiLine(0, ChatLine("タロウ", false, "合成の発言", null), isMine = true)), ownerAction = OwnerAction.CLEAR_LOG)
        val cleared = withLines.afterOwnerAction(OwnerAction.CLEAR_LOG, page(), lastLineIdAtStart = 0)
        assertTrue(cleared.lines.isEmpty())
        assertNull(cleared.ownerAction)
    }

    @Test fun clearKeepsLinesThatArrivedDuringTheAction() {
        fun line(id: Long) = UiLine(id, ChatLine("ハナコ", true, "合成の発言 $id", null), isMine = false)
        // 新しい順。id 1 までが操作の開始時点にあった行、id 2 は操作中に届いた行。開始時に行が無ければ全部が操作中の行。
        val s = owner.copy(lines = listOf(line(2), line(1), line(0)), ownerAction = OwnerAction.CLEAR_LOG)
        assertEquals(listOf(2L), s.afterOwnerAction(OwnerAction.CLEAR_LOG, page(), lastLineIdAtStart = 1).lines.map { it.id })
        assertEquals(listOf(2L, 1L, 0L), s.afterOwnerAction(OwnerAction.CLEAR_LOG, page(), lastLineIdAtStart = null).lines.map { it.id })
    }

    @Test fun banHidesBanUnlessPageStillShowsIt() {
        val s = owner.copy(canBanGuest = true)
        assertFalse(s.afterOwnerAction(OwnerAction.BAN_GUEST, page(canBan = false)).canBanGuest)
        assertTrue(s.afterOwnerAction(OwnerAction.BAN_GUEST, page(canBan = true)).canBanGuest)
    }

    @Test fun waitingMessageComesFromResponsePage() {
        val changed = owner.afterOwnerAction(OwnerAction.CHANGE_MESSAGE, page(message = "新しい合成の待機文"))
        assertEquals("新しい合成の待機文", changed.waitingMessage)
    }

    @Test fun makingPrivateThatSiteRefusedIsReported() {
        val refused = owner.afterOwnerAction(OwnerAction.MAKE_PRIVATE, page(isPublic = true))
        assertTrue(refused.isPublic)
        assertNotNull(refused.ownerNotice)
        val done = owner.afterOwnerAction(OwnerAction.MAKE_PRIVATE, page(isPublic = false))
        assertFalse(done.isPublic)
        assertNull(done.ownerNotice)
        val unknown = owner.afterOwnerAction(OwnerAction.MAKE_PRIVATE, null)
        assertTrue("応答を確認できなければ表示を変えない", unknown.isPublic)
        assertEquals(OwnerAction.MAKE_PRIVATE.failure, unknown.ownerNotice)
    }

    @Test fun unreadableOwnerActionResponseIsReportedWithoutOptimisticChanges() {
        val line = UiLine(0, ChatLine("タロウ", false, "合成の発言", null), isMine = true)
        val s = owner.copy(lines = listOf(line), canBanGuest = true, ownerAction = OwnerAction.CLEAR_LOG)

        val failed = s.afterOwnerAction(OwnerAction.CLEAR_LOG, null, lastLineIdAtStart = 0)

        assertEquals(listOf(line), failed.lines)
        assertTrue(failed.canBanGuest)
        assertEquals(OwnerAction.CLEAR_LOG.failure, failed.ownerNotice)
        assertNull(failed.ownerAction)
    }
}
