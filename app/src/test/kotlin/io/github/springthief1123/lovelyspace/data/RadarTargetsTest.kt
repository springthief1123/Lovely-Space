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
class RadarTargetsTest {
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
    @Test fun pinningWinsOverSortingAndUnconfirmedDatesSortLast() {
        val a = TrackedRoom(room.copy(id = 1), confirmedAt = 100)
        val b = TrackedRoom(room.copy(id = 2), confirmedAt = 200)
        val pin = TrackedRoom(room.copy(id = 3), confirmedAt = 1, pinned = true)
        val unknown = TrackedRoom(room.copy(id = 4))
        assertEquals(listOf(3L, 2L, 1L, 4L), listOf(unknown, a, pin, b).organized(RadarTargetSort.LAST_CONFIRMED).map { it.room.id })
        assertEquals(listOf(3L, 1L, 2L, 4L), listOf(unknown, b, a, pin).organized(RadarTargetSort.NAME).map { it.room.id })
    }
    @Test fun observationsAndRestartKeepPersonalMetadataWithoutReusingAnIdentity() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            val lists = Lists(listOf(room))
            val searches = SearchPresetRepository(db)
            val job = SupervisorJob(backgroundScope.coroutineContext[Job])
            val radar = RadarRepository(db.presets(), lists, searches, RoomPreferenceRepository(db), CoroutineScope(backgroundScope.coroutineContext + job))
            radar.state.first { it.loaded }; radar.track(room)
            radar.updateTarget(room, pinned = true, note = "合成の自分用メモ\n二行目")
            radar.setTargetSort(RadarTargetSort.NAME); radar.scan()
            assertTrue(radar.state.value.targets.single().pinned)
            lists.rooms = listOf(room.copy(name = "別の合成")); radar.scan()
            val target = radar.state.value.targets.single()
            assertEquals(RoomIdentityEvidence.REUSED, target.evidence)
            assertEquals("合成の自分用メモ\n二行目", target.note)
            try { radar.updateTarget(lists.rooms.single(), note = "転用しない"); fail("元の記録と異なる") } catch (_: IllegalArgumentException) { }
            job.cancelAndJoin()
            val restored = RadarRepository(db.presets(), Lists(emptyList()), searches, RoomPreferenceRepository(db), backgroundScope)
            restored.state.first { it.loaded }
            assertEquals(target.note, restored.state.value.targets.single().note)
            assertTrue(restored.state.value.targets.single().pinned)
            assertEquals(RadarTargetSort.NAME, restored.state.value.targetSort)
            assertEquals(RoomIdentityEvidence.REUSED, restored.state.value.targets.single().evidence)
        } finally { db.close() }
    }
    @Test fun failedMetadataSaveRestoresPinMemoAndSort() = runTest {
        val db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java).build()
        try {
            var reject = false
            val dao = object : PresetDao by db.presets() {
                override suspend fun put(value: LocalState) {
                    if (reject) throw java.io.IOException("合成の保存失敗")
                    db.presets().put(value)
                }
            }
            val radar = RadarRepository(dao, Lists(emptyList()), SearchPresetRepository(db), RoomPreferenceRepository(db), backgroundScope)
            radar.state.first { it.loaded }; radar.track(room); runCurrent(); reject = true
            try { radar.updateTarget(room, pinned = true, note = "合成"); fail("保存失敗") } catch (_: java.io.IOException) { }
            assertFalse(radar.state.value.targets.single().pinned); assertEquals("", radar.state.value.targets.single().note)
            try { radar.setTargetSort(RadarTargetSort.NAME); fail("保存失敗") } catch (_: java.io.IOException) { }
            assertEquals(RadarTargetSort.LAST_CONFIRMED, radar.state.value.targetSort)
        } finally { db.close() }
    }
}
