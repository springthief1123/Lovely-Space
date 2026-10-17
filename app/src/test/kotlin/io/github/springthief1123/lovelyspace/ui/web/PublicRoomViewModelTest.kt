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
    @Test fun openingReadsAtOnceAndMonitorWaitsTheIntervalFromThatRead() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val reads = mutableListOf<Pair<Long, Boolean>>()
            val vm = PublicRoomViewModel(clock = { testScheduler.currentTime }) { manual ->
                reads += testScheduler.currentTime to manual
                PublicRoomPage("合成", emptyList(), "")
            }
            // 「覗く」で開いたらすぐ、押した読み込みとして読む。
            vm.open(); runCurrent()
            assertEquals(listOf(0L to true), reads)
            vm.open(); runCurrent()
            assertEquals(1, reads.size)
            // 画面が前面になって自動の読み直しが始まっても、開いた直後の読み込みから間隔を空ける。
            val job = backgroundScope.launch { vm.monitor { 5_000 } }
            runCurrent()
            assertEquals(1, reads.size)
            advanceTimeBy(5_001); runCurrent()
            assertEquals(listOf(0L to true, 5_000L to false), reads)
            job.cancelAndJoin()
        } finally { Dispatchers.resetMain() }
    }
    @Test fun aCancelledFirstReadIsRetriedAtOnceWhenTheScreenComesBack() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var requests = 0
            var hang = true
            val vm = PublicRoomViewModel(clock = { testScheduler.currentTime }) {
                requests++
                if (hang) awaitCancellation()
                PublicRoomPage("合成", emptyList(), "")
            }
            vm.open(); runCurrent()
            // 読み込み中に画面を離れて、すぐ戻る。
            vm.stopRefresh(); runCurrent()
            hang = false
            val job = backgroundScope.launch { vm.monitor { 20_000 } }
            runCurrent()
            assertEquals(2, requests)
            assertTrue(vm.state.value.opened)
            job.cancelAndJoin()
        } finally { Dispatchers.resetMain() }
    }
}
