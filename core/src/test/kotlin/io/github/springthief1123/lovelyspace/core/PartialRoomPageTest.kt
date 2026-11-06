package io.github.springthief1123.lovelyspace.core

import org.junit.Assert.*
import org.junit.Test

class PartialRoomPageTest {
    private fun page(ids: List<Int>, broken: Int? = null, number: Int = 1, last: Int = 2): RoomListPage {
        val html = "<table class='rooms'>" + ids.joinToString("") { id ->
            "<tr class='roomcol'><td><span>待機中</span></td>" +
                "<td><form action='/PreEnterRoom'><input name='room_id' value='$id'><input type='submit'></form></td>" +
                "<td>合成$id</td><td>男</td><td>25</td>" +
                (if (id == broken) "" else "<td>合成の募集文$id</td>") + "</tr>"
        } + "</table>"
        return RoomListParser.parse(html, "zenkoku", number).copy(lastPage = last)
    }

    @Test fun aDroppedMiddleRowDoesNotCloseAPinnedRoom() {
        val ids = listOf(1000, 900, 800, 700, 600, 500, 400, 300)
        val before = page(ids)
        val pinned = before.rooms.single { it.id == 800L }
        val after = RoomPageWindow().observe(before).observe(page(ids, broken = 800))
        assertTrue(after.listedRooms.any { it.id == pinned.id })
        assertFalse(after.isClosed(pinned))
        assertNotEquals(PinState.CLOSED, after.pinState(pinned).first)
    }

    @Test fun partialSinglePageKeepsMissingRoomUntilACompleteObservation() {
        val ids = listOf(1000, 900, 800, 700, 600, 500, 400, 300)
        val before = page(ids, last = 1)
        val after = RoomPageWindow().observe(before).observe(page(ids, broken = 800, last = 1))
        assertTrue(after.listedRooms.any { it.id == 800L })
        val closed = after.observe(page(ids - 800, last = 1))
        assertFalse(closed.listedRooms.any { it.id == 800L })
    }

    @Test fun aPartialPageCannotCompleteAClosureRun() {
        val before = RoomPageWindow().observe(page(listOf(1000, 900, 800, 700)))
            .observe(page(listOf(600, 500, 400), number = 2))
        val moved = before.observe(page(listOf(1000, 900, 800)))
        val partial = moved.observe(page(listOf(600, 500, 400), broken = 500, number = 2))
        assertTrue(partial.listedRooms.any { it.id == 700L })
        assertFalse(partial.isClosed(before.rooms.single { it.id == 700L }))
        assertFalse(partial.observe(page(listOf(600, 500, 400), number = 2)).listedRooms.any { it.id == 700L })
    }
    @Test fun partialObservationCannotCloseAnUnseenPinAtEitherEdge() {
        val ids = listOf(1000, 900, 800, 700, 600, 500, 400, 300)
        val original = page(ids)
        val window = RoomPageWindow().observe(page(ids, broken = 800, last = 1))
        for (id in listOf(800L, 1200L, 100L)) {
            assertFalse(window.isClosed(original.rooms.first().copy(id = id)))
        }
        assertEquals(1, page(ids, broken = 800).droppedRowCount)
        assertFalse(page(ids, broken = 800).rowsComplete)
        assertTrue(original.rowsComplete)
        assertTrue(window.observe(page(ids, last = 1)).incompletePages.isEmpty())
    }

    @Test fun advertisingOutsideRoomRowsDoesNotMakeAPageIncomplete() {
        val html = "<table class='rooms'><tr><td>広告用の合成文</td></tr></table>"
        assertTrue(RoomListParser.parse(html, "zenkoku").rowsComplete)
    }

}
