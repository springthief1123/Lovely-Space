package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.RefreshPacing
import io.github.springthief1123.lovelyspace.core.RoomListPage
import io.github.springthief1123.lovelyspace.core.RoomQuery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HotPagePollerTest {
    private val kanto = Genres["kanto"]!!
    private val kinki = Genres["kinki"]!!

    /** 取得の種類（priority か）を数える合成の一覧。 */
    private class Lists : ObservedRoomListSource {
        val priority = mutableListOf<RoomQuery>()
        val plain = mutableListOf<RoomQuery>()
        override val observations = MutableStateFlow<Map<RoomQuery, ObservedRoomPage>>(emptyMap())
        private var revision = 0L
        override fun observation(query: RoomQuery) = observations.value[query]
        override suspend fun fetch(query: RoomQuery, force: Boolean): RoomListPage { plain += query; return record(query) }
        override suspend fun fetchPriority(query: RoomQuery, force: Boolean): RoomListPage { priority += query; return record(query) }
        private fun record(query: RoomQuery): RoomListPage {
            val page = RoomListPage(query.genre.key, emptyList(), null, null, query.page, 5, emptyMap(), null)
            observations.value = observations.value + (query to ObservedRoomPage(query, page, 0L, ++revision))
            return page
        }
    }

    @Test
    fun readsOnlyTheFirstPageOfWatchedGenresAtTheSetInterval() = runTest {
        val lists = Lists()
        val fetched = mutableListOf<RoomQuery>()
        val poller = HotPagePoller(lists, pacing = { RefreshPacing() }, onFetched = { fetched += it.query }, clock = { testScheduler.currentTime })
        val job = launch { poller.run() }
        runCurrent()
        // 見ているジャンルが無い間は読まない。
        assertEquals(emptyList<RoomQuery>(), lists.priority)
        poller.want("search", setOf(kanto))
        runCurrent()
        assertEquals(listOf(RoomQuery(kanto)), lists.priority)
        advanceTimeBy(2_900); runCurrent()
        assertEquals(1, lists.priority.size)
        advanceTimeBy(200); runCurrent()
        // 既定は 3 秒ごと。1 ページ目だけを、巡回より先に通す取得（priority）で読む。
        assertEquals(listOf(RoomQuery(kanto), RoomQuery(kanto)), lists.priority)
        assertEquals(emptyList<RoomQuery>(), lists.plain)
        assertEquals(lists.priority, fetched)
        job.cancel()
    }

    @Test
    fun genresFromSeveralOwnersAreReadInTurn() = runTest {
        val lists = Lists()
        val poller = HotPagePoller(lists, pacing = { RefreshPacing() }, clock = { testScheduler.currentTime })
        poller.want("search", setOf(kanto))
        poller.want("radar", setOf(kanto, kinki))
        assertEquals(setOf(kanto, kinki), poller.genres.value)
        val job = launch { poller.run() }
        runCurrent()
        assertEquals(setOf(RoomQuery(kanto), RoomQuery(kinki)), lists.priority.toSet())
        // 2 ジャンルなら 1 ジャンルあたり 6 秒ごと。
        advanceTimeBy(5_900); runCurrent()
        assertEquals(2, lists.priority.size)
        advanceTimeBy(200); runCurrent()
        assertEquals(4, lists.priority.size)
        // 見なくなったジャンルは読まない。
        poller.want("radar", emptySet())
        assertEquals(setOf(kanto), poller.genres.value)
        lists.priority.clear()
        advanceTimeBy(6_100); runCurrent()
        assertEquals(setOf(RoomQuery(kanto)), lists.priority.toSet())
        job.cancel()
    }
}
