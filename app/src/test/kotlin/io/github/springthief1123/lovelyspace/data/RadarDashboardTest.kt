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
        var failGenre: String? = null
        var onFetch: (suspend () -> Unit)? = null
        val calls = mutableListOf<RoomQuery>()
        override fun observation(query: RoomQuery) = observations.value[query]
        override suspend fun fetch(query: RoomQuery, force: Boolean): RoomListPage {
            calls += query
            onFetch?.invoke()
            if (query.genre.key == failGenre) error("合成の取得失敗")
            val room = Room(42, query.genre.key, RoomStatus.WAITING, RoomAction.ENTER, null, "合成", Gender.FEMALE, 25, null, "本文")
            val page = RoomListPage(query.genre.key, listOf(room), null, null, query.page, 2, emptyMap(), null)
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
