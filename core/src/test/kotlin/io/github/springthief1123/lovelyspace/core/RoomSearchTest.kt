package io.github.springthief1123.lovelyspace.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

class RoomSearchTest {
    private val room = Room(1, "zenkoku", RoomStatus.WAITING, RoomAction.ENTER, 2.minutes,
        "合成 Alice", Gender.FEMALE, 25, "東京", "猫と雑談")
    @Test fun appliesAndOrAndExclusionsAcrossFields() {
        assertTrue(RoomSearchCriteria(name = "alice 合成", message = "猫　雑談").matches(room))
        assertFalse(RoomSearchCriteria(message = "猫 犬").matches(room))
        assertTrue(RoomSearchCriteria(message = "猫 犬", keywordMode = KeywordMode.ANY).matches(room))
        assertFalse(RoomSearchCriteria(excluded = "犬 ALICE").matches(room))
        assertFalse(RoomSearchCriteria(name = "隠された名前").matches(room.copy(name = null)))
    }
    @Test fun distinguishesUnknownAgesAndInclusiveRange() {
        val criteria = RoomSearchCriteria(minAge = 25, maxAge = 25)
        assertTrue(criteria.matches(room))
        assertTrue(criteria.matches(room.copy(age = null)))
        assertFalse(criteria.copy(includeUnknownAge = false).matches(room.copy(age = null)))
        assertFalse(criteria.copy(minAge = 30, maxAge = 20).isValid)
        assertFalse(criteria.copy(minAge = 17).isValid)
    }
    @Test fun combinesGenderAreaAndPublicFullState() {
        assertTrue(RoomSearchCriteria(gender = Gender.FEMALE, area = "東京", waitingOnly = true, publicOnly = false).matches(room))
        assertFalse(RoomSearchCriteria(gender = Gender.MALE).matches(room))
        assertFalse(RoomSearchCriteria(area = "大阪").matches(room))
        val peek = room.copy(status = RoomStatus.FULL, action = RoomAction.PEEK)
        assertTrue(RoomSearchCriteria(waitingOnly = false, publicOnly = true).matches(peek))
        assertFalse(RoomSearchCriteria(waitingOnly = true).matches(peek))
    }
    @Test fun deduplicatesByHostAndSortsUnknownValuesLastStably() {
        val older = room.copy(id = 2, age = 30, elapsed = 1.minutes)
        val unknown = room.copy(id = 3, age = null, elapsed = null, name = null)
        val otherHost = room.copy(genreKey = "talk")
        val rooms = listOf(unknown, older, room, room.copy(genreKey = "kanto"), otherHost)
        assertEquals(4, searchRooms(rooms, RoomSearchCriteria()).size)
        assertEquals(listOf(25, 25, 30, null), searchRooms(rooms, RoomSearchCriteria(sort = RoomSort.AGE)).map { it.age })
        assertEquals(listOf(2L, 1L, 1L, 3L), searchRooms(rooms, RoomSearchCriteria(sort = RoomSort.ELAPSED)).map { it.id })
        assertNull(searchRooms(rooms, RoomSearchCriteria(sort = RoomSort.NAME)).last().name)
    }

    @Test
    fun siteQueryCarriesOnlyConditionsTheSiteCanFilterWithoutLosingMatches() {
        val kanto = Genres["kanto"]!!
        val query = RoomSearchCriteria(
            gender = Gender.FEMALE, waitingOnly = true, publicOnly = false,
            name = "合成", message = "合成", text = "合成", excluded = "除外", area = "東京",
        ).siteQuery(kanto)
        assertEquals(RoomQuery(kanto, sex = Gender.FEMALE, publicOnly = false, waitingOnly = true), query)
        assertEquals(RoomQuery(kanto), RoomSearchCriteria().siteQuery(kanto))
    }

    @Test
    fun siteQueryUsesAnAgeBandOnlyWhenTheRangeFitsOneBandAndUnknownAgesAreExcluded() {
        val kanto = Genres["kanto"]!!
        fun band(min: Int?, max: Int?, unknown: Boolean = false) =
            RoomSearchCriteria(minAge = min, maxAge = max, includeUnknownAge = unknown).siteQuery(kanto).ageBand
        assertEquals("20-29", band(20, 29))
        assertEquals("20-29", band(23, 25))
        assertEquals("60-", band(60, null))
        assertEquals("-19", band(18, 19))
        assertNull(band(25, 35))
        assertNull(band(null, 29))
        assertNull(band(30, null))
        assertNull(band(20, 29, unknown = true))
        assertNull(band(30, 20))
    }
}
