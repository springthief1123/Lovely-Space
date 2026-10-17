package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.core.RoomSearchCriteria
import io.github.springthief1123.lovelyspace.core.RoomStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class SearchResultsTest {
    private val genre = Genres.all[0]
    private val other = Genres.all[1]

    @Test fun anAutomaticRefreshKeepsShowingThePreviousResultUntilTheNewOneIsReady() {
        val before = listOf(room(1, "映画"))
        val shown = SearchResults.of(genre, before, RoomSearchCriteria(text = "映画"))
        val refreshed = listOf(room(2, "映画"), room(1, "映画"))
        val resolved = shown.resolve(genre, refreshed, RoomSearchCriteria(text = "映画"))
        assertSame(shown, resolved)
        assertEquals(false, resolved.isFor(genre, refreshed, RoomSearchCriteria(text = "映画")))
    }

    @Test fun changingTheCriteriaNeverShowsTheOldResult() {
        val rooms = listOf(room(1, "映画"), room(2, "雑談"))
        val shown = SearchResults.of(genre, rooms, RoomSearchCriteria(text = "映画"))
        // 条件の変更と自動更新が重なっても、新しい条件で計算した結果を出す。
        val refreshed = rooms + room(3, "雑談")
        val resolved = shown.resolve(genre, refreshed, RoomSearchCriteria(text = "雑談"))
        assertNotSame(shown, resolved)
        assertEquals(listOf(2L, 3L), resolved.results.map { it.id })
        assertEquals(true, resolved.isFor(genre, refreshed, RoomSearchCriteria(text = "雑談")))
    }

    @Test fun changingTheGenreDropsTheOldResultAtOnce() {
        val shown = SearchResults.of(genre, listOf(room(1, "映画")), RoomSearchCriteria())
        val resolved = shown.resolve(other, emptyList(), RoomSearchCriteria())
        assertEquals(emptyList<Room>(), resolved.results)
    }

    @Test fun theFirstResultIsComputedOnTheSpot() {
        val rooms = listOf(room(1, "映画"), room(2, "雑談"))
        assertEquals(listOf(1L), null.resolve(genre, rooms, RoomSearchCriteria(text = "映画")).results.map { it.id })
    }

    private fun room(id: Long, message: String) = Room(id, genre.key, RoomStatus.WAITING, RoomAction.ENTER,
        null, "合成$id", Gender.UNKNOWN, null, null, message)
}
