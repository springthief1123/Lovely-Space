package io.github.springthief1123.lovelyspace.data

import android.app.Application
import androidx.room.Room as RoomDb
import androidx.test.core.app.ApplicationProvider
import io.github.springthief1123.lovelyspace.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class RadarHistoryTest {
    private val room = Room(42, "zenkoku", RoomStatus.WAITING, RoomAction.ENTER, null, "合成", Gender.FEMALE, 25, null, "本文")
    private class Lists(var rooms: List<Room>) : ObservedRoomListSource {
        override val observations = MutableStateFlow<Map<RoomQuery, ObservedRoomPage>>(emptyMap())
        var revision = 0L
        override fun observation(query: RoomQuery) = observations.value[query]
        override suspend fun fetch(query: RoomQuery, force: Boolean): RoomListPage {
            val page = RoomListPage(query.genre.key, rooms, null, null, query.page, 1, emptyMap(), null)
            revision++; observations.value += query to ObservedRoomPage(query, page, revision * 1000, revision)
            return page
        }
    }
    @Test fun historyFiltersUseStableOriginsAndDoNotMixIdenticalNames() {
        val a = RadarEvent(1, "合成", kind = RadarEventKind.SEARCH_MATCH, origin = RadarEventOrigin(RadarOriginType.PLAN, "a", "同名"))
        val b = a.copy(id = "b", origin = a.origin!!.copy(id = "b"))
        val filter = RadarHistoryFilter(unreadOnly = true, kind = RadarEventKind.SEARCH_MATCH, originKey = a.origin!!.key)
        assertTrue(a.matches(filter)); assertFalse(b.matches(filter)); assertFalse(a.copy(read = true).matches(filter))
        assertFalse(a.copy(kind = RadarEventKind.CANDIDATE_MATCH).matches(filter))
    }
    @Test fun arrivalsAfterTheReadSnapshotStayUnreadAndSurviveRestart() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            db.presets().put(LocalState("radar_v1", """{"plans":[],"targets":[],"events":[{"at":1,"id":"old","text":"合成の旧履歴"}]}"""))
            val searches = SearchPresetRepository(db)
            searches.save(SearchPreset("p", "合成条件", "zenkoku", RoomSearchCriteria()))
            val lists = Lists(listOf(room))
            val job = SupervisorJob(backgroundScope.coroutineContext[Job])
            val radar = RadarRepository(db.presets(), lists, searches, RoomPreferenceRepository(db), CoroutineScope(backgroundScope.coroutineContext + job))
            radar.state.first { it.loaded }
            assertTrue(radar.state.value.events.single().read)
            assertEquals(RadarEventKind.LEGACY, radar.state.value.events.single().kind)
            radar.setPlan("p", true); radar.scan()
            lists.rooms += room.copy(id = 43); radar.scan()
            val idsAtClick = radar.state.value.events.map { it.id }.toSet()
            lists.rooms += room.copy(id = 44); radar.scan()
            radar.markEventsRead(idsAtClick)
            val unread = radar.state.value.events.single { !it.read }
            assertEquals(44L, unread.rooms.single().id)
            assertEquals(RadarEventKind.SEARCH_MATCH, unread.kind)
            assertEquals("p", unread.origin!!.id); assertEquals(1, radar.state.value.unreadEvents)
            job.cancelAndJoin()
            val restored = RadarRepository(db.presets(), Lists(emptyList()), searches, RoomPreferenceRepository(db), backgroundScope)
            restored.state.first { it.loaded }
            assertEquals(unread, restored.state.value.events.single { !it.read })
            restored.markEventsRead(setOf(unread.id)); assertEquals(0, restored.state.value.unreadEvents)
        } finally { db.close() }
    }
    @Test fun failedReadSaveDoesNotLoseTheUnreadState() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            db.presets().put(LocalState("radar_v1", """{"plans":[],"targets":[],"events":[{"at":1,"id":"a","text":"合成","read":false,"kind":"ROOM_STATUS"}]}"""))
            var reject = false
            val dao = object : PresetDao by db.presets() {
                override suspend fun put(value: LocalState) {
                    if (reject) throw java.io.IOException("合成の保存失敗")
                    db.presets().put(value)
                }
            }
            val radar = RadarRepository(dao, Lists(emptyList()), SearchPresetRepository(db), RoomPreferenceRepository(db), backgroundScope)
            radar.state.first { it.loaded }; runCurrent(); reject = true
            try { radar.markEventsRead(setOf("a")); fail("保存失敗を通知する") } catch (_: java.io.IOException) { }
            assertFalse(radar.state.value.events.single().read)
            assertEquals(1, radar.state.value.unreadEvents)
        } finally { db.close() }
    }
}
