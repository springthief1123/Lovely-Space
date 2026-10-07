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
        } finally { Dispatchers.resetMain() }
    }
    @Test fun leavingWithAutomaticOffCancelsManualPublicRead() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var requests = 0
            var cancelled = false
            val vm = PublicRoomViewModel {
                requests++
                try { awaitCancellation() } finally { cancelled = true }
            }
            vm.automatic(false); vm.refresh(); runCurrent()
            vm.stopRefresh(); runCurrent(); advanceTimeBy(60_000); runCurrent()
            assertTrue(cancelled)
            assertFalse(vm.state.value.loading)
            assertFalse(vm.state.value.automatic)
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
