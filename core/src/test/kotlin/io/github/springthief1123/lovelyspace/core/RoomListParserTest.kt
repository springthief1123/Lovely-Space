package io.github.springthief1123.lovelyspace.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RoomListParserTest {
    private val page = RoomListParser.parse(fixture("genre_kanto_page1.html"), "kanto")

    @Test
    fun parsesAllRoomsAcrossSplitTables() {
        assertEquals(listOf(900000001L, 900000002L, 900000003L, 900000004L), page.rooms.map { it.id })
    }

    @Test
    fun parsesPublicWaitingRoom() {
        val room = page.rooms[0]
        assertEquals(RoomStatus.PUBLIC_WAITING, room.status)
        assertEquals(RoomAction.ENTER, room.action)
        assertTrue(room.isPublic)
        assertEquals("さくら", room.name)
        assertEquals(Gender.FEMALE, room.gender)
        assertEquals(27, room.age)
        assertEquals("東京", room.area)
        assertEquals("映画の話をしませんか", room.message)
        assertEquals(13.minutes + 5.seconds, room.elapsed)
        assertEquals("kanto", room.genreKey)
    }

    @Test
    fun parsesPrivateWaitingRoomWithoutAgeOrArea() {
        val room = page.rooms[1]
        assertEquals(RoomStatus.WAITING, room.status)
        assertFalse(room.isPublic)
        assertNull(room.age)
        assertNull(room.area)
        assertEquals(Gender.MALE, room.gender)
        assertEquals("のんびり雑談できる方募集", room.message)
        assertEquals(3.hours + 48.minutes + 5.seconds, room.elapsed)
    }

    @Test
    fun fullPublicRoomUsesHiddenMessageAndHasNoName() {
        val room = page.rooms[2]
        assertEquals(RoomStatus.FULL, room.status)
        assertEquals(RoomAction.PEEK, room.action)
        assertTrue(room.isPublic)
        assertNull(room.name)
        assertEquals(Gender.FEMALE, room.gender)
        assertEquals(21, room.age)
        assertEquals("大阪", room.area)
        assertEquals("音楽好きな人と話したい", room.message)
    }

    @Test
    fun fullPrivateRoomCannotBeOpened() {
        val room = page.rooms[3]
        assertEquals(RoomAction.NONE, room.action)
        assertFalse(room.isPublic)
        assertNull(room.area)
        assertEquals("週末の予定を話しましょう", room.message)
    }

    @Test
    fun parsesCountsAndPagination() {
        assertEquals(1005, page.waitingCount)
        assertEquals(57, page.fullCount)
        assertEquals(1, page.page)
        assertEquals(18, page.lastPage)
        assertTrue(page.hasNextPage)
        assertEquals(7893, page.totalRooms)
        assertEquals(mapOf("zenkoku" to 21, "kanto" to 1062, "chah" to 1189, "talk" to 333), page.genreCounts)
    }

    @Test
    fun pagerLinksKeepFiltersInThePathAndSkipNonListLinks() {
        // 本家の構造だけを再現した合成のページャ（絞り込み中は条件がパスに入る）。
        val html = """
            <a href="https://chat.shalove.net/ReportBadRoomInfo/genre_key/hokkaido/pageID/1/">通報</a>
            <p align="center"><nobr><span>1-60</span></nobr>
            <nobr><a href="https://chat.shalove.net/g/hokkaido/vsex/1/pageID/2/" title="ページ 61-120">61-120</a></nobr>
            <nobr><a href="/g/hokkaido/vsex/1/pageID/3/" title="ページ 121-123">121-123</a></nobr>
            <nobr><a href="https://chat.shalove.net/g/hokkaido/vsex/1/pageID/2/" title="次のページ"><b>次→</b></a></nobr></p>
        """.trimIndent()
        val parsed = RoomListParser.parse(html, "hokkaido", baseUri = "https://chat.shalove.net/g/hokkaido/?vsex=1")
        assertEquals(3, parsed.lastPage)
        assertEquals(
            mapOf(
                2 to "https://chat.shalove.net/g/hokkaido/vsex/1/pageID/2/",
                3 to "https://chat.shalove.net/g/hokkaido/vsex/1/pageID/3/",
            ),
            parsed.pageUrls,
        )
    }

    @Test
    fun emptyPageHasNoRooms() {
        val empty = RoomListParser.parse("<html><body>部屋がありません</body></html>", "kanto")
        assertTrue(empty.rooms.isEmpty())
        assertEquals(1, empty.lastPage)
        assertFalse(empty.hasNextPage)
    }

    @Test
    fun parsesElapsed() {
        assertEquals(1.hours + 2.minutes + 3.seconds, RoomListParser.parseElapsed("01:02:03"))
        assertEquals(2.minutes + 3.seconds, RoomListParser.parseElapsed("02:03"))
        assertNull(RoomListParser.parseElapsed("abc"))
    }
}

internal fun fixture(name: String): String =
    requireNotNull(RoomListParserTest::class.java.getResource("/fixtures/$name")) { "missing fixture $name" }
        .readText(Charsets.UTF_8)
