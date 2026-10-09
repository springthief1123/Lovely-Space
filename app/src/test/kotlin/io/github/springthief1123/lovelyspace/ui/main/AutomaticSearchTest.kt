package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.data.RoomListSource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticSearchTest {
    private fun room(id: Long) = Room(id, "zenkoku", RoomStatus.WAITING, RoomAction.ENTER, null, "合成$id", Gender.MALE, 25, null, "合成")
    private fun page(number: Int, rooms: List<Room>) = RoomListPage("zenkoku", rooms, null, null, number, 3, emptyMap(), null)

    @Test fun automaticDiscoveryLoadsAllPagesAndKeepsThemWhenHeadIsRefreshed() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val calls = mutableListOf<Int>()
            val vm = SearchViewModel(repository = RoomListSource { query, force ->
                if (calls.size < 4) assertFalse(force)
                calls += query.page
                page(query.page, listOf(room(query.page.toLong())))
            }, clock = { testScheduler.currentTime })
            val job = backgroundScope.launch { vm.monitor() }
            // まだ読んでいないページを先に 3 秒ごとに読み切り、その後に 1 ページ目を取り直す。
            // 0 秒: 1、3 秒: 2、6 秒: 3、9 秒: 1。
            runCurrent(); advanceTimeBy(9_001); runCurrent()
            assertEquals(listOf(1, 2, 3, 1), calls)
            assertEquals(setOf(1L, 2L, 3L), vm.state.value.rooms.map { it.id }.toSet())
            vm.refresh(); runCurrent()
            assertEquals(3, vm.state.value.rooms.size)
            job.cancelAndJoin()
            val stopped = calls.size
            advanceTimeBy(30_000); runCurrent()
            assertEquals(stopped, calls.size)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun discoveryUsesRepositoryObservationOrderInsteadOfCacheReadOrder() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val newest = room(1).copy(status = RoomStatus.FULL, action = RoomAction.PEEK, name = null)
            val source = object : RoomListSource {
                override suspend fun fetch(query: RoomQuery, force: Boolean) = observation(query).page
                override fun observation(query: RoomQuery) = io.github.springthief1123.lovelyspace.data.ObservedRoomPage(query,
                    page(query.page, if (query.page == 1) listOf(newest) else listOf(room(1), room(2))),
                    if (query.page == 1) 10_000L else 2_000L, if (query.page == 1) 10L else 2L)
            }
            val vm = SearchViewModel(source)
            vm.refresh(); runCurrent(); vm.more(); runCurrent()
            assertEquals(RoomAction.PEEK, vm.state.value.rooms.first().action)
            assertNull(vm.state.value.rooms.first().name)
            assertEquals(2, vm.state.value.rooms.size)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun leavingDuringManualRefreshCancelsTheRequestOwnedByTheViewModel() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var cancelled = false
            val vm = SearchViewModel(RoomListSource { _, _ ->
                try { awaitCancellation() } finally { cancelled = true }
            })
            vm.refresh(); runCurrent()
            val job = backgroundScope.launch { vm.monitor() }
            runCurrent(); job.cancelAndJoin(); runCurrent()
            assertTrue(cancelled)
            assertFalse(vm.state.value.loading)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun leavingWithAutomaticOffCancelsManualRefreshWithoutAMonitor() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var requests = 0
            var cancelled = false
            val vm = SearchViewModel(RoomListSource { _, _ ->
                requests++
                try { awaitCancellation() } finally { cancelled = true }
            })
            vm.automatic(false); vm.refresh(); runCurrent()
            assertTrue(vm.state.value.loading)
            // ON_PAUSE/onDisposeから呼び出す。自動巡回コルーチンは起動していない。
            vm.stopRefresh(); runCurrent(); advanceTimeBy(30_000); runCurrent()
            assertTrue(cancelled)
            assertFalse(vm.state.value.loading)
            assertFalse(vm.state.value.automatic)
            assertEquals(1, requests)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun automaticFailureRetriesTheSamePageAndCancellationReachesTheSource() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var fail = true
            var cancelled = false
            val calls = mutableListOf<Int>()
            val vm = SearchViewModel(repository = RoomListSource { query, _ ->
                calls += query.page
                if (query.page == 2 && fail) { fail = false; throw IOException("合成通信失敗") }
                if (calls.size > 3) { try { awaitCancellation() } finally { cancelled = true } }
                page(query.page, listOf(room(query.page.toLong())))
            }, clock = { testScheduler.currentTime })
            val job = backgroundScope.launch { vm.monitor() }
            runCurrent(); advanceTimeBy(3_001); runCurrent()
            assertNotNull(vm.state.value.error)
            advanceTimeBy(20_001); runCurrent()
            // 復旧時の新着優先取得のあと、失敗した2ページ目に戻る。
            advanceTimeBy(3_001); runCurrent()
            assertEquals(listOf(1, 2, 1, 2), calls)
            job.cancelAndJoin()
            assertTrue(cancelled)
            assertFalse(vm.state.value.loading)
        } finally { Dispatchers.resetMain() }
    }
}
