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
class RadarRepositoryTest {
    private val room = Room(42, "zenkoku", RoomStatus.WAITING, RoomAction.ENTER, null, "合成", Gender.FEMALE, 25, null, "本文")
    private class Lists : ObservedRoomListSource {
        override val observations = MutableStateFlow<Map<RoomQuery, ObservedRoomPage>>(emptyMap())
        var rooms = emptyList<Room>()
        var revision = 0L
        val calls = mutableListOf<RoomQuery>()
        override fun observation(query: RoomQuery) = observations.value[query]
        override suspend fun fetch(query: RoomQuery, force: Boolean): RoomListPage {
            calls += query
            val page = RoomListPage(query.genre.key, rooms, null, null, query.page, 1, emptyMap(), null)
            observations.value += query to ObservedRoomPage(query, page, ++revision * 1000, revision)
            return page
        }
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
            lists.rooms = listOf(room.copy(name = "別の合成")); radar.scan()
            assertEquals(RoomIdentityEvidence.REUSED, radar.state.value.targets.single().evidence)
            assertTrue(radar.state.value.events.any { "追跡を停止" in it.text })
            val persisted = db.presets().state("radar_v1")!!
            assertTrue(persisted.contains("REUSED"))
            assertTrue(persisted.contains("条件"))
        } finally { db.close() }
    }
}
