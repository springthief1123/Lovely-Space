package io.github.springthief1123.lovelyspace.data

import android.app.Application
import androidx.room.Room as RoomDb
import androidx.test.core.app.ApplicationProvider
import io.github.springthief1123.lovelyspace.core.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
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
    @Test fun trackingKeepsTheObservedPageAndFiltersAcrossRestartAndScan() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            val query = RoomQuery(Genres[room.genreKey]!!, sex = Gender.FEMALE, prefecture = 13,
                ageBand = "20-29", publicOnly = false, waitingOnly = true, name = "合成", message = "本文", page = 3)
            val lists = Lists().apply { rooms = listOf(room); lastPage = 3 }
            lists.fetch(query, force = true)
            val job = SupervisorJob(backgroundScope.coroutineContext[Job])
            val radar = RadarRepository(db.presets(), lists, searches, RoomPreferenceRepository(db), CoroutineScope(backgroundScope.coroutineContext + job))
            radar.state.first { it.loaded }; radar.track(room)
            assertEquals(query, radar.state.value.targets.single().sourceQuery)
            assertEquals(3, radar.state.value.targets.single().observedPage)
            job.cancelAndJoin()
            val fresh = Lists().apply { rooms = listOf(room); lastPage = 3 }
            val restored = RadarRepository(db.presets(), fresh, searches, RoomPreferenceRepository(db), backgroundScope)
            restored.state.first { it.loaded }
            assertEquals(query, restored.state.value.targets.single().sourceQuery)
            restored.scan()
            assertEquals(listOf(query), fresh.calls)
            fresh.rooms = listOf(room.copy(status = RoomStatus.PUBLIC_WAITING, action = RoomAction.PEEK))
            restored.scan()
            assertEquals(query, restored.state.value.events.single().sourceQuery)
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
    @Test fun clockRollbackAndRestoredFutureTimestampsDoNotBlockNewObservations() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            val prefs = RoomPreferenceRepository(db)
            val lists = Lists().apply { rooms = listOf(room); clock = 10_000 }
            val firstJob = SupervisorJob(backgroundScope.coroutineContext[Job])
            val radar = RadarRepository(db.presets(), lists, searches, prefs, CoroutineScope(backgroundScope.coroutineContext + firstJob))
            radar.state.first { it.loaded }
            radar.track(room)
            radar.scan()
            assertEquals(10_000L, radar.state.value.targets.single().observedAt)

            lists.clock = 1_000 // 時計を戻しても、新しい取得結果は反映する。
            lists.rooms = listOf(room.copy(status = RoomStatus.PUBLIC_WAITING))
            radar.scan()
            assertEquals(RoomStatus.PUBLIC_WAITING, radar.state.value.targets.single().room.status)
            assertEquals(1_000L, radar.state.value.targets.single().observedAt)
            assertEquals(2L, radar.state.value.targets.single().observationRevision)
            assertEquals(1, radar.state.value.events.size)

            firstJob.cancelAndJoin() // 保存済み時刻の書き換え中に旧監視が保存しないよう終了する。
            val json = org.json.JSONObject(db.presets().state("radar_v1")!!)
            json.getJSONArray("targets").getJSONObject(0).put("observedAt", 50_000L)
            db.presets().put(LocalState("radar_v1", json.toString()))
            // 再起動で取得順序が1に戻り、保存時刻が未来でもID再利用の検出を継続する。
            val freshLists = Lists().apply { rooms = listOf(room.copy(name = "別の合成")); clock = 500 }
            val restored = RadarRepository(db.presets(), freshLists, searches, prefs, backgroundScope)
            restored.state.first { it.loaded }
            assertEquals(50_000L, restored.state.value.targets.single().observedAt)
            assertEquals(0L, restored.state.value.targets.single().observationRevision)
            restored.scan()
            assertEquals(RoomIdentityEvidence.REUSED, restored.state.value.targets.single().evidence)
            assertEquals(500L, restored.state.value.targets.single().observedAt)
            assertTrue(restored.state.value.events.any { "追跡を停止" in it.text })
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
            radar.state.first { it.loaded }; radar.setPlan("a", true); radar.track(room)
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
    @Test fun sharedPlansBaselineDedupPartialPageIdentityReuseAndPersistence() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            val prefs = RoomPreferenceRepository(db)
            searches.save(SearchPreset("a", "条件A", "zenkoku", RoomSearchCriteria()))
            searches.save(SearchPreset("b", "条件B", "zenkoku", RoomSearchCriteria()))
            val lists = Lists()
            val radar = RadarRepository(db.presets(), lists, searches, prefs, backgroundScope)
            radar.state.first { it.loaded }
            radar.setPlan("a", true); radar.setPlan("b", true); radar.track(room)
            lists.rooms = listOf(room)
            radar.scan()
            assertEquals(1, lists.calls.size)
            assertTrue(radar.state.value.events.isEmpty())
            assertNotNull(radar.state.value.targets.single().confirmedAt)
            val confirmedAt = radar.state.value.targets.single().confirmedAt
            lists.rooms = emptyList(); radar.scan()
            assertEquals(confirmedAt, radar.state.value.targets.single().confirmedAt)
            assertEquals(RoomIdentityEvidence.MATCH, radar.state.value.targets.single().evidence)
            lists.rooms = listOf(room.copy(name = null, status = RoomStatus.FULL)); radar.scan()
            assertEquals(RoomIdentityEvidence.AMBIGUOUS, radar.state.value.targets.single().evidence)
            assertEquals(confirmedAt, radar.state.value.targets.single().confirmedAt)
            assertTrue(radar.state.value.targets.single().observedAt!! > confirmedAt!!)
            lists.rooms = listOf(room.copy(name = "別の合成")); radar.scan()
            assertEquals(RoomIdentityEvidence.REUSED, radar.state.value.targets.single().evidence)
            assertTrue(radar.state.value.events.any { "追跡を停止" in it.text })
            val persisted = db.presets().state("radar_v1")!!
            assertTrue(persisted.contains("REUSED"))
            assertTrue(persisted.contains("条件"))
            val restored = RadarRepository(db.presets(), lists, searches, prefs, backgroundScope)
            restored.state.first { it.loaded }
            assertEquals(setOf("a", "b"), restored.state.value.plans)
            assertEquals(RoomIdentityEvidence.REUSED, restored.state.value.targets.single().evidence)
            assertEquals(room.name, restored.state.value.targets.single().identity.name)
            assertEquals(radar.state.value.targets.single().observedAt, restored.state.value.targets.single().observedAt)
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
    @Test fun backgroundScanChecksOnlyBackgroundPlansAndLeavesCandidatesAndTargetsToTheForeground() = runTest {
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
            radar.track(room.copy(genreKey = "cosplay"), RoomQuery(Genres["cosplay"]!!, page = 2))
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
}
