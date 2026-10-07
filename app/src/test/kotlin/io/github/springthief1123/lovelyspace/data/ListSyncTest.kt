package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.RoomListPage
import io.github.springthief1123.lovelyspace.core.RoomQuery
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListSyncTest {
    private val zenkoku = RoomQuery(Genres["zenkoku"]!!, page = 1)
    private val kanto = RoomQuery(Genres["kanto"]!!, page = 1)
    private val zenkoku2 = zenkoku.copy(page = 2)

    private class CountingSource(private val failing: Set<RoomQuery> = emptySet()) : RoomListSource {
        val calls = mutableListOf<RoomQuery>()
        override suspend fun fetch(query: RoomQuery, force: Boolean): RoomListPage {
            calls += query
            if (query in failing) throw java.io.IOException("合成の通信失敗")
            return RoomListPage(query.genre.key, emptyList(), null, null, query.page, 3, emptyMap(), null)
        }
    }

    @Test fun samePageRequestedByManyFeaturesIsFetchedOnce() = runTest {
        val source = CountingSource()
        // 巡回・候補・追跡がそれぞれ同じページを求めても、取得は 1 回。
        val outcomes = ListSync(source).sync(listOf(zenkoku, kanto, zenkoku, zenkoku2, kanto))
        assertEquals(listOf(zenkoku, kanto, zenkoku2), source.calls)
        assertEquals(3, outcomes.size)
        assertTrue(outcomes.values.all { it is ListSyncOutcome.Fetched })
    }

    @Test fun pagesBeyondTheLimitAreDeferred() = runTest {
        val source = CountingSource()
        val outcomes = ListSync(source).sync(listOf(zenkoku, kanto, zenkoku2), maxPages = 2)
        assertEquals(listOf(zenkoku, kanto), source.calls)
        assertEquals(ListSyncOutcome.Deferred, outcomes[zenkoku2])
    }

    @Test fun skippedPagesAreNotFetchedAndDoNotUseTheLimit() = runTest {
        val source = CountingSource()
        val outcomes = ListSync(source).sync(listOf(zenkoku, kanto, zenkoku2), maxPages = 2, shouldFetch = { it != zenkoku })
        assertEquals(listOf(kanto, zenkoku2), source.calls)
        assertEquals(ListSyncOutcome.Skipped, outcomes[zenkoku])
    }

    @Test fun failureOfOnePageDoesNotStopTheOthers() = runTest {
        val source = CountingSource(failing = setOf(zenkoku))
        val seen = mutableListOf<Pair<RoomQuery, ListSyncOutcome>>()
        ListSync(source).sync(listOf(zenkoku, kanto), onResult = { q, o -> seen += q to o })
        assertTrue(seen[0].second is ListSyncOutcome.Failed)
        assertTrue(seen[1].second is ListSyncOutcome.Fetched)
    }
}
