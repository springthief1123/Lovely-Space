package io.github.springthief1123.lovelyspace.ui.web

import io.github.springthief1123.lovelyspace.core.chat.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PublicRoomViewModelTest {
    @Test fun duplicateLinesArePreservedAndClosureStopsShowingOldConversation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val line = ChatLine("合成", false, "同じ発言", null)
            var unavailable = false
            val vm = PublicRoomViewModel {
                if (unavailable) throw PublicRoomUnavailableException("合成の非公開変更")
                PublicRoomPage("合成", listOf(line, line), "")
            }
            vm.refresh(); runCurrent()
            val first = vm.state.value.lines
            assertEquals(2, first.size)
            assertEquals(2, first.map { it.id }.toSet().size)
            vm.refresh(); runCurrent()
            assertEquals(first, vm.state.value.lines)
            unavailable = true; vm.refresh(); runCurrent()
            assertTrue(vm.state.value.lines.isEmpty())
            assertFalse(vm.state.value.automatic)
            assertFalse(vm.state.value.opened)
            assertNotNull(vm.state.value.error)
            unavailable = false; vm.refresh(); runCurrent()
            assertTrue(vm.state.value.automatic)
            assertTrue(vm.state.value.opened)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun guestLinesGoRightAndRolesSurviveScrollingOut() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val entered = ChatLine(null, false, "タロウ(男)さん(Android 一時ID Ab3dE)が入室しましたので、このチャットルームをロックしました。", null)
            val owner = ChatLine("ミナ", true, "合成の発言", null)
            val guest = ChatLine("タロウ", false, "合成の返事", null)
            var page = listOf(guest, owner, entered)
            val vm = PublicRoomViewModel { PublicRoomPage("合成", page, "") }
            vm.refresh(); runCurrent()
            assertEquals(listOf(true, false, false), vm.state.value.lines.map { it.isMine })
            // お知らせが直近の行から外れても、覚えた名前で見分け続ける。
            page = listOf(guest.copy(text = "次の返事"), guest, owner)
            vm.refresh(); runCurrent()
            assertEquals(listOf(true, true, false), vm.state.value.lines.map { it.isMine })
        } finally { Dispatchers.resetMain() }
    }
    @Test fun leavingCancelsManualPublicRead() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var requests = 0
            var cancelled = false
            val vm = PublicRoomViewModel {
                requests++
                try { awaitCancellation() } finally { cancelled = true }
            }
            vm.refresh(); runCurrent()
            vm.stopRefresh(); runCurrent(); advanceTimeBy(60_000); runCurrent()
            assertTrue(cancelled)
            assertFalse(vm.state.value.loading)
            assertEquals(1, requests)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun leavingPublicScreenCancelsReadAndDoesNotPollInBackground() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var requests = 0
            var cancelled = false
            val vm = PublicRoomViewModel {
                requests++
                try { awaitCancellation() } finally { cancelled = true }
            }
            val job = backgroundScope.launch { vm.monitor() }
            runCurrent(); job.cancelAndJoin()
            advanceTimeBy(60_000); runCurrent()
            assertTrue(cancelled)
            assertFalse(vm.state.value.loading)
            assertEquals(1, requests)
        } finally { Dispatchers.resetMain() }
    }
}
