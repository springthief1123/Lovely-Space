package io.github.springthief1123.lovelyspace.data

import android.app.Application
import androidx.room.Room as RoomDb
import androidx.test.core.app.ApplicationProvider
import io.github.springthief1123.lovelyspace.core.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RadarDashboardTest {
    private class Lists : ObservedRoomListSource {
        override val observations = MutableStateFlow<Map<RoomQuery, ObservedRoomPage>>(emptyMap())
        var revision = 0L
        var fresh = true
        var extra = false
        var failGenre: String? = null
        var onFetch: (suspend () -> Unit)? = null
        val calls = mutableListOf<RoomQuery>()
        override fun observation(query: RoomQuery) = observations.value[query]
        override suspend fun fetch(query: RoomQuery, force: Boolean): RoomListPage {
            calls += query
            onFetch?.invoke()
            if (query.genre.key == failGenre) error("合成の取得失敗")
            val room = Room(42, query.genre.key, RoomStatus.WAITING, RoomAction.ENTER, null, "合成", Gender.FEMALE, 25, null, "本文")
            val page = RoomListPage(query.genre.key, if (extra) listOf(room, room.copy(id = 43)) else listOf(room), null, null, query.page, 2, emptyMap(), null)
            if (fresh) { revision++; observations.value += query to ObservedRoomPage(query, page, revision * 1000, revision) }
            return page
        }
    }
    private suspend fun TestScope.withRadar(block: suspend (RadarRepository, SearchPresetRepository, Lists) -> Unit) {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val searches = SearchPresetRepository(db)
            val lists = Lists()
            val radar = RadarRepository(db.presets(), lists, searches, RoomPreferenceRepository(db), backgroundScope)
            radar.state.first { it.loaded }
            block(radar, searches, lists)
        } finally { db.close() }
    }
    @Test fun sharedPagesHaveOneProgressItemAndDoNotDoubleCountMatches() = runTest {
        withRadar { radar, searches, lists ->
            searches.save(SearchPreset("a", "合成A", "zenkoku", RoomSearchCriteria()))
            searches.save(SearchPreset("b", "合成B", "zenkoku", RoomSearchCriteria()))
            radar.setPlan("a", true); radar.setPlan("b", true)
            radar.saveCandidate(CandidateRule(id = "c", label = "合成候補", genreKey = "zenkoku", term = "合成"))
            radar.scan()
            val report = radar.state.value.lastScan!!
            assertEquals(1, lists.calls.size); assertEquals(1, report.pages.size)
            assertEquals(1, report.confirmed); assertEquals(1, report.matches)
            assertEquals(0, report.failed); assertEquals(0, report.newEvents)
            assertNotNull(report.finishedAt); assertEquals(1000L, radar.state.value.lastConfirmedAt)
        }
    }
    @Test fun trackingOnlyScanCountsMatchedTarget() = runTest {
        withRadar { radar, _, _ ->
            val query = RoomQuery(Genres["zenkoku"]!!, page = 1)
            val room = Room(42, "zenkoku", RoomStatus.WAITING, RoomAction.ENTER, null, "合成", Gender.FEMALE, 25, null, "本文")
            radar.track(room, query)
            radar.scan()
            assertEquals(1, radar.state.value.lastScan!!.confirmed)
            assertEquals(1, radar.state.value.lastScan!!.matches)
        }
    }
    @Test fun sharedListFetchUpdatesLastConfirmedAt() = runTest {
        withRadar { radar, _, lists ->
            val query = RoomQuery(Genres["zenkoku"]!!, page = 1)
            lists.fetch(query, force = true)
            assertEquals(1000L, radar.state.first { it.lastConfirmedAt != null }.lastConfirmedAt)
        }
    }
    @Test fun failedPagesDoNotAdvanceAndOtherPagesStillComplete() = runTest {
        withRadar { radar, searches, lists ->
            searches.save(SearchPreset("a", "合成A", "zenkoku", RoomSearchCriteria()))
            searches.save(SearchPreset("b", "合成B", "talk", RoomSearchCriteria()))
            radar.setPlan("a", true); radar.setPlan("b", true); lists.failGenre = "talk"
            radar.scan()
            val state = radar.state.value
            assertEquals(2, lists.calls.size); assertEquals(1, state.lastScan!!.confirmed)
            assertEquals(1, state.lastScan!!.failed); assertFalse(state.running)
            assertEquals(2, state.nextPages["a"]); assertNull(state.results["b"])
            assertNotNull(state.error)
            lists.failGenre = null; radar.scan()
            assertEquals(1, lists.calls.last { it.genre.key == "talk" }.page)
        }
    }
    @Test fun cachedOrMissingObservationsAreNotSuccessfulChecks() = runTest {
        withRadar { radar, searches, lists ->
            searches.save(SearchPreset("a", "合成", "zenkoku", RoomSearchCriteria()))
            radar.setPlan("a", true); radar.scan()
            val old = radar.state.value.results["a"]
            lists.fresh = false; radar.scan()
            assertEquals(1, radar.state.value.lastScan!!.failed)
            assertEquals(0, radar.state.value.lastScan!!.confirmed)
            assertEquals(2, radar.state.value.nextPages["a"])
            assertEquals(old, radar.state.value.results["a"])
            assertEquals(1000L, radar.state.value.lastConfirmedAt)
        }
    }
    @Test fun failedPageSaveRestoresCursorsBaselinesAndArrivalDetection() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            var rejectNewEvent = false
            val dao = object : PresetDao by db.presets() {
                override suspend fun put(value: LocalState) {
                    if (rejectNewEvent && org.json.JSONObject(value.value).getJSONArray("events").length() > 0) {
                        rejectNewEvent = false
                        throw java.io.IOException("合成の保存失敗")
                    }
                    db.presets().put(value)
                }
            }
            val searches = SearchPresetRepository(db)
            searches.save(SearchPreset("a", "合成", "zenkoku", RoomSearchCriteria()))
            val lists = Lists()
            val radar = RadarRepository(dao, lists, searches, RoomPreferenceRepository(db), backgroundScope)
            radar.state.first { it.loaded }; radar.setPlan("a", true)
            radar.saveCandidate(CandidateRule(id = "c", label = "合成候補", genreKey = "zenkoku", term = "合成"))
            radar.scan(); radar.scan()
            val previous = radar.state.value.results["a"]
            lists.extra = true; rejectNewEvent = true; radar.scan()
            assertEquals(1, radar.state.value.lastScan!!.failed)
            assertEquals(1, radar.state.value.nextPages["a"])
            assertEquals(1, radar.state.value.nextCandidatePages["c"])
            assertEquals(previous, radar.state.value.results["a"])
            assertTrue(radar.state.value.events.isEmpty())
            radar.scan()
            assertEquals(listOf(1, 2, 1, 1), lists.calls.map { it.page })
            assertEquals(2, radar.state.value.events.size)
            assertEquals(1, radar.state.value.lastScan!!.confirmed)
        } finally { db.close() }
    }
    @Test fun stoppingDuringARequestMarksTheRemainingPagesAsSkipped() = runTest {
        withRadar { radar, searches, lists ->
            searches.save(SearchPreset("a", "合成A", "zenkoku", RoomSearchCriteria()))
            searches.save(SearchPreset("b", "合成B", "talk", RoomSearchCriteria()))
            radar.setPlan("a", true); radar.setPlan("b", true)
            lists.onFetch = { radar.pauseAllPlans() }; radar.scan()
            assertEquals(1, lists.calls.size)
            assertEquals(1, radar.state.value.lastScan!!.confirmed)
            assertEquals(1, radar.state.value.lastScan!!.skipped)
            assertEquals(2, radar.state.value.lastScan!!.completed)
            assertTrue(radar.state.value.results.isEmpty())
        }
    }
}
