package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.data.RoomListSource
import io.github.springthief1123.lovelyspace.data.SearchPreset
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @Test fun unifiedDiscoveryStillLoadsWhenGenrePreferenceWriteFails() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val preferences = object : io.github.springthief1123.lovelyspace.settings.RoomListPreferenceStore {
                override val roomListPreferences = kotlinx.coroutines.flow.flowOf(io.github.springthief1123.lovelyspace.settings.RoomListPreferences(lastGenreKey = "talk"))
                override suspend fun setLastRoomGenre(genreKey: String) { throw IOException("合成の保存失敗") }
            }
            val calls = mutableListOf<String>()
            val vm = SearchViewModel(RoomListSource { query, _ ->
                calls += query.genre.key
                page(query.genre.key, 1, listOf(room(1, query.genre.key)))
            }, preferences)
            runCurrent()
            assertEquals(listOf("talk"), calls)
            assertTrue(vm.state.value.initialized)
            assertEquals(1, vm.state.value.rooms.size)
            assertNotNull(vm.state.value.preferenceError)
            vm.criteria(RoomSearchCriteria(text = "テスト"))
            assertEquals(1, calls.size)
            assertEquals(1, vm.state.value.results.size)
        } finally { Dispatchers.resetMain() }
    }

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
    @Test fun initialSearchUsesCacheAndOnlyLaterUpdateIsForced() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val forced = mutableListOf<Boolean>()
            val vm = SearchViewModel(RoomListSource { query, force ->
                forced += force
                page(query.genre.key, 1, listOf(room(1)))
            })
            vm.refresh(); runCurrent()
            vm.refresh(); runCurrent()
            vm.genre(Genres["talk"]!!)
            vm.refresh(); runCurrent()
            assertEquals(listOf(false, true, false), forced)
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
    @Test fun applyingSameGenreKeepsPagesAndRestoresAgeInputsWithoutFetching() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var calls = 0
            val vm = SearchViewModel(RoomListSource { query, _ ->
                calls++
                page(query.genre.key, query.page, listOf(room(query.page.toLong())))
            })
            vm.refresh(); runCurrent()
            vm.more(); runCurrent()
            vm.minAge("9")
            assertFalse(vm.state.value.validAges)
            val saved = SearchPreset(label = "保存済み", genreKey = Genres.default.key,
                criteria = RoomSearchCriteria(name = "合成", minAge = 25, maxAge = 40, includeUnknownAge = false))
            vm.applyPreset(saved); runCurrent()
            assertEquals(2, calls)
            assertEquals(2, vm.state.value.page)
            assertEquals(2, vm.state.value.rooms.size)
            assertEquals(saved.criteria, vm.state.value.criteria)
            assertEquals("25", vm.state.value.minAgeInput)
            assertEquals("40", vm.state.value.maxAgeInput)
            assertTrue(vm.state.value.validAges)
            vm.applyPreset(saved.copy(criteria = RoomSearchCriteria()))
            assertEquals("", vm.state.value.minAgeInput)
            assertEquals("", vm.state.value.maxAgeInput)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun applyingDifferentGenreCancelsInflightRequestAndRequiresExplicitSearch() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var cancelled = false
            var calls = 0
            val vm = SearchViewModel(RoomListSource { _, _ ->
                calls++
                try { awaitCancellation() } finally { cancelled = true }
            })
            vm.refresh(); runCurrent()
            vm.applyPreset(SearchPreset(label = "別ジャンル", genreKey = "talk", criteria = RoomSearchCriteria()))
            runCurrent()
            assertTrue(cancelled)
            assertEquals(1, calls)
            assertEquals("talk", vm.state.value.genre.key)
            assertFalse(vm.state.value.loading)
            assertEquals(0, vm.state.value.page)
            assertTrue(vm.state.value.rooms.isEmpty())
        } finally { Dispatchers.resetMain() }
    }
}
