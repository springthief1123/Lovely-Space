package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.core.RoomListPage
import io.github.springthief1123.lovelyspace.core.RoomQuery
import io.github.springthief1123.lovelyspace.core.RoomStatus
import io.github.springthief1123.lovelyspace.data.ObservedRoomPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObservedRoomTest {
    private val waiting = Room(42, Genres.default.key, RoomStatus.WAITING, RoomAction.ENTER, null, "合成", Gender.FEMALE, 25, null, "本文")
    private val full = waiting.copy(status = RoomStatus.FULL, action = RoomAction.NONE, name = null)

    private fun seen(revision: Long, vararg rooms: Room) = ObservedRoomPage(
        RoomQuery(Genres.default, page = revision.toInt()),
        RoomListPage(Genres.default.key, rooms.toList(), null, null, revision.toInt(), 3, emptyMap(), null),
        revision * 1000, revision,
    )

    @Test fun prefersTheLatestSightingThatStillShowsTheName() {
        val observations = listOf(seen(1, waiting.copy(age = 24)), seen(2, waiting), seen(3, full))
        assertEquals(waiting, observedRoom(observations, Genres.default.key, 42))
    }

    @Test fun fallsBackToTheNamelessSightingAndIgnoresOtherRooms() {
        assertEquals(full, observedRoom(listOf(seen(1, full)), Genres.default.key, 42))
        assertNull(observedRoom(listOf(seen(1, waiting.copy(id = 7))), Genres.default.key, 42))
    }
}
