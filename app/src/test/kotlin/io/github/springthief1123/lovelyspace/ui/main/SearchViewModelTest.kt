package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.data.ObservedRoomListSource
import io.github.springthief1123.lovelyspace.data.ObservedRoomPage
import io.github.springthief1123.lovelyspace.data.RoomListSource
import io.github.springthief1123.lovelyspace.data.SearchPreset
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
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
    @Test fun pressingRefreshRepeatedlyRefetchesWhileLoadingAndFoldsExtraPressesIntoOne() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val releases = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            var automaticCalls = 0
            var manualCalls = 0
            val vm = SearchViewModel(object : RoomListSource {
                override suspend fun fetch(query: RoomQuery, force: Boolean): RoomListPage { automaticCalls++; return page(query.genre.key, 1, listOf(room(1))).copy(lastPage = 1) }
                override suspend fun fetchNow(query: RoomQuery): RoomListPage {
                    manualCalls++
                    releases.receive()
                    // 1 ページだけの一覧にして、取り直しで消えた部屋が残らないようにする。
                    return page(query.genre.key, 1, listOf(room(1), room(manualCalls + 1L))).copy(lastPage = 1)
                }
            })
            vm.refresh(); runCurrent()
            assertEquals(1, automaticCalls)
            // 押すと、本家の「最新更新」と同じく取り直す。読み込み中でも押せる。
            vm.refresh(); runCurrent()
            assertEquals(1, manualCalls)
            assertTrue(vm.state.value.loading)
            // 取り直し中に何度押しても、終わった後の 1 回にまとめる。
            repeat(3) { vm.refresh() }; runCurrent()
            assertEquals(1, manualCalls)
            releases.send(Unit); runCurrent()
            assertEquals(2, manualCalls)
            assertTrue(vm.state.value.loading)
            releases.send(Unit); runCurrent()
            assertEquals(2, manualCalls)
            assertFalse(vm.state.value.loading)
            assertEquals(setOf(1L, 3L), vm.state.value.rooms.map { it.id }.toSet())
            // 終わった後に押せば、また取り直す。
            vm.refresh(); runCurrent()
            assertEquals(3, manualCalls)
            vm.stopRefresh(); runCurrent()
            assertFalse(vm.state.value.loading)
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

    @Test fun everyConditionIsJudgedOnTheDeviceAcrossAllPagesWithoutRefetching() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val queries = mutableListOf<RoomQuery>()
            val vm = SearchViewModel(RoomListSource { query, _ ->
                queries += query
                val rooms = if (query.page == 1) listOf(room(1).copy(name = "ゲーム好き"), room(2).copy(gender = Gender.FEMALE))
                    else listOf(room(3).copy(message = "げーむしよ", status = RoomStatus.FULL, action = RoomAction.PEEK))
                page(query.genre.key, query.page, rooms)
            })
            vm.refresh(); runCurrent()
            vm.more(); runCurrent()
            assertEquals(listOf(RoomQuery(Genres.default), RoomQuery(Genres.default, page = 2)), queries)
            // 語・性別・待機は本家に渡さず、取得済みの全ページにその場で掛ける。ひらがな・カタカナも同じに扱う。
            vm.criteria(RoomSearchCriteria(text = "ゲーム"))
            assertEquals(listOf(1L, 3L), vm.state.value.results.map { it.id })
            vm.criteria(RoomSearchCriteria(text = "ゲーム", waitingOnly = true))
            assertEquals(listOf(1L), vm.state.value.results.map { it.id })
            vm.criteria(RoomSearchCriteria(gender = Gender.FEMALE))
            assertEquals(listOf(2L), vm.state.value.results.map { it.id })
            advanceTimeBy(60_000); runCurrent()
            assertEquals(2, queries.size)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun pagesReadByOtherFeaturesAreShownAtOnceUnlessTheyAreOld() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val wall = 1_000_000L
            val flow = MutableStateFlow(mapOf(
                RoomQuery(Genres.default) to ObservedRoomPage(RoomQuery(Genres.default), page(Genres.default.key, 1, listOf(room(1))), wall - 1_000, 1),
                // 他のジャンル・本家側で絞った一覧・古い確認は取り込まない。
                RoomQuery(Genres["talk"]!!) to ObservedRoomPage(RoomQuery(Genres["talk"]!!), page("talk", 1, listOf(room(7, "talk"))), wall, 2),
                RoomQuery(Genres.default, sex = Gender.FEMALE) to ObservedRoomPage(RoomQuery(Genres.default, sex = Gender.FEMALE), page(Genres.default.key, 1, listOf(room(8))), wall, 3),
                RoomQuery(Genres.default, page = 2) to ObservedRoomPage(RoomQuery(Genres.default, page = 2), page(Genres.default.key, 2, listOf(room(9))), wall - SearchViewModel.SHARED_MAX_AGE_MS - 1, 4),
            ))
            var calls = 0
            val source = object : ObservedRoomListSource {
                override val observations = flow
                override fun observation(query: RoomQuery) = flow.value[query]
                override suspend fun fetch(query: RoomQuery, force: Boolean): RoomListPage { calls++; return page(query.genre.key, query.page, emptyList()) }
            }
            val vm = SearchViewModel(source, now = { wall })
            runCurrent()
            assertEquals(listOf(1L), vm.state.value.rooms.map { it.id })
            // レーダーなどが後から読んだページも、この画面で取り直さずに出す。
            val second = ObservedRoomPage(RoomQuery(Genres.default, page = 2), page(Genres.default.key, 2, listOf(room(4))), wall, 5)
            flow.value = flow.value + (second.query to second)
            runCurrent()
            assertEquals(listOf(1L, 4L), vm.state.value.rooms.map { it.id })
            assertEquals(2, vm.state.value.page)
            assertEquals(0, calls)
            // 開き直したジャンルも、共有の一覧があればすぐ出す。
            vm.genre(Genres["talk"]!!)
            assertEquals(listOf(7L), vm.state.value.rooms.map { it.id })
        } finally { Dispatchers.resetMain() }
    }

    @Test fun readingMoreFetchesTheFirstPageWhenOnlyALaterPageCameFromOtherFeatures() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val second = ObservedRoomPage(RoomQuery(Genres.default, page = 2), page(Genres.default.key, 2, listOf(room(4))), 1_000_000L, 1)
            val flow = MutableStateFlow(mapOf(second.query to second))
            val calls = mutableListOf<Int>()
            val source = object : ObservedRoomListSource {
                override val observations = flow
                override fun observation(query: RoomQuery) = flow.value[query]
                override suspend fun fetch(query: RoomQuery, force: Boolean): RoomListPage { calls += query.page; return page(query.genre.key, query.page, listOf(room(1))) }
            }
            val vm = SearchViewModel(source, now = { 1_000_000L })
            runCurrent()
            assertTrue(vm.state.value.canLoadMore)
            // 取り込んだのが 2 ページ目だけなら、続きの読み込みは未読の 1 ページ目を取る。
            vm.more(); runCurrent()
            assertEquals(listOf(1), calls)
            assertEquals(setOf(1L, 4L), vm.state.value.rooms.map { it.id }.toSet())
        } finally { Dispatchers.resetMain() }
    }
}
