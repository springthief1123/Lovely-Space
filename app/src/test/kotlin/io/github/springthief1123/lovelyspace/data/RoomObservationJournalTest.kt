package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** すべて合成データ。StateFlowだけでは消える中間観測を、イベントとして順番に受け取る。 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomObservationJournalTest {
    private val genre = Genres["zenkoku"]!!
    private val query = RoomQuery(genre)
    private val full = Room(900L, genre.key, RoomStatus.FULL, RoomAction.NONE, null, null,
        Gender.UNKNOWN, null, null, "合成の募集文")
    private val open = full.copy(status = RoomStatus.WAITING, action = RoomAction.ENTER, name = "合成の部屋主")

    private fun page(room: Room) = RoomListPage(genre.key, listOf(room), null, null, 1, 1, emptyMap(), null)


    @Test fun incompletePagesRemainIncompleteInEventsAndRebuiltGenreWindows() = runTest {
        val journal = RoomObservationJournal()
        val complete = RoomListPage(genre.key, (1000 downTo 300 step 100).map { full.copy(id = it.toLong(), message = "合成の募集文$it") },
            null, null, 1, 1, emptyMap(), null)
        val partial = complete.copy(rooms = complete.rooms.filterNot { it.id == 800L }, droppedRowCount = 1)
        val pinned = complete.rooms.single { it.id == 800L }
        val seen = mutableListOf<ObservedRoomChange>()
        backgroundScope.launch { journal.changes.collect { seen += it } }
        runCurrent()
        journal.record(query, complete, 1_000)
        journal.record(query, partial, 2_000)
        runCurrent()
        org.junit.Assert.assertFalse(seen.last().observation.page.rowsComplete)
        val live = seen.fold(RoomPageWindow()) { window, change -> window.observe(change.observation.page, change.observation.revision) }
        org.junit.Assert.assertTrue(live.listedRooms.any { it.id == pinned.id })
        org.junit.Assert.assertFalse(live.isClosed(pinned))
        val rebuilt = journal.observations.value.genreWindow(genre)
        org.junit.Assert.assertFalse(rebuilt.isClosed(pinned))
        assertEquals(setOf(1), rebuilt.incompletePages)
    }
    @Test fun rapidFullOpenFullUpdatesAreDeliveredInOrderEvenWhenLatestStateHasReturnedToFull() = runTest {
        val journal = RoomObservationJournal()
        val seen = mutableListOf<ObservedRoomChange>()
        backgroundScope.launch { journal.changes.collect { seen += it } }
        runCurrent() // 購読開始。次の3件はコレクタを実行する前にまとめて発行する。

        journal.record(query, page(full), 1_000)
        journal.record(query, page(open), 2_000)
        journal.record(query, page(full), 3_000)
        assertEquals(RoomStatus.FULL, journal.observations.value.getValue(query).page.rooms.single().status)
        assertEquals(emptyList<ObservedRoomChange>(), seen)

        runCurrent()
        assertEquals(listOf(RoomStatus.FULL, RoomStatus.WAITING, RoomStatus.FULL),
            seen.map { it.observation.page.rooms.single().status })
        assertEquals(listOf(1L, 2L, 3L), seen.map { it.observation.revision })
        assertEquals(listOf(1L, 2L, 3L), seen.map { it.pages.getValue(query).revision })
    }

    @Test fun sameCachedPageDoesNotCreateAnotherObservationOrEvent() = runTest {
        val journal = RoomObservationJournal()
        val seen = mutableListOf<ObservedRoomChange>()
        backgroundScope.launch { journal.changes.collect { seen += it } }
        runCurrent()

        val cached = page(full)
        journal.record(query, cached, 1_000)
        journal.record(query, cached, 2_000)
        runCurrent()

        assertEquals(1, seen.size)
        assertSame(cached, journal.observations.value.getValue(query).page)
        assertEquals(1L, journal.observations.value.getValue(query).revision)
    }

    @Test fun lateSubscriberReceivesTheCurrentPagesWithoutReplayingAnInventedTransition() = runTest {
        val journal = RoomObservationJournal()
        journal.record(query, page(full), 1_000)
        val seen = mutableListOf<ObservedRoomChange>()
        backgroundScope.launch { journal.changes.collect { seen += it } }
        runCurrent()

        assertEquals(1, seen.size)
        assertEquals(RoomStatus.FULL, seen.single().observation.page.rooms.single().status)
        journal.record(query, page(open), 2_000)
        runCurrent()
        assertEquals(listOf(1L, 2L), seen.map { it.observation.revision })
    }

    @Test fun snapshotsKeepTheirOriginalPageAfterNewerUpdates() = runTest {
        val journal = RoomObservationJournal()
        val seen = mutableListOf<ObservedRoomChange>()
        backgroundScope.launch { journal.changes.collect { seen += it } }
        runCurrent()
        journal.record(query, page(full), 1_000)
        journal.record(query, page(open), 2_000)
        journal.record(query, page(full), 3_000)
        runCurrent()

        assertEquals(RoomStatus.WAITING, seen[1].pages.getValue(query).page.rooms.single().status)
        assertEquals(RoomStatus.FULL, seen[2].pages.getValue(query).page.rooms.single().status)
    }
}
