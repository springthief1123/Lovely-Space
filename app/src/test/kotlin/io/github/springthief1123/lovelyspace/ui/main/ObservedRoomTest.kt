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

    @Test fun keepsTheLatestStateAndBorrowsTheEarlierName() {
        val observations = listOf(seen(1, waiting.copy(message = "古い本文")), seen(2, waiting), seen(3, full))
        // 状態は最新（満室）のまま、名前だけ待機中に見えたものを補う。
        assertEquals(full.copy(name = "合成"), observedRoom(observations, Genres.default.key, 42))
    }

    @Test fun doesNotBorrowAcrossAReusedId() {
        // 年齢が違う（別の人の部屋に変わった）観測からは名前を補わない。
        val other = waiting.copy(name = null, status = RoomStatus.FULL, action = RoomAction.NONE, age = 40)
        val observations = listOf(seen(1, waiting), seen(2, other), seen(3, full.copy(age = 40)))
        assertEquals(null, observedRoom(observations, Genres.default.key, 42)?.name)
    }

    @Test fun fallsBackToTheNamelessSightingAndIgnoresOtherRooms() {
        assertEquals(full, observedRoom(listOf(seen(1, full)), Genres.default.key, 42))
        assertNull(observedRoom(listOf(seen(1, waiting.copy(id = 7))), Genres.default.key, 42))
    }
}
