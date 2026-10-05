package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.data.RoomListSource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private fun room(id: Long, genre: String = "zenkoku") = Room(id, genre, RoomStatus.WAITING, RoomAction.ENTER,
        null, "合成$id", Gender.UNKNOWN, null, null, "テスト")
    private fun page(genre: String, number: Int, rooms: List<Room>) = RoomListPage(genre, rooms, null, null, number, 2, emptyMap(), null)

    @Test fun retriesFailedNextPageWithoutDiscardingEarlierResultsAndFiltersWithoutNetwork() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val calls = mutableListOf<Int>()
            var failMore = true
            val vm = SearchViewModel(RoomListSource { query, _ ->
                calls += query.page
                if (query.page == 2 && failMore) throw IOException("合成の通信エラー")
                page(query.genre.key, query.page, if (query.page == 1) listOf(room(1)) else listOf(room(1), room(2)))
            })
            vm.refresh(); runCurrent()
            assertEquals(1, vm.state.value.page)
            vm.criteria(RoomSearchCriteria(name = "該当なし"))
            assertTrue(vm.state.value.results.isEmpty())
            assertEquals(listOf(1), calls)
            vm.more(); runCurrent()
            assertTrue(vm.state.value.errorOnMore)
            assertEquals(1, vm.state.value.rooms.size)
            failMore = false
            vm.more(); runCurrent()
            assertEquals(listOf(1, 2, 2), calls)
            assertEquals(2, vm.state.value.rooms.size)
            assertEquals(2, vm.state.value.page)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun genreChangeCancelsPreviousRequestAndClearsItsScope() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var cancelled = false
            val vm = SearchViewModel(RoomListSource { query, _ ->
                if (query.genre == Genres.default) {
                    try { awaitCancellation() } finally { cancelled = true }
                }
                page(query.genre.key, 1, listOf(room(5, query.genre.key)))
            })
            vm.refresh(); runCurrent()
            val next = Genres["talk"]!!
            vm.genre(next); runCurrent()
            assertTrue(cancelled)
            assertEquals(0, vm.state.value.page)
            assertFalse(vm.state.value.loading)
            vm.refresh(); runCurrent()
            assertEquals(next, vm.state.value.genre)
            assertEquals("talk", vm.state.value.rooms.single().genreKey)
        } finally { Dispatchers.resetMain() }
    }
}
