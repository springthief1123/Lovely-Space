package io.github.springthief1123.lovelyspace.data

import android.app.Application
import androidx.room.Room as RoomDb
import androidx.test.core.app.ApplicationProvider
import io.github.springthief1123.lovelyspace.core.*
import kotlinx.coroutines.flow.*
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
        var revision = 0L
        var lastPage = 1
        var clock: Long? = null
        var onFetch: (suspend (RoomQuery) -> Unit)? = null
        val calls = mutableListOf<RoomQuery>()
        override fun observation(query: RoomQuery) = observations.value[query]
        override suspend fun fetch(query: RoomQuery, force: Boolean): RoomListPage {
            calls += query
            onFetch?.invoke(query)
            val page = RoomListPage(query.genre.key, rooms, null, null, query.page, lastPage, emptyMap(), null)
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
}
