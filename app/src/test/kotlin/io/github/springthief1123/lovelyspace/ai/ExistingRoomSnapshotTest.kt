package io.github.springthief1123.lovelyspace.ai

import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.core.RoomListPage
import io.github.springthief1123.lovelyspace.core.RoomQuery
import io.github.springthief1123.lovelyspace.core.RoomStatus
import io.github.springthief1123.lovelyspace.data.ObservedRoomPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExistingRoomSnapshotTest {
    private fun room(
        id: Long,
        text: String,
        status: RoomStatus = RoomStatus.WAITING,
        genre: String = "adult",
    ) = Room(
        id = id,
        genreKey = genre,
        status = status,
        action = if (status == RoomStatus.FULL) RoomAction.NONE else RoomAction.ENTER,
        elapsed = null,
        name = "テスト用の人物名",
        gender = Gender.UNKNOWN,
        age = 29,
        area = "テスト地域",
        message = text,
    )

    private fun observation(
        at: Long,
        vararg rooms: Room,
    ) = ObservedRoomPage(
        query = RoomQuery(genre = io.github.springthief1123.lovelyspace.core.Genres.default),
        page = RoomListPage(
            genreKey = "adult",
            rooms = rooms.toList(),
            waitingCount = null,
            fullCount = null,
            page = 1,
            lastPage = 1,
            genreCounts = emptyMap(),
            totalRooms = null,
        ),
        confirmedAt = at,
        revision = 1,
    )

    @Test fun onlyRecentWaitingMessagesAreSelected() {
        val now = 2_000_000L
        val selected = ExistingRoomSnapshot.select(listOf(
            observation(now - 60_000L,
                room(1, "落ち着いて話せる募集文"),
                room(2, "   "),
                room(3, "満室の非公開メッセージ", status = RoomStatus.FULL)),
            observation(now - ExistingRoomSnapshot.MAX_AGE_MS - 1L, room(4, "古い部屋")),
            observation(now + 1L, room(5, "未来の観測")),
        ), now)
        assertEquals(listOf("落ち着いて話せる募集文"), selected)
    }

    @Test fun duplicatesAreRemovedAndTextsClipped() {
        val now = 1_000_000L
        val longText = "あ".repeat(260)
        val selected = ExistingRoomSnapshot.select(
            listOf(observation(now, room(1, longText), room(1, "重複"), room(2, "  こんばんは  "))), now,
        )
        assertEquals(2, selected.size)
        assertEquals(240, selected.first().length)
        assertEquals("こんばんは", selected.last())
    }

    @Test fun corpusIsCappedAt100() {
        val now = 1_000_000L
        val rooms = (1L..150L).map { id -> room(id, "架空の募集文 ${id}") }
        val selected = ExistingRoomSnapshot.select(
            listOf(observation(now, *rooms.toTypedArray())), now,
        )
        assertEquals(100, selected.size)
    }

    @Test fun queryHasStrictLengthAndNoImplicitResults() {
        assertTrue(runCatching { ExistingRoomSnapshot.validateQuery(" ") }.isFailure)
        assertTrue(runCatching { ExistingRoomSnapshot.validateQuery("あ".repeat(121)) }.isFailure)
        assertEquals("ゆっくり雑談", ExistingRoomSnapshot.validateQuery("  ゆっくり雑談 "))
        assertFalse(ExistingRoomSnapshot.select(emptyList(), 1000L).isNotEmpty())
    }
}
