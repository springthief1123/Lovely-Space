package io.github.springthief1123.lovelyspace.ui.shell

import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.Routes
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LovelyAppShellNavigationTest {
    @Test fun findingTabReselectScrollsOnlyWhenAlreadyOnFindingPage() {
        assertTrue(shouldScrollFindTabToTop(Routes.ROOMS, Routes.ROOMS))
        assertTrue(shouldScrollFindTabToTop(Routes.SEARCH, Routes.ROOMS))
        assertFalse(shouldScrollFindTabToTop(Routes.RADAR, Routes.ROOMS))
        assertFalse(shouldScrollFindTabToTop(Routes.PROFILE, Routes.ROOMS))
        assertFalse(shouldScrollFindTabToTop(Routes.ROOMS, Routes.RADAR))
        assertFalse(shouldScrollFindTabToTop(null, Routes.ROOMS))
    }

    @Test fun newRoomsBadgeAppearsOnlyOnFindingPageWithSearchClosed() {
        assertTrue(shouldShowNewRoomsBadge(Routes.ROOMS, 3, searchOpen = false))
        assertTrue(shouldShowNewRoomsBadge(Routes.SEARCH, 1, searchOpen = false))
        assertFalse(shouldShowNewRoomsBadge(Routes.ROOMS, 0, searchOpen = false))
        assertFalse(shouldShowNewRoomsBadge(Routes.ROOMS, 3, searchOpen = true))
        assertFalse(shouldShowNewRoomsBadge(Routes.RADAR, 3, searchOpen = false))
    }

    @Test fun actualNoticesCoverSubpagesButPreviewIsOnlyOnFinding() {
        assertTrue(shouldShowFloatingPreview(Routes.ROOMS, 2))
        assertTrue(shouldShowFloatingPreview(Routes.SEARCH, 1))
        assertFalse(shouldShowFloatingPreview(Routes.RADAR, 2))
        assertFalse(shouldShowFloatingPreview(Routes.SETTINGS_NOTIFICATIONS, 2))
        assertFalse(shouldShowFloatingPreview(Routes.ROOMS, 0))
        assertEquals(108.dp, floatingNoticeBottomOffset(Routes.ROOMS, true, 100.dp, false))
        assertEquals(180.dp, floatingNoticeBottomOffset(Routes.ROOMS, true, 100.dp, true))
        assertEquals(16.dp, floatingNoticeBottomOffset(Routes.SETTINGS_NOTIFICATIONS, false, 100.dp, false))
        assertEquals(8.dp, floatingNoticeBottomOffset(Routes.CHAT, false, 100.dp, false))
        assertEquals(160.dp, floatingNoticeBottomOffset(Routes.CHAT, false, 100.dp, false, 152.dp))
        assertEquals(312.dp, floatingNoticeBottomOffset(Routes.CHAT, false, 100.dp, false, 304.dp))
        assertEquals(80.dp, floatingNoticeBottomOffset(Routes.CREATE, false, 100.dp, false))
    }

    @Test fun bottomContentInsetDoesNotReserveFabSpace() {
        assertEquals(LovelySpacing.bottomContentInset, lovelyBottomContentInset(100.dp))
        assertEquals(180.dp, lovelyBottomContentInset(100.dp, showResumeBar = true))
        assertEquals(150.dp, lovelyBottomContentInset(150.dp))
    }

    @Test fun measuredChatBottomBarTracksChangesAndDoesNotClearNewOwners() {
        val shell = LovelyShellState()
        val oldChat = Any()
        val newChat = Any()
        assertEquals(0.dp, shell.chatBottomBarHeight)
        shell.reportChatBottomBarHeight(oldChat, 104.dp)
        assertEquals(104.dp, shell.chatBottomBarHeight)
        // 文章が5行になったり復旧操作が表示されると、測定値が変わる。
        shell.reportChatBottomBarHeight(oldChat, 292.dp)
        assertEquals(292.dp, shell.chatBottomBarHeight)
        shell.reportChatBottomBarHeight(newChat, 120.dp)
        shell.releaseChatBottomBarHeight(oldChat)
        assertEquals(120.dp, shell.chatBottomBarHeight)
        shell.releaseChatBottomBarHeight(newChat)
        assertEquals(0.dp, shell.chatBottomBarHeight)
    }

    @Test fun scrollActionRemainsAvailableAndIsReleasedOnlyByItsOwner() {
        val shell = LovelyShellState()
        val owner = Any()
        var clicked = 0
        val action = ShellScrollToTop(newCount = 2) { clicked++ }

        shell.publish(owner, searchButton = null, scrollToTop = action)
        assertSame(action, shell.scrollToTop)
        shell.scrollToTop?.onClick?.invoke()
        assertEquals(1, clicked)
        shell.release(Any())
        assertSame(action, shell.scrollToTop)
        shell.release(owner)
        assertNull(shell.scrollToTop)
    }
}
