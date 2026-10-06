package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RadarRoomInspectionTest {
    private val room = Room(42, "zenkoku", RoomStatus.WAITING, RoomAction.ENTER, null, "合成", Gender.FEMALE, 25, null, "本文")
    private class Lists(var rooms: List<Room>, var fresh: Boolean = true) : RoomListSource {
        var observed: ObservedRoomPage? = null
        var calls = 0
        var force = false
        override fun observation(query: RoomQuery) = observed
        override suspend fun fetch(query: RoomQuery, force: Boolean): RoomListPage {
            calls++; this.force = force
            val page = RoomListPage(query.genre.key, rooms, null, null, query.page, 3, emptyMap(), null)
            if (fresh) observed = ObservedRoomPage(query, page, 1000, (observed?.revision ?: 0) + 1)
            return page
        }
    }
    @Test fun historicalSnapshotRequiresFreshProfileMatchOnItsRecordedPage() = runTest {
        val lists = Lists(listOf(room.copy(message = "新しい合成本文")))
        val result = inspectRadarRoom(lists, RadarRoomSnapshot(room, 500, page = 3))
        assertEquals("新しい合成本文", result.room?.message)
        assertEquals(3, lists.observed?.query?.page)
        assertTrue(lists.force)
        lists.fresh = false
        assertNull(inspectRadarRoom(lists, RadarRoomSnapshot(room, 500, page = 3)).room)
    }
    @Test fun filteredSourceQueryIsUsedWithoutDroppingAnyFilter() = runTest {
        val query = RoomQuery(Genres[room.genreKey]!!, sex = Gender.FEMALE, prefecture = 13,
            ageBand = "20-29", publicOnly = false, waitingOnly = true, name = "合成", message = "本文", page = 3)
        val lists = Lists(listOf(room))
        assertNotNull(inspectRadarRoom(lists, RadarRoomSnapshot(room, 500, sourceQuery = query)).room)
        assertEquals(query, lists.observed?.query)
        assertNull(inspectRadarRoom(lists, RadarRoomSnapshot(room, 500, sourceQuery = query.copy(genre = Genres["talk"]!!))).room)
    }
    @Test fun reusedHiddenAndMissingProfilesNeverEnableEntry() = runTest {
        val snapshot = RadarRoomSnapshot(room, 500)
        val reused = inspectRadarRoom(Lists(listOf(room.copy(name = "別の合成"))), snapshot)
        assertNull(reused.room); assertTrue(reused.reused)
        assertNull(inspectRadarRoom(Lists(listOf(room.copy(name = null, status = RoomStatus.FULL))), snapshot).room)
        assertNull(inspectRadarRoom(Lists(emptyList()), snapshot).room)
        val blocked = Lists(listOf(room))
        assertNull(inspectRadarRoom(blocked, snapshot.copy(blocked = true)).room)
        assertEquals(0, blocked.calls)
    }
}
