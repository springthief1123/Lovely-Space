package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.NgWordFilter
import io.github.springthief1123.lovelyspace.core.NgWordSet
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.core.RoomSearchCriteria
import io.github.springthief1123.lovelyspace.core.RoomStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SearchResultsTest {
    private val genre = Genres.all[0]
    private val other = Genres.all[1]

    @Test fun listsArrivingFasterThanTheFilterStillGetFilteredResults() = runTest {
        // 一覧が 50 ミリ秒ごとに届き、絞り込みに 120 ミリ秒かかっても、結果は途中で出て、最後は最新の一覧の結果になる。
        val lists = MutableStateFlow(0)
        val shown = mutableListOf<Int>()
        val job = launch { lists.finishThenLatest({ false }, { n -> delay(120); n }) { shown += it } }
        repeat(20) { delay(50); lists.value = it + 1 }
        advanceUntilIdle()
        job.cancel()
        assertTrue("途中の結果が出ていない: $shown", shown.size >= 5)
        assertEquals(20, shown.last())
        assertEquals(shown.sorted(), shown)
    }

    @Test fun inputsAlreadyComputedAreSkipped() = runTest {
        val lists = MutableStateFlow(1)
        val computed = mutableListOf<Int>()
        val job = launch { lists.finishThenLatest({ it == 1 }, { n -> computed += n; n }) { } }
        advanceUntilIdle()
        lists.value = 2
        advanceUntilIdle()
        job.cancel()
        assertEquals(listOf(2), computed)
    }

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

    @Test fun ngWordsSplitMatchesIntoShownAndExcludedAndChangingThemRecomputes() {
        val rooms = listOf(room(1, "映画"), room(2, "映画 PayPay"), room(3, "雑談"))
        val ng = NgWordFilter.of(NgWordSet.BUILT_IN)
        val shown = SearchResults.of(genre, rooms, RoomSearchCriteria(text = "映画"), ng)
        assertEquals(listOf(1L), shown.results.map { it.id })
        assertEquals(listOf(2L), shown.excluded.map { it.room.id })
        assertEquals("PayPay", shown.excluded.single().match.word)
        // NG ワードを外した（今だけ外すを含む）ら、前の結果を出さずに計算し直す。
        val resolved = shown.resolve(genre, listOf(rooms[0], rooms[1]), RoomSearchCriteria(text = "映画"), NgWordFilter.NONE)
        assertEquals(listOf(1L, 2L), resolved.results.map { it.id })
        assertEquals(emptyList<Any>(), resolved.excluded)
    }

    @Test fun oldFilteredResultsImmediatelyShowTheLatestFullStatusAndDisableEntry() {
        val waiting = room(1, "合成の募集文")
        val shown = SearchResults.of(genre, listOf(waiting), RoomSearchCriteria())
        // 計算中は古い検索結果を保持するが、カードの状態と操作には最新の部屋を使う。
        val full = waiting.copy(status = RoomStatus.FULL, action = RoomAction.NONE, name = null)
        val resolved = shown.resolve(genre, listOf(full), RoomSearchCriteria())
        assertSame(shown, resolved)
        assertEquals(full, resolved.currentCards(listOf(full)).single())
        assertEquals(RoomAction.NONE, resolved.currentCards(listOf(full)).single().action)
        assertTrue(resolved.currentCards(emptyList()).isEmpty())
    }

    @Test fun changedRoomIdentityIsNotOperableWhileBackgroundFiltering() {
        val waiting = room(1, "合成の募集文")
        val shown = SearchResults.of(genre, listOf(waiting), RoomSearchCriteria())
        // 同じ ID を別人が使っていても、古い部屋のカードから操作させない。
        assertTrue(shown.currentCards(listOf(waiting.copy(name = "別の合成名"))).isEmpty())
        assertTrue(shown.currentCards(listOf(waiting.copy(name = null, message = "別の募集文"))).isEmpty())
    }

    @Test fun waitingOnlyFilterDoesNotExposeNewlyFullRoomDuringRecalculation() {
        val waiting = room(1, "合成")
        val criteria = RoomSearchCriteria(waitingOnly = true)
        val shown = SearchResults.of(genre, listOf(waiting), criteria)
        val full = waiting.copy(status = RoomStatus.FULL, action = RoomAction.PEEK, name = null)
        assertTrue(shown.currentCards(listOf(full)).isEmpty())
    }

    private fun room(id: Long, message: String) = Room(id, genre.key, RoomStatus.WAITING, RoomAction.ENTER,
        null, "合成$id", Gender.UNKNOWN, null, null, message)
}
