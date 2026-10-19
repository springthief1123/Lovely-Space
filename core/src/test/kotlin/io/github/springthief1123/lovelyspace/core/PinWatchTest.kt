package io.github.springthief1123.lovelyspace.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset

class PinWatchTest {
    private fun room(id: Long, name: String? = "合成$id", message: String = "合成の募集文$id、よろしくお願いします", status: RoomStatus = RoomStatus.WAITING) =
        Room(id, "zenkoku", status, if (status == RoomStatus.FULL) RoomAction.NONE else RoomAction.ENTER, null, name, Gender.FEMALE, 25, null, message)

    private fun page(number: Int, rooms: List<Room>, last: Int = 1) = RoomListPage("zenkoku", rooms, null, null, number, last, emptyMap(), null)

    @Test fun pinStateReadsTheRoomFromTheWindowAndOnlyCallsItClosedWhenTheIdRangeProvesIt() {
        val pinned = room(500)
        val rooms = (600L downTo 400L step 20).map { room(it) }
        val window = RoomPageWindow().observe(page(1, rooms))
        assertEquals(PinState.OPEN, window.pinState(pinned).first)
        assertEquals(PinState.FULL, RoomPageWindow().observe(page(1, rooms.map { if (it.id == 500L) it.copy(status = RoomStatus.FULL, name = null) else it }))
            .pinState(pinned).first)
        val without = RoomPageWindow().observe(page(1, (900L downTo 100L step 100).map { room(it) }.filterNot { it.id == 500L }))
        assertEquals(PinState.CLOSED, without.pinState(pinned).first)
        // 読んだページの範囲の外（読んでいない後ろのページかもしれない）なら、閉じたとは言えない。
        val partial = RoomPageWindow().observe(page(1, (900L downTo 700L step 50).map { room(it) }, last = 3))
        assertEquals(PinState.UNSEEN, partial.pinState(pinned).first)
    }

    @Test fun aRoomOlderThanTheLastPageIsClosedOnceTheLastPageHasBeenRead() {
        val yesterday = room(10)
        val window = RoomPageWindow().observe(page(1, (900L downTo 700L step 50).map { room(it) }, last = 2))
        assertFalse(window.isClosed(yesterday))
        val both = window.observe(page(2, (650L downTo 400L step 50).map { room(it) }, last = 2))
        assertTrue(both.isClosed(yesterday))
        assertEquals(PinState.CLOSED, both.pinState(yesterday).first)
    }

    @Test fun similarityIgnoresSpacingAndKanaButDropsWithRewrites() {
        assertEquals(1.0, messageSimilarity("ゆるく お話 しよう", "ユルクお話しよう"), 1e-9)
        assertTrue(messageSimilarity("寝る前にゆるく話そう。通話はなしで", "今夜も寝る前にゆるく話そう。通話はなしで") >= 0.85)
        assertTrue(messageSimilarity("寝る前にゆるく話そう", "映画の話がしたいです") < 0.3)
        assertEquals(0.0, messageSimilarity("", "なにか"), 0.0)
    }

    @Test fun similarRoomNeedsTheSameNameOrANearIdenticalMessage() {
        val old = room(100, name = "合成さん", message = "寝る前にゆるく話そう。通話はなしでお願いします")
        val rewritten = room(900, name = "合成さん", message = "今夜も寝る前にゆるく話そう。通話はなし")
        val other = room(901, name = "別の合成", message = "映画の話がしたいです。最近見たのはミステリー")
        val window = RoomPageWindow().observe(page(1, listOf(other, rewritten)))
        val found = window.similarRoomOf(old)
        assertEquals(900L, found?.room?.id)
        assertTrue(found!!.similarity >= SIMILAR_SAME_NAME)
        // 名前が違えば、ほぼ同じ募集文のときだけ。
        assertNull(RoomPageWindow().observe(page(1, listOf(rewritten.copy(name = "別の名前")))).similarRoomOf(old))
        assertNotNull(RoomPageWindow().observe(page(1, listOf(rewritten.copy(name = "別の名前", message = old.message + "！")))).similarRoomOf(old))
        // 「別の人」と答えた部屋・古い ID・同じ募集文の立て直し（自動で引き継ぐ）は候補にしない。
        assertNull(window.similarRoomOf(old, skip = setOf(roomIdentity(rewritten))))
        assertNull(RoomPageWindow().observe(page(1, listOf(rewritten.copy(id = 50)))).similarRoomOf(old))
        assertNull(RoomPageWindow().observe(page(1, listOf(old.copy(id = 902)))).similarRoomOf(old))
        // 短い定型文は照合しない。
        assertNull(RoomPageWindow().observe(page(1, listOf(room(903, name = "合成さん", message = "よろしく")))).similarRoomOf(old.copy(message = "よろしく")))
    }

    @Test fun twoEquallySimilarRoomsAreNotGuessed() {
        val old = room(100, name = "合成さん", message = "寝る前にゆるく話そう。通話はなしでお願いします")
        val a = room(900, name = "合成さん", message = "寝る前にゆるく話そう。通話はなしでお願いします！")
        val b = room(901, name = "合成さん", message = "寝る前にゆるく話そう。通話はなしでお願いします？")
        assertNull(RoomPageWindow().observe(page(1, listOf(b, a))).similarRoomOf(old))
    }

    @Test fun vacancyStatsCountTheLastWeekByHour() {
        val hour = 60 * 60 * 1000L
        val now = 10 * 24 * hour
        val times = listOf(now - 2 * hour, now - 26 * hour, now - 50 * hour, now - 9 * 24 * hour)
        val stats = VacancyStats.of(times, now, ZoneOffset.UTC)
        assertEquals(4, stats.count)
        assertEquals(now - 2 * hour, stats.last)
        assertEquals(3, stats.hours.sum())
        assertEquals(22, stats.peakHour)
        assertNull(VacancyStats.of(times.take(2), now, ZoneOffset.UTC).peakHour)
    }
}
