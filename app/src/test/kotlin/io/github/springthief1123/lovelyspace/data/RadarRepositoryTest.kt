package io.github.springthief1123.lovelyspace.data

import android.app.Application
import androidx.room.Room as RoomDb
import androidx.test.core.app.ApplicationProvider
import io.github.springthief1123.lovelyspace.core.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RadarRepositoryTest {
    private val room = Room(42, "zenkoku", RoomStatus.WAITING, RoomAction.ENTER, null, "合成", Gender.FEMALE, 25, null, "本文")
    private class Lists : ObservedRoomListSource {
        override val observations = MutableStateFlow<Map<RoomQuery, ObservedRoomPage>>(emptyMap())
        var rooms = emptyList<Room>()
        var roomsByPage: Map<Int, List<Room>> = emptyMap()
        var revision = 0L
        var lastPage = 1
        var clock: Long? = null
        var onFetch: (suspend (RoomQuery) -> Unit)? = null
        val calls = mutableListOf<RoomQuery>()
        override fun observation(query: RoomQuery) = observations.value[query]
        override suspend fun fetch(query: RoomQuery, force: Boolean): RoomListPage {
            calls += query
            onFetch?.invoke(query)
            val page = RoomListPage(query.genre.key, roomsByPage[query.page] ?: rooms, null, null, query.page, lastPage, emptyMap(), null)
            revision++
            observations.value += query to ObservedRoomPage(query, page, clock ?: revision * 1000, revision)
            return page
        }
    }
    @Test fun matchResultsAndEventRoomSnapshotsSurviveWithoutTreatingHistoryAsCurrent() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            searches.save(SearchPreset("a", "合成条件", "zenkoku", RoomSearchCriteria()))
            val lists = Lists().apply { rooms = listOf(room) }
            val job = SupervisorJob(backgroundScope.coroutineContext[Job])
            val radar = RadarRepository(db.presets(), lists, searches, RoomPreferenceRepository(db), CoroutineScope(backgroundScope.coroutineContext + job))
            radar.state.first { it.loaded }; radar.setPlan("a", true); radar.scan()
            assertEquals(listOf(room), radar.state.value.results["a"]?.rooms)
            lists.rooms = listOf(room, room.copy(id = 43, name = "追加の合成")); radar.scan()
            val event = radar.state.value.events.single()
            assertEquals(43L, event.rooms.single().id)
            assertEquals(1, event.page)
            job.cancelAndJoin()
            val restored = RadarRepository(db.presets(), Lists(), searches, RoomPreferenceRepository(db), backgroundScope)
            restored.state.first { it.loaded }
            assertEquals(event, restored.state.value.events.single())
            assertTrue(restored.state.value.results.isEmpty())
        } finally { db.close() }
    }
    @Test fun failingTransactionCannotUndoAutomaticPollingStop() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            var suspendWrites = false
            val writing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val dao = object : PresetDao by db.presets() {
                override suspend fun put(value: LocalState) {
                    if (suspendWrites && org.json.JSONObject(value.value).getJSONArray("plans").length() == 0) {
                        writing.complete(Unit)
                        release.await()
                        throw java.io.IOException("合成の保存失敗")
                    }
                    db.presets().put(value)
                }
            }
            val searches = SearchPresetRepository(db)
            searches.save(SearchPreset("auto", "合成", "zenkoku", RoomSearchCriteria()))
            val radar = RadarRepository(dao, Lists(), searches, RoomPreferenceRepository(db), backgroundScope)
            radar.state.first { it.loaded }; radar.setPlan("auto", true)
            suspendWrites = true
            var failed = false
            val edit = backgroundScope.launch {
                try { radar.pauseAllPlans() } catch (_: java.io.IOException) { failed = true }
            }
            writing.await()
            radar.automatic(false)
            release.complete(Unit); edit.join()
            assertTrue(failed)
            assertFalse(radar.state.value.automatic)
            assertEquals(setOf("auto"), radar.state.value.plans)
        } finally { db.close() }
    }
    @Test fun latestPagePriorityDoesNotResetTheRemainingPageCursorAndLiveRoomsSpanPages() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            searches.save(SearchPreset("auto", "合成の自動巡回", "zenkoku", RoomSearchCriteria()))
            val lists = Lists().apply { lastPage = 3; roomsByPage = mapOf(1 to listOf(room), 2 to listOf(room.copy(id = 43)), 3 to listOf(room.copy(id = 44))) }
            val radar = RadarRepository(db.presets(), lists, searches, RoomPreferenceRepository(db), backgroundScope)
            radar.state.first { it.loaded }; radar.setPlan("auto", true)
            radar.scan()
            radar.scan()
            radar.scan(latestFirst = true)
            assertEquals(3, radar.state.value.nextPages["auto"])
            radar.scan()
            assertEquals(listOf(1, 2, 1, 3), lists.calls.map { it.page })
            assertEquals(setOf(42L, 43L, 44L), radar.state.value.livePages["zenkoku"]!!.rooms.map { it.id }.toSet())
            lists.roomsByPage = mapOf(1 to listOf(room.copy(id = 45)), 2 to listOf(room.copy(id = 43)), 3 to listOf(room.copy(id = 44)))
            radar.scan(latestFirst = true)
            // 1 ページ目から消えた 42 は後ろのページへずれただけかもしれないので、2 ページ目を読んだうえで 1 ページ目にも無いと分かるまで残す。
            assertEquals(setOf(42L, 43L, 44L, 45L), radar.state.value.livePages["zenkoku"]!!.rooms.map { it.id }.toSet())
            radar.scan(latestFirst = true)
            assertEquals(setOf(42L, 43L, 44L, 45L), radar.state.value.livePages["zenkoku"]!!.rooms.map { it.id }.toSet())
            radar.scan(); radar.scan()
            assertEquals(listOf(1, 2), lists.calls.takeLast(2).map { it.page })
            radar.scan(latestFirst = true)
            assertEquals(setOf(43L, 44L, 45L), radar.state.value.livePages["zenkoku"]!!.rooms.map { it.id }.toSet())
        } finally { db.close() }
    }
    @Test fun resultsOfAnOlderDefinitionCannotBeShownAsMatchesForAnEditedPreset() {
        val original = SearchPreset("a", "合成条件", "zenkoku", RoomSearchCriteria())
        val result = RadarResult(original.id, 1000, 1, 1, listOf(room), original.genreKey, original.criteria)
        val state = RadarState(results = mapOf(original.id to result))
        assertSame(result, state.resultFor(original.copy(label = "名前だけ変更")))
        assertNull(state.resultFor(original.copy(criteria = RoomSearchCriteria(name = "別条件"))))
        assertNull(state.resultFor(original.copy(genreKey = "talk")))
    }
    @Test fun legacyTargetsLoadOnlyForTheMoveToPinsAndAreClearedAfterwards() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            // 以前の追跡は、照合に使っていた姿（identity）とメモだけを読む。
            val target = """{"id":42,"genre":"zenkoku","status":"FULL","action":"ENTER","name":null,"gender":"UNKNOWN","age":null,"area":null,"message":"本文"}"""
            val identity = """{"id":42,"genre":"zenkoku","status":"WAITING","action":"ENTER","name":"合成","gender":"FEMALE","age":25,"area":null,"message":"本文"}"""
            db.presets().put(LocalState("radar_v1", """{"plans":[],"targets":[{"room":$target,"identity":$identity,"at":null,"evidence":"MATCH","note":"合成のメモ"}],"events":[]}"""))
            val searches = SearchPresetRepository(db)
            val lists = Lists()
            val radar = RadarRepository(db.presets(), lists, searches, RoomPreferenceRepository(db), backgroundScope)
            radar.state.first { it.loaded }
            assertEquals(listOf(LegacyTarget(room, "合成のメモ")), radar.state.value.legacyTargets)
            // 追跡は見張らないので、巡回しても取得しない。
            radar.scan()
            assertTrue(lists.calls.isEmpty())
            radar.clearLegacyTargets()
            val restored = RadarRepository(db.presets(), Lists(), searches, RoomPreferenceRepository(db), backgroundScope)
            restored.state.first { it.loaded }
            assertTrue(restored.state.value.legacyTargets.isEmpty())
        } finally { db.close() }
    }
    @Test fun legacyTextOnlyHistoryStillLoads() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            db.presets().put(LocalState("radar_v1", """{"plans":[],"targets":[],"events":[{"at":1000,"text":"合成の旧履歴"}]}"""))
            val radar = RadarRepository(db.presets(), Lists(), SearchPresetRepository(db), RoomPreferenceRepository(db), backgroundScope)
            radar.state.first { it.loaded }
            assertEquals("合成の旧履歴", radar.state.value.events.single().text)
            assertTrue(radar.state.value.events.single().rooms.isEmpty())
        } finally { db.close() }
    }
    @Test fun ngWordsKeepRoomsOutOfPlanMatchesAndTurningThemOffDoesNotNotifyOldRooms() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            searches.save(SearchPreset("a", "合成", "zenkoku", RoomSearchCriteria()))
            val ngRoom = room.copy(id = 43, message = "合成 PayPay")
            val lists = Lists().apply { rooms = listOf(room, ngRoom); clock = 1_000_000 }
            val ng = MutableStateFlow(NgWordFilter.of(NgWordSet.BUILT_IN))
            val radar = RadarRepository(db.presets(), lists, searches, RoomPreferenceRepository(db), backgroundScope, ngWords = ng) { 1_000_000 }
            radar.state.first { it.loaded }; radar.setPlan("a", true); radar.scan()
            assertEquals(listOf(42L), radar.state.value.results["a"]?.rooms?.map { it.id })
            // NG ワードを切ると、手元の一覧からすぐ一致を出し直す。前からあった部屋は新着として知らせない。
            ng.value = NgWordFilter.NONE
            radar.state.first { it.results["a"]?.rooms?.size == 2 }
            radar.scan()
            assertTrue(radar.state.value.events.none { it.kind == RadarEventKind.SEARCH_MATCH })
            // 名前で追う候補には NG ワードを掛けない。
            ng.value = NgWordFilter.of(NgWordSet.BUILT_IN)
            radar.saveCandidate(CandidateRule("c", "合成候補", "zenkoku", "合成"))
            radar.state.first { it.results["a"]?.rooms?.size == 1 }
            assertEquals(listOf(42L, 43L), radar.state.value.candidateResults["c"]?.rooms?.map { it.id }?.sorted())
        } finally { db.close() }
    }
    @Test fun radarWaitsForSavedNgWordsBeforeLoadingAndRefiltersWithoutRecentSharedPages() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            searches.save(SearchPreset("a", "合成", "zenkoku", RoomSearchCriteria()))
            val ngRoom = room.copy(id = 43, message = "合成 PayPay")
            var clock = 1_000_000L
            val lists = Lists().apply { rooms = listOf(room, ngRoom); this.clock = clock }
            val saved = MutableStateFlow<NgWordFilter?>(null)
            val radar = RadarRepository(db.presets(), lists, searches, RoomPreferenceRepository(db), backgroundScope, ngWords = saved.filterNotNull()) { clock }
            // 背景の巡回は loaded を待つので、保存した NG ワードを読むまでは読み込み済みにしない。
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(300) }
            assertFalse(radar.state.value.loaded)
            saved.value = NgWordFilter.of(NgWordSet.BUILT_IN)
            radar.state.first { it.loaded }; radar.setPlan("a", true); radar.scan()
            assertEquals(listOf(42L), radar.state.value.results["a"]?.rooms?.map { it.id })
            // 共有の一覧が古くなった後で NG ワードを変えても、手元の全ページから一致を出し直す。
            clock += 10 * 60_000L
            saved.value = NgWordFilter.NONE
            radar.state.first { it.results["a"]?.rooms?.size == 2 }
            // 一致件数の説明も同じ件数に合わせる。
            assertTrue(radar.state.value.scopes["a"].orEmpty().contains("一致2件"))
        } finally { db.close() }
    }
    @Test fun radarStopsWhenSavedNgWordsCannotBeRead() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val failing = flow<NgWordFilter> { throw java.io.IOException("合成の読み込み失敗") }
            val radar = RadarRepository(db.presets(), Lists(), SearchPresetRepository(db), RoomPreferenceRepository(db), backgroundScope, ngWords = failing)
            // 読めない NG ワードを「何も外さない」として巡回を始めない。
            val state = radar.state.first { it.error != null }
            assertFalse(state.loaded)
        } finally { db.close() }
    }
    @Test fun pausingAllPlansDuringFetchSkipsRemainingPlanPagesAndKeepsTheDefinitions() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            searches.save(SearchPreset("a", "合成A", "zenkoku", RoomSearchCriteria()))
            searches.save(SearchPreset("b", "合成B", "talk", RoomSearchCriteria()))
            val lists = Lists()
            val radar = RadarRepository(db.presets(), lists, searches, RoomPreferenceRepository(db), backgroundScope)
            radar.state.first { it.loaded }; radar.setPlan("a", true); radar.setPlan("b", true)
            lists.onFetch = { radar.pauseAllPlans() }
            radar.scan()
            assertEquals(1, lists.calls.size)
            assertTrue(radar.state.value.plans.isEmpty())
            assertEquals(2, searches.presets.first().size)
            assertFalse(radar.state.value.running)
            radar.scan()
            assertEquals(1, lists.calls.size)
        } finally { db.close() }
    }
    @Test fun deletedPresetCannotRemainAnActivePlanOrLeaveVisibleMatches() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            searches.save(SearchPreset("a", "合成", "zenkoku", RoomSearchCriteria()))
            val lists = Lists().apply { rooms = listOf(room) }
            val radar = RadarRepository(db.presets(), lists, searches, RoomPreferenceRepository(db), backgroundScope)
            radar.state.first { it.loaded }; radar.setPlan("a", true); radar.scan()
            searches.delete("a"); radar.scan()
            assertTrue(radar.state.value.plans.isEmpty())
            assertTrue(radar.state.value.results.isEmpty())
            assertEquals(1, lists.calls.size)
        } finally { db.close() }
    }
    @Test fun failedPauseRestoresThePagePositionAndComparisonBaseline() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            var rejectPause = false
            val dao = object : PresetDao by db.presets() {
                override suspend fun put(value: LocalState) {
                    if (rejectPause && org.json.JSONObject(value.value).getJSONArray("plans").length() == 0) throw java.io.IOException("合成の保存失敗")
                    db.presets().put(value)
                }
            }
            val searches = SearchPresetRepository(db)
            searches.save(SearchPreset("a", "合成", "zenkoku", RoomSearchCriteria()))
            val lists = Lists().apply { rooms = listOf(room); lastPage = 2 }
            val radar = RadarRepository(dao, lists, searches, RoomPreferenceRepository(db), backgroundScope)
            radar.state.first { it.loaded }; radar.setPlan("a", true); radar.scan(); radar.scan(); radar.scan()
            rejectPause = true
            try { radar.pauseAllPlans(); fail("保存失敗を通知する") } catch (_: java.io.IOException) { }
            rejectPause = false
            assertEquals(setOf("a"), radar.state.value.plans)
            assertEquals(2, radar.state.value.nextPages["a"])
            lists.rooms = listOf(room, room.copy(id = 99, name = "追加の合成"))
            radar.scan()
            assertEquals(listOf(1, 2, 1, 2), lists.calls.map { it.page })
            assertEquals(1, radar.state.value.events.size)
        } finally { db.close() }
    }
    @Test fun candidateRulesShareRequestsKeepHiddenRoomsOutAndRestoreOnlyTheDefinitions() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            val prefs = RoomPreferenceRepository(db)
            searches.save(SearchPreset("a", "合成の巡回", "zenkoku", RoomSearchCriteria()))
            val lists = Lists().apply { rooms = listOf(room) }
            val job = SupervisorJob(backgroundScope.coroutineContext[Job])
            val radar = RadarRepository(db.presets(), lists, searches, prefs, CoroutineScope(backgroundScope.coroutineContext + job))
            radar.state.first { it.loaded }; radar.setPlan("a", true)
            val rule = CandidateRule("candidate", "合成の候補", "zenkoku", "合成")
            radar.saveCandidate(rule); radar.scan()
            assertEquals(1, lists.calls.size)
            assertEquals(listOf(room), radar.state.value.resultFor(rule)?.rooms)
            assertTrue(radar.state.value.events.isEmpty())
            val additional = room.copy(id = 43)
            lists.rooms = listOf(room, additional); radar.scan()
            val candidateEvent = radar.state.value.events.single { it.text.startsWith("候補") }
            assertEquals(listOf(additional), candidateEvent.rooms)
            val hidden = room.copy(id = 44)
            prefs.setHidden(hidden, true)
            lists.rooms = listOf(room, additional, hidden); radar.scan()
            assertEquals(listOf(room, additional), radar.state.value.resultFor(rule)?.rooms)
            assertEquals(1, radar.state.value.events.count { it.text.startsWith("候補") })
            job.cancelAndJoin()
            val restored = RadarRepository(db.presets(), Lists(), searches, prefs, backgroundScope)
            restored.state.first { it.loaded }
            assertEquals(listOf(rule), restored.state.value.candidateRules)
            assertTrue(restored.state.value.candidateResults.isEmpty())
            assertEquals(candidateEvent, restored.state.value.events.single { it.text.startsWith("候補") })
        } finally { db.close() }
    }
    @Test fun candidateEditsResetPagesAndFirstObservationNeverCreatesAnArrivalEvent() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val lists = Lists().apply { rooms = listOf(room, room.copy(id = 43, name = "別の合成")); lastPage = 2 }
            val radar = RadarRepository(db.presets(), lists, SearchPresetRepository(db), RoomPreferenceRepository(db), backgroundScope)
            radar.state.first { it.loaded }
            val rule = CandidateRule("a", "合成条件", "zenkoku", "合成")
            radar.saveCandidate(rule); radar.scan()
            assertEquals(2, radar.state.value.nextCandidatePages["a"])
            val edited = rule.copy(term = "別の合成")
            radar.saveCandidate(edited)
            assertNull(radar.state.value.resultFor(edited))
            radar.scan()
            assertEquals(listOf(1, 1), lists.calls.map { it.page })
            assertEquals("別の合成", radar.state.value.resultFor(edited)?.rooms?.single()?.name)
            assertTrue(radar.state.value.events.isEmpty())
            radar.pauseAllPlans(); radar.scan()
            assertFalse(radar.state.value.candidateRules.single().enabled)
            assertEquals(2, lists.calls.size)
        } finally { db.close() }
    }
    @Test fun changingTheNameOrGenreOfAnAutoEntryRuleTurnsAutoEntryOff() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val radar = RadarRepository(db.presets(), Lists(), SearchPresetRepository(db), RoomPreferenceRepository(db), backgroundScope)
            radar.state.first { it.loaded }
            val rule = CandidateRule("a", "合成条件", "zenkoku", "合成")
            radar.saveCandidate(rule)
            radar.setCandidateAutoEnter("a", true)
            val saved = radar.state.value.candidateRules.single()
            assertTrue(saved.autoEnterActive(System.currentTimeMillis()))
            // 表示名だけの変更なら続ける。
            radar.saveCandidate(saved.copy(label = "合成の別名"))
            assertTrue(radar.state.value.candidateRules.single().autoEnterUntil > 0)
            // ジャンルを変えたら、許可していない条件で入らないよう止める（名前を変えたときと同じ）。
            radar.saveCandidate(radar.state.value.candidateRules.single().copy(genreKey = "kanto"))
            assertEquals(0L, radar.state.value.candidateRules.single().autoEnterUntil)
            radar.setCandidateAutoEnter("a", true)
            radar.saveCandidate(radar.state.value.candidateRules.single().copy(term = "別の合成"))
            assertEquals(0L, radar.state.value.candidateRules.single().autoEnterUntil)
        } finally { db.close() }
    }
    @Test fun namedAutoEntryOnlyGetsRoomsOpenedAfterTheRuleSawTheList() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val hiddenName = room.copy(status = RoomStatus.FULL, action = RoomAction.NONE, name = null)
            val lists = Lists().apply { rooms = listOf(hiddenName) }
            val arrivals = mutableListOf<Long>()
            val radar = RadarRepository(db.presets(), lists, SearchPresetRepository(db), RoomPreferenceRepository(db), backgroundScope,
                onNamedRoom = { _, found -> arrivals += found.id })
            radar.state.first { it.loaded }
            radar.saveCandidate(CandidateRule("a", "合成条件", "zenkoku", "合成"))
            radar.setCandidateAutoEnter("a", true)
            radar.scan()
            // 満室で隠れていた名前が、空いて出ただけ。前から立っていた部屋なので自動入室には渡さない。
            lists.rooms = listOf(room)
            radar.scan()
            assertEquals(emptyList<Long>(), arrivals)
            // 後から立った（ID の大きい）同じ名前の部屋だけを渡す。
            lists.rooms = listOf(room.copy(id = 43), room)
            radar.scan()
            assertEquals(listOf(43L), arrivals)
        } finally { db.close() }
    }
    @Test fun candidatePlansAtDifferentPagesAdvanceOnlyTheirScheduledRequest() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val lists = Lists().apply { lastPage = 3 }
            val radar = RadarRepository(db.presets(), lists, SearchPresetRepository(db), RoomPreferenceRepository(db), backgroundScope)
            radar.state.first { it.loaded }
            radar.saveCandidate(CandidateRule("a", "合成A", "zenkoku", "合成")); radar.scan()
            radar.saveCandidate(CandidateRule("b", "合成B", "zenkoku", "合成")); radar.scan()
            assertEquals(3, radar.state.value.nextCandidatePages["a"])
            assertEquals(2, radar.state.value.nextCandidatePages["b"])
            assertEquals(2, radar.state.value.candidateResults["a"]?.page)
            assertEquals(1, radar.state.value.candidateResults["b"]?.page)
            radar.scan()
            assertEquals(listOf(1, 2, 1, 3, 2), lists.calls.map { it.page })
            assertEquals(3, radar.state.value.candidateResults["a"]?.page)
            assertEquals(2, radar.state.value.candidateResults["b"]?.page)
        } finally { db.close() }
    }
    @Test fun candidateOnlyPagesDoNotReplaceASearchPlansAssignedPage() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            searches.save(SearchPreset("plan", "合成計画", "zenkoku", RoomSearchCriteria()))
            val lists = Lists().apply { rooms = listOf(room); lastPage = 2 }
            val radar = RadarRepository(db.presets(), lists, searches, RoomPreferenceRepository(db), backgroundScope)
            radar.state.first { it.loaded }; radar.setPlan("plan", true); radar.scan()
            radar.saveCandidate(CandidateRule("candidate", "合成候補", "zenkoku", "合成")); radar.scan()
            assertEquals(listOf(1, 2, 1), lists.calls.map { it.page })
            assertEquals(2, radar.state.value.results["plan"]?.page)
            assertEquals(1, radar.state.value.candidateResults["candidate"]?.page)
        } finally { db.close() }
    }
    @Test fun offScheduleCandidatesAreNotRecordedEarlyOrSuppressedOnTheirActualPage() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val lists = Lists().apply { rooms = listOf(room); lastPage = 2 }
            val radar = RadarRepository(db.presets(), lists, SearchPresetRepository(db), RoomPreferenceRepository(db), backgroundScope)
            radar.state.first { it.loaded }
            val a = CandidateRule("a", "合成A", "zenkoku", "合成")
            radar.saveCandidate(a); radar.scan()
            radar.saveCandidate(a.copy(id = "b", label = "合成B"))
            val arrival = room.copy(id = 44)
            lists.roomsByPage = mapOf(1 to listOf(room, arrival), 2 to listOf(room))
            radar.scan()
            assertTrue(radar.state.value.events.isEmpty())
            assertEquals(listOf(room), radar.state.value.resultFor(a)?.rooms)
            radar.scan()
            val event = radar.state.value.events.single()
            assertTrue(event.text.contains("合成A"))
            assertEquals(listOf(arrival), event.rooms)
        } finally { db.close() }
    }
    @Test fun divergedPlansAdvanceOnlyOncePerScheduledPage() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            searches.save(SearchPreset("old", "条件Z", "zenkoku", RoomSearchCriteria()))
            val lists = Lists().apply { lastPage = 3 }
            val radar = RadarRepository(db.presets(), lists, searches, RoomPreferenceRepository(db), backgroundScope)
            radar.state.first { it.loaded }
            radar.setPlan("old", true)
            radar.scan() // 古い計画は次に2ページ目。
            searches.save(SearchPreset("new", "条件A", "zenkoku", RoomSearchCriteria()))
            radar.setPlan("new", true)
            radar.scan() // 新計画1ページ目、古い計画2ページ目。
            radar.scan() // 新計画は2、古い計画は3。新計画が2ページ目を飛ばさない。
            assertEquals(listOf(1, 1, 2, 2, 3), lists.calls.map { it.page })
        } finally { db.close() }
    }
    @Test fun editedConditionStartsAtFirstPageAndCreatesANewBaseline() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            val original = SearchPreset("a", "条件A", "zenkoku", RoomSearchCriteria())
            searches.save(original)
            val lists = Lists().apply { lastPage = 2; rooms = listOf(room) }
            val radar = RadarRepository(db.presets(), lists, searches, RoomPreferenceRepository(db), backgroundScope)
            radar.state.first { it.loaded }
            radar.setPlan("a", true)
            radar.scan()
            searches.save(original.copy(criteria = RoomSearchCriteria(text = "本文")))
            radar.scan()
            assertEquals(listOf(1, 1), lists.calls.map { it.page })
            assertTrue(radar.state.value.events.isEmpty())
        } finally { db.close() }
    }
    @Test fun sharedPlansShareOnePageAndPersist() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            val prefs = RoomPreferenceRepository(db)
            searches.save(SearchPreset("a", "条件A", "zenkoku", RoomSearchCriteria()))
            searches.save(SearchPreset("b", "条件B", "zenkoku", RoomSearchCriteria()))
            val lists = Lists()
            val radar = RadarRepository(db.presets(), lists, searches, prefs, backgroundScope)
            radar.state.first { it.loaded }
            radar.setPlan("a", true); radar.setPlan("b", true)
            lists.rooms = listOf(room)
            radar.scan()
            assertEquals(1, lists.calls.size)
            assertTrue(radar.state.value.events.isEmpty())
            val restored = RadarRepository(db.presets(), lists, searches, prefs, backgroundScope)
            restored.state.first { it.loaded }
            assertEquals(setOf("a", "b"), restored.state.value.plans)
        } finally { db.close() }
    }
    @Test fun pageLimitDefersTheRestAndAConcurrentScanDoesNotFetchTwice() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            searches.save(SearchPreset("a", "合成A", "zenkoku", RoomSearchCriteria()))
            searches.save(SearchPreset("b", "合成B", "talk", RoomSearchCriteria()))
            val lists = Lists()
            val radar = RadarRepository(db.presets(), lists, searches, RoomPreferenceRepository(db), backgroundScope)
            radar.state.first { it.loaded }; radar.setPlan("a", true); radar.setPlan("b", true)
            // 背景実行と同じ上限付きの巡回。上限を超えたページは取得せず次回に回す。
            radar.scan(latestFirst = true, force = false, maxPages = 1)
            assertEquals(1, lists.calls.size)
            val pages = radar.state.value.lastScan!!.pages
            assertEquals(listOf(RadarCheckStatus.CONFIRMED, RadarCheckStatus.SKIPPED), pages.map { it.status })
            // 取得中に別の巡回（前面と背景など）が始まっても、同じページを二重に取得しない。
            lists.onFetch = { radar.scan(latestFirst = true) }
            radar.scan(latestFirst = true)
            assertEquals(3, lists.calls.size)
        } finally { db.close() }
    }
    @Test fun backgroundScanChecksOnlyBackgroundPlansAndLeavesCandidatesToTheForeground() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            searches.save(SearchPreset("bg", "合成の背景", "zenkoku", RoomSearchCriteria()))
            searches.save(SearchPreset("fg", "合成の前面", "talk", RoomSearchCriteria()))
            val lists = Lists()
            val radar = RadarRepository(db.presets(), lists, searches, RoomPreferenceRepository(db), backgroundScope)
            radar.state.first { it.loaded }
            radar.setPlanBackground("bg", true); radar.setPlan("fg", true)
            radar.saveCandidate(CandidateRule("c", "合成の候補", "game", "合成"))
            assertEquals(setOf("bg", "fg"), radar.state.value.plans)
            assertEquals(setOf("bg"), radar.state.value.activeBackgroundPlans)
            radar.scan(latestFirst = true, force = false, backgroundOnly = true)
            assertEquals(listOf(RoomQuery(Genres["zenkoku"]!!, page = 1)), lists.calls)
        } finally { db.close() }
    }
    @Test fun backgroundBaselineSurvivesARestartSoOnlyNewMatchesAreReported() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            val prefs = RoomPreferenceRepository(db)
            searches.save(SearchPreset("bg", "合成の背景", "zenkoku", RoomSearchCriteria()))
            var clock = 1_000_000L
            // 前の起動は止めてから次を作る（背景の実行ごとにプロセスが作り直される想定）。
            var running = SupervisorJob(backgroundScope.coroutineContext[Job])
            suspend fun restart(): CoroutineScope { running.cancelAndJoin(); running = SupervisorJob(backgroundScope.coroutineContext[Job]); return CoroutineScope(backgroundScope.coroutineContext + running) }
            val first = RadarRepository(db.presets(), Lists().apply { rooms = listOf(room) }, searches, prefs, CoroutineScope(backgroundScope.coroutineContext + running)) { clock }
            first.state.first { it.loaded }; first.setPlanBackground("bg", true)
            // 初回は比較基準を作るだけで、一致を「新しい」とは扱わない。
            assertTrue(first.scan(latestFirst = true, force = false, backgroundOnly = true).isEmpty())
            // 背景の実行ごとにプロセスが作り直されても、保存した基準と比べる。
            clock += 15 * 60 * 1000L
            val added = room.copy(id = 43, name = "追加の合成")
            val second = RadarRepository(db.presets(), Lists().apply { rooms = listOf(room, added) }, searches, prefs, restart()) { clock }
            second.state.first { it.loaded }
            val events = second.scan(latestFirst = true, force = false, backgroundOnly = true)
            assertEquals(listOf(RadarEventKind.SEARCH_MATCH), events.map { it.kind })
            assertEquals(listOf(43L), events.single().rooms.map { it.id })
            // 通知を出す前に止まっても、未通知の一致として保存されている。
            assertTrue(events.single().pendingNotice)
            assertTrue(events.single().alert)
            // 一度知らせた部屋は、次の再起動後にも新しい一致として出さない。
            clock += 15 * 60 * 1000L
            val third = RadarRepository(db.presets(), Lists().apply { rooms = listOf(added) }, searches, prefs, restart()) { clock }
            third.state.first { it.loaded }
            assertEquals(listOf(events.single().id), third.pendingNotices().map { it.id })
            third.markNoticed(setOf(events.single().id))
            assertTrue(third.pendingNotices().isEmpty())
            assertFalse(db.presets().state("radar_v1")!!.contains("\"pendingNotice\":true"))
            assertTrue(third.scan(latestFirst = true, force = false, backgroundOnly = true).isEmpty())
        } finally { db.close() }
    }
    @Test fun foregroundMatchesGoToTheBellAndOnlyNotifyingConditionsAlert() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            searches.save(SearchPreset("quiet", "合成の巡回だけ", "zenkoku", RoomSearchCriteria()))
            searches.save(SearchPreset("loud", "合成の知らせる", "zenkoku", RoomSearchCriteria()))
            val lists = Lists().apply { rooms = listOf(room) }
            val radar = RadarRepository(db.presets(), lists, searches, RoomPreferenceRepository(db), backgroundScope)
            radar.state.first { it.loaded }
            radar.setPlan("quiet", true); radar.setPlanBackground("loud", true)
            val loud = CandidateRule("named", "合成の名前", "zenkoku", "追加の合成")
            val silent = CandidateRule("silent", "合成の知らせない名前", "zenkoku", "追加の合成", notify = false)
            radar.saveCandidate(loud); radar.saveCandidate(silent)
            radar.scan()
            lists.rooms = listOf(room, room.copy(id = 43, name = "追加の合成")); radar.scan()
            // 前面の巡回で見つけた一致も、すべてお知らせに出す。端末通知は知らせる条件・名前だけ。
            val pending = radar.pendingNotices().associateBy { it.origin!!.id }
            assertEquals(setOf("quiet", "loud", "named", "silent"), pending.keys)
            assertFalse(pending.getValue("quiet").alert)
            assertTrue(pending.getValue("loud").alert)
            assertTrue(pending.getValue("named").alert)
            assertFalse(pending.getValue("silent").alert)
            radar.markOriginRead(pending.getValue("loud").origin!!.key, pending.getValue("loud").at)
            assertTrue(radar.state.value.events.single { it.origin?.id == "loud" }.read)
            assertFalse(radar.state.value.events.single { it.origin?.id == "quiet" }.read)
        } finally { db.close() }
    }
    @Test fun candidateNotifySettingSurvivesARestartAndOldRulesNotify() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            db.presets().put(LocalState("radar_v1", """{"plans":[],"targets":[],"events":[],"candidateRules":[{"id":"old","label":"合成","genre":"zenkoku","term":"合成","mode":"EXACT_NAME","enabled":true}]}"""))
            val searches = SearchPresetRepository(db)
            val radar = RadarRepository(db.presets(), Lists(), searches, RoomPreferenceRepository(db), backgroundScope)
            radar.state.first { it.loaded }
            assertTrue(radar.state.value.candidateRules.single().notify)
            radar.setCandidateNotify("old", false)
            val restored = RadarRepository(db.presets(), Lists(), searches, RoomPreferenceRepository(db), backgroundScope)
            restored.state.first { it.loaded }
            assertFalse(restored.state.value.candidateRules.single().notify)
        } finally { db.close() }
    }
    @Test fun anOldSavedBaselineIsRebuiltInsteadOfReportingEverythingAsNew() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            val prefs = RoomPreferenceRepository(db)
            searches.save(SearchPreset("bg", "合成の背景", "zenkoku", RoomSearchCriteria()))
            var clock = 1_000_000L
            // 前の起動は止めてから次を作る（背景の実行ごとにプロセスが作り直される想定）。
            var running = SupervisorJob(backgroundScope.coroutineContext[Job])
            suspend fun restart(): CoroutineScope { running.cancelAndJoin(); running = SupervisorJob(backgroundScope.coroutineContext[Job]); return CoroutineScope(backgroundScope.coroutineContext + running) }
            val first = RadarRepository(db.presets(), Lists().apply { rooms = listOf(room) }, searches, prefs, CoroutineScope(backgroundScope.coroutineContext + running)) { clock }
            first.state.first { it.loaded }; first.setPlanBackground("bg", true)
            first.scan(latestFirst = true, force = false, backgroundOnly = true)
            clock += 7 * 60 * 60 * 1000L
            val later = RadarRepository(db.presets(), Lists().apply { rooms = listOf(room, room.copy(id = 43, name = "追加の合成")) }, searches, prefs, restart()) { clock }
            later.state.first { it.loaded }
            assertTrue(later.scan(latestFirst = true, force = false, backgroundOnly = true).isEmpty())
        } finally { db.close() }
    }
    @Test fun backgroundSettingsPersistAndStoppingPlansStopsTheBackgroundToo() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            searches.save(SearchPreset("a", "合成A", "zenkoku", RoomSearchCriteria()))
            searches.save(SearchPreset("b", "合成B", "talk", RoomSearchCriteria()))
            val radar = RadarRepository(db.presets(), Lists(), searches, RoomPreferenceRepository(db), backgroundScope)
            radar.state.first { it.loaded }
            radar.setPlanBackground("a", true); radar.setPlanBackground("b", true); radar.setBackgroundInterval(30)
            val restored = RadarRepository(db.presets(), Lists(), searches, RoomPreferenceRepository(db), backgroundScope)
            restored.state.first { it.loaded }
            assertEquals(setOf("a", "b"), restored.state.value.activeBackgroundPlans)
            assertEquals(30, restored.state.value.backgroundIntervalMinutes)
            assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { restored.setBackgroundInterval(5) } }
            restored.setPlan("b", false)
            assertEquals(setOf("a"), restored.state.value.activeBackgroundPlans)
            restored.pauseAllPlans()
            assertTrue(restored.state.value.activeBackgroundPlans.isEmpty())
            assertTrue(restored.state.value.backgroundPlans.isEmpty())
        } finally { db.close() }
    }
    @Test fun savingOtherStateDoesNotKeepAnUnrefreshedBaselineAlive() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            val prefs = RoomPreferenceRepository(db)
            searches.save(SearchPreset("bg", "合成の背景", "zenkoku", RoomSearchCriteria()))
            var clock = 1_000_000L
            var running = SupervisorJob(backgroundScope.coroutineContext[Job])
            suspend fun restart(): CoroutineScope { running.cancelAndJoin(); running = SupervisorJob(backgroundScope.coroutineContext[Job]); return CoroutineScope(backgroundScope.coroutineContext + running) }
            val first = RadarRepository(db.presets(), Lists().apply { rooms = listOf(room) }, searches, prefs, CoroutineScope(backgroundScope.coroutineContext + running)) { clock }
            first.state.first { it.loaded }; first.setPlanBackground("bg", true)
            first.scan(latestFirst = true, force = false, backgroundOnly = true)
            // 取得に失敗し続ける間も、起動や設定の保存は起きる。それで基準の時刻を新しくしない。
            clock += 5 * 60 * 60 * 1000L
            val failing = RadarRepository(db.presets(), Lists(), searches, prefs, restart()) { clock }
            failing.state.first { it.loaded }; failing.setBackgroundInterval(30)
            clock += 2 * 60 * 60 * 1000L
            val recovered = RadarRepository(db.presets(), Lists().apply { rooms = listOf(room, room.copy(id = 43, name = "追加の合成")) }, searches, prefs, restart()) { clock }
            recovered.state.first { it.loaded }
            assertTrue(recovered.scan(latestFirst = true, force = false, backgroundOnly = true).isEmpty())
        } finally { db.close() }
    }
    @Test fun plansDeferredByThePageLimitComeFirstInTheNextProcess() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            val prefs = RoomPreferenceRepository(db)
            val genres = listOf("zenkoku", "talk", "game", "cosplay")
            genres.forEach { searches.save(SearchPreset("p-$it", "合成 $it", it, RoomSearchCriteria())) }
            var clock = 1_000_000L
            var running = SupervisorJob(backgroundScope.coroutineContext[Job])
            suspend fun restart(): CoroutineScope { running.cancelAndJoin(); running = SupervisorJob(backgroundScope.coroutineContext[Job]); return CoroutineScope(backgroundScope.coroutineContext + running) }
            val firstLists = Lists()
            val first = RadarRepository(db.presets(), firstLists, searches, prefs, CoroutineScope(backgroundScope.coroutineContext + running)) { clock }
            first.state.first { it.loaded }
            genres.forEach { first.setPlanBackground("p-$it", true) }
            first.scan(latestFirst = true, force = false, maxPages = 3, backgroundOnly = true)
            val skipped = genres.single { genre -> firstLists.calls.none { it.genre.key == genre } }
            clock += 15 * 60 * 1000L
            val nextLists = Lists()
            val next = RadarRepository(db.presets(), nextLists, searches, prefs, restart()) { clock }
            next.state.first { it.loaded }
            next.scan(latestFirst = true, force = false, maxPages = 3, backgroundOnly = true)
            assertEquals(skipped, nextLists.calls.first().genre.key)
        } finally { db.close() }
    }
    @Test fun plansThatKeepFailingDoNotBlockTheRestInLaterProcesses() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            val prefs = RoomPreferenceRepository(db)
            val genres = listOf("zenkoku", "talk", "game", "cosplay")
            genres.forEach { searches.save(SearchPreset("p-$it", "合成 $it", it, RoomSearchCriteria())) }
            var clock = 1_000_000L
            var running = SupervisorJob(backgroundScope.coroutineContext[Job])
            suspend fun restart(): CoroutineScope { running.cancelAndJoin(); running = SupervisorJob(backgroundScope.coroutineContext[Job]); return CoroutineScope(backgroundScope.coroutineContext + running) }
            fun failing() = Lists().apply { onFetch = { throw java.io.IOException("合成の通信失敗") } }
            val firstLists = failing()
            val first = RadarRepository(db.presets(), firstLists, searches, prefs, CoroutineScope(backgroundScope.coroutineContext + running)) { clock }
            first.state.first { it.loaded }
            genres.forEach { first.setPlanBackground("p-$it", true) }
            first.scan(latestFirst = true, force = false, maxPages = 3, backgroundOnly = true)
            val untried = genres.single { genre -> firstLists.calls.none { it.genre.key == genre } }
            clock += 15 * 60 * 1000L
            val nextLists = failing()
            val next = RadarRepository(db.presets(), nextLists, searches, prefs, restart()) { clock }
            next.state.first { it.loaded }
            next.scan(latestFirst = true, force = false, maxPages = 3, backgroundOnly = true)
            assertEquals(untried, nextLists.calls.first().genre.key)
        } finally { db.close() }
    }
    @Test fun aPageWithoutTheRoomTableIsCheckedAgainLaterWithoutAnError() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            searches.save(SearchPreset("a", "合成条件", "zenkoku", RoomSearchCriteria()))
            val lists = Lists().apply { onFetch = { throw RoomListUnavailableException("合成") } }
            val radar = RadarRepository(db.presets(), lists, searches, RoomPreferenceRepository(db), backgroundScope) { 1_000_000 }
            radar.state.first { it.loaded }
            radar.setPlan("a", true)
            radar.scan(latestFirst = true)
            // 本家が部屋の表を出さなかっただけなので、失敗の案内は出さずに次の巡回で確認する。
            assertNull(radar.state.value.error)
            assertEquals(listOf(RadarCheckStatus.SKIPPED), radar.state.value.lastScan?.pages?.map { it.status })
        } finally { db.close() }
    }
    @Test fun enablingAPlanShowsMatchesFromPagesOtherFeaturesJustReadAndUsesThemAsTheBaseline() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            searches.save(SearchPreset("a", "合成条件", "zenkoku", RoomSearchCriteria()))
            val later = room.copy(id = 43, name = "後ろの合成")
            val lists = Lists().apply { clock = 1_000_000; lastPage = 2; roomsByPage = mapOf(1 to listOf(room), 2 to listOf(later)) }
            val radar = RadarRepository(db.presets(), lists, searches, RoomPreferenceRepository(db), backgroundScope) { 1_000_000 }
            radar.state.first { it.loaded }
            // 見つける画面が全ページを読んだ直後に計画を有効にすると、読み直さずに全ページの一致を出す。
            val zenkoku = Genres["zenkoku"]!!
            lists.fetch(RoomQuery(zenkoku), false); lists.fetch(RoomQuery(zenkoku, page = 2), false)
            radar.setPlan("a", true)
            assertEquals(listOf(42L, 43L), radar.state.value.results["a"]?.rooms?.map { it.id })
            assertEquals(2, radar.state.value.results["a"]?.pagesRead)
            assertEquals(2, lists.calls.size)
            // 部屋の詳細の確認は、その部屋が載っていたページを取り直す。
            assertEquals(1, radar.state.value.results["a"]?.pageOf(room))
            assertEquals(2, radar.state.value.results["a"]?.pageOf(later))
            // 共有の一覧を比較基準にするので、次に読んだページで増えた一致だけを知らせる。
            val arrival = room.copy(id = 44, name = "新しい合成")
            lists.roomsByPage = mapOf(1 to listOf(room, arrival), 2 to listOf(later))
            radar.scan()
            assertEquals(listOf(44L), radar.state.value.events.single().rooms.map { it.id })
            assertEquals(setOf(42L, 43L, 44L), radar.state.value.results["a"]?.rooms?.map { it.id }?.toSet())
            assertEquals(2, radar.state.value.results["a"]?.pageOf(later))
        } finally { db.close() }
    }
    @Test fun candidateMatchesComeFromEveryPageAndAppearAsSoonAsTheRuleIsAdded() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val later = room.copy(id = 43, name = "合成の後ろ")
            val lists = Lists().apply { clock = 1_000_000; lastPage = 2; roomsByPage = mapOf(1 to listOf(room), 2 to listOf(later)) }
            val radar = RadarRepository(db.presets(), lists, SearchPresetRepository(db), RoomPreferenceRepository(db), backgroundScope) { 1_000_000 }
            radar.state.first { it.loaded }
            // 見つける画面が全ページを読んだ直後に候補条件を足すと、読み直さずに全ページの一致を出す。
            val zenkoku = Genres["zenkoku"]!!
            lists.fetch(RoomQuery(zenkoku), false); lists.fetch(RoomQuery(zenkoku, page = 2), false)
            val rule = CandidateRule("c", "合成の候補", "zenkoku", "合成", CandidateMode.DISPLAY_TEXT)
            radar.saveCandidate(rule)
            val seeded = radar.state.value.resultFor(rule)
            assertEquals(listOf(42L, 43L), seeded?.rooms?.map { it.id })
            assertEquals(2, seeded?.pagesRead)
            assertEquals(2, seeded?.pageOf(later))
            assertEquals(2, lists.calls.size)
            // 共有の一覧を比較基準にするので、次に読んだページで増えた一致だけを知らせる。
            val arrival = room.copy(id = 44, name = "新しい合成")
            lists.roomsByPage = mapOf(1 to listOf(room, arrival), 2 to listOf(later))
            radar.scan()
            assertEquals(listOf(44L), radar.state.value.events.single().rooms.map { it.id })
            assertEquals(setOf(42L, 43L, 44L), radar.state.value.resultFor(rule)?.rooms?.map { it.id }?.toSet())
        } finally { db.close() }
    }
    @Test fun checkingForNewRoomsCanBePressedDuringAScanAndFoldsExtraPressesIntoOne() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            searches.save(SearchPreset("a", "合成条件", "zenkoku", RoomSearchCriteria()))
            val releases = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val entered = kotlinx.coroutines.channels.Channel<RoomQuery>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            var manual = 0
            val base = Lists().apply { rooms = listOf(room) }
            val lists = object : ObservedRoomListSource {
                override val observations get() = base.observations
                override fun observation(query: RoomQuery) = base.observation(query)
                override suspend fun fetch(query: RoomQuery, force: Boolean): RoomListPage {
                    entered.send(query); releases.receive(); return base.fetch(query, force)
                }
                override suspend fun fetchNow(query: RoomQuery): RoomListPage { manual++; return fetch(query, true) }
            }
            val radar = RadarRepository(db.presets(), lists, searches, RoomPreferenceRepository(db), backgroundScope)
            radar.state.first { it.loaded }; radar.setPlan("a", true)
            // 自動の巡回の途中で何度押しても、巡回が終わった後の 1 回にまとめて 1 ページ目を取り直す。
            val automatic = launch { radar.scan(force = false) }
            entered.receive()
            val presses = List(3) { launch { radar.scanNow() } }
            radar.state.first { it.scanNowPending }
            releases.send(Unit); automatic.join()
            entered.receive()
            assertEquals(1, manual)
            // 取り直し中に押された分は、終わった後にもう 1 回取る。
            val again = launch { radar.scanNow() }
            testScheduler.runCurrent()
            releases.send(Unit)
            entered.receive()
            releases.send(Unit)
            (presses + again).joinAll()
            assertEquals(2, manual)
            assertEquals(3, base.calls.size)
            assertFalse(radar.state.value.scanNowPending)
            assertFalse(radar.state.value.running)
        } finally { db.close() }
    }
    @Test fun sharedPagesOlderThanTheLimitAreNotUsedForANewPlan() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            searches.save(SearchPreset("a", "合成条件", "zenkoku", RoomSearchCriteria()))
            val lists = Lists().apply { clock = 1_000_000; rooms = listOf(room) }
            val radar = RadarRepository(db.presets(), lists, searches, RoomPreferenceRepository(db), backgroundScope) { 1_000_000 + 5 * 60_000L + 1 }
            radar.state.first { it.loaded }
            lists.fetch(RoomQuery(Genres["zenkoku"]!!), false)
            radar.setPlan("a", true)
            assertNull(radar.state.value.results["a"])
        } finally { db.close() }
    }
}
