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
            vm.criteria(RoomSearchCriteria(text = "テスト 該当なし", keywordMode = KeywordMode.ANY))
            assertEquals(1, calls.size)
            assertEquals(1, vm.state.value.results.size)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun keywordScopeMovesTheWordAndResetsWithTheCriteria() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = SearchViewModel(RoomListSource { query, _ -> page(query.genre.key, 1, listOf(room(1))) })
            vm.refresh(); runCurrent()
            vm.keyword("合成")
            assertEquals(RoomSearchCriteria(text = "合成"), vm.state.value.criteria)
            vm.keywordScope(KeywordScope.NAME)
            assertEquals(RoomSearchCriteria(name = "合成"), vm.state.value.criteria)
            vm.keyword("別")
            assertEquals(RoomSearchCriteria(name = "別"), vm.state.value.criteria)
            val draft = vm.resetCriteria()!!
            assertEquals(KeywordScope.ALL, vm.state.value.keywordScope)
            vm.restoreCriteria(draft)
            assertEquals(KeywordScope.NAME, vm.state.value.keywordScope)
            vm.clearHiddenKeywords()
            assertEquals(RoomSearchCriteria(name = "別"), vm.state.value.criteria)
            vm.stopRefresh()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun resettingCriteriaCanBeUndoneWithoutNetwork() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val calls = mutableListOf<Int>()
            val vm = SearchViewModel(RoomListSource { query, _ -> calls += query.page; page(query.genre.key, 1, listOf(room(1))) })
            vm.refresh(); runCurrent()
            assertNull(vm.resetCriteria())
            // 端末側だけの条件（語・年齢の入力途中）なので、リセットと元に戻すで通信しない。
            vm.criteria(RoomSearchCriteria(text = "合成 該当なし", keywordMode = KeywordMode.ANY, excluded = "除外"))
            vm.minAge("1")
            val before = vm.state.value
            val draft = vm.resetCriteria()
            assertNotNull(draft)
            assertEquals(RoomSearchCriteria(), vm.state.value.criteria)
            assertEquals("", vm.state.value.minAgeInput)
            // 入力途中の年齢も含めて戻す。
            vm.restoreCriteria(draft!!)
            assertEquals(before.criteria, vm.state.value.criteria)
            assertEquals("1", vm.state.value.minAgeInput)
            runCurrent()
            assertEquals(listOf(1), calls)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun invalidAgeInputOnlyDropsTheAgeConditionAndKeepsTheList() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = SearchViewModel(RoomListSource { query, _ ->
                page(query.genre.key, 1, listOf(room(1).copy(age = 20), room(2).copy(age = 40, gender = Gender.FEMALE)))
            })
            vm.refresh(); runCurrent()
            vm.criteria(RoomSearchCriteria(minAge = 30, gender = Gender.FEMALE))
            vm.minAge("30")
            assertEquals(listOf(2L), vm.state.value.results.map { it.id })
            // 入力途中の「3」は範囲外。年齢の条件だけを外し、性別の条件と一覧は残す。
            vm.minAge("3")
            assertFalse(vm.state.value.validAges)
            assertNull(vm.state.value.effectiveCriteria.minAge)
            assertEquals(Gender.FEMALE, vm.state.value.effectiveCriteria.gender)
            assertEquals(listOf(2L), vm.state.value.results.map { it.id })
            vm.criteria(vm.state.value.criteria.copy(gender = null))
            assertEquals(listOf(1L, 2L), vm.state.value.results.map { it.id })
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
            vm.criteria(RoomSearchCriteria(name = "該当なし 不一致", keywordMode = KeywordMode.ANY))
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
                criteria = RoomSearchCriteria(name = "abc", minAge = 25, maxAge = 40, includeUnknownAge = false))
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

    @Test fun siteConditionsAreSentToTheSiteAndRestartTheListFromItsFirstPage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val queries = mutableListOf<RoomQuery>()
            val vm = SearchViewModel(RoomListSource { query, _ ->
                queries += query
                val rooms = if (query.sex == Gender.FEMALE) listOf(room(9).copy(gender = Gender.FEMALE))
                    else listOf(room(query.page.toLong()), room(10L + query.page))
                page(query.genre.key, query.page, rooms)
            })
            vm.refresh(); runCurrent()
            vm.more(); runCurrent()
            assertEquals(4, vm.state.value.rooms.size)
            // 本家で探せない語（英字は大文字・小文字の扱いが違う）は本家に渡さず、取得済みに掛けるだけ。
            vm.criteria(RoomSearchCriteria(text = "abc"))
            advanceTimeBy(SearchViewModel.REQUERY_DELAY_MS + 1); runCurrent()
            assertEquals(2, queries.size)
            // 共通検索欄の語は、本家の名前検索と募集文検索の 2 つの一覧で探し、合わせて出す。
            vm.criteria(RoomSearchCriteria(text = "合成"))
            advanceTimeBy(SearchViewModel.REQUERY_DELAY_MS + 1); runCurrent()
            assertEquals(listOf(RoomQuery(Genres.default, name = "合成"), RoomQuery(Genres.default, message = "合成")), queries.takeLast(2))
            assertEquals(listOf(11L, 1L), vm.state.value.rooms.map { it.id })
            assertEquals(2, vm.state.value.page)
            assertTrue(vm.state.value.newRoomIds.isEmpty())
            queries.clear()
            // 性別は本家で絞る。入力が続く間は待ち、新しい絞り込みの 1 ページ目から一覧を作り直す。
            vm.criteria(RoomSearchCriteria(gender = Gender.FEMALE))
            runCurrent()
            assertTrue(queries.isEmpty())
            advanceTimeBy(SearchViewModel.REQUERY_DELAY_MS + 1); runCurrent()
            assertEquals(RoomQuery(Genres.default, sex = Gender.FEMALE), queries.last())
            assertEquals(listOf(9L), vm.state.value.rooms.map { it.id })
            assertEquals(1, vm.state.value.page)
            assertTrue(vm.state.value.newRoomIds.isEmpty())
        } finally { Dispatchers.resetMain() }
    }

    @Test fun changingSiteConditionsCancelsTheOldRequestInsteadOfShowingIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val gate = CompletableDeferred<Unit>()
            val queries = mutableListOf<RoomQuery>()
            val vm = SearchViewModel(RoomListSource { query, _ ->
                queries += query
                if (query.waitingOnly == null) gate.await()
                page(query.genre.key, 1, listOf(room(if (query.waitingOnly == null) 1L else 2L)))
            })
            vm.refresh(); runCurrent()
            vm.criteria(RoomSearchCriteria(waitingOnly = true))
            advanceTimeBy(SearchViewModel.REQUERY_DELAY_MS + 1); runCurrent()
            gate.complete(Unit); runCurrent()
            assertEquals(RoomQuery(Genres.default, waitingOnly = true), queries.last())
            assertEquals(listOf(2L), vm.state.value.rooms.map { it.id })
            assertFalse(vm.state.value.loading)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun listFromOldSiteConditionsIsMarkedUntilTheNewConditionsLoad() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var fail = true
            val vm = SearchViewModel(RoomListSource { query, _ ->
                if (query.sex == null && fail) throw IOException("合成の通信エラー")
                page(query.genre.key, 1, listOf(room(if (query.sex == null) 1L else 2L)))
            })
            vm.criteria(RoomSearchCriteria(gender = Gender.FEMALE))
            vm.refresh(); runCurrent()
            assertFalse(vm.state.value.awaitingNewConditions)
            // 女性 → 指定なしへ広げると、取り直すまでは前の条件で取った部屋しか無い。
            vm.criteria(RoomSearchCriteria())
            assertTrue(vm.state.value.awaitingNewConditions)
            advanceTimeBy(SearchViewModel.REQUERY_DELAY_MS + 1); runCurrent()
            assertNotNull(vm.state.value.error)
            assertTrue(vm.state.value.awaitingNewConditions)
            fail = false
            vm.refresh(); runCurrent()
            assertFalse(vm.state.value.awaitingNewConditions)
            assertEquals(listOf(1L), vm.state.value.rooms.map { it.id })
        } finally { Dispatchers.resetMain() }
    }
}
