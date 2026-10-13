package io.github.springthief1123.lovelyspace.ai

import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.core.RoomListPage
import io.github.springthief1123.lovelyspace.core.RoomQuery
import io.github.springthief1123.lovelyspace.core.RoomStatus
import io.github.springthief1123.lovelyspace.data.ObservedRoomPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExistingSnapshotHybridTest {
    private fun room(id: Long, genre: String, message: String): Room = Room(
        id = id,
        genreKey = genre,
        status = RoomStatus.WAITING,
        action = RoomAction.ENTER,
        elapsed = null,
        name = "検索対象外の名前",
        gender = Gender.UNKNOWN,
        age = 20,
        area = "架空の地域",
        message = message,
    )

    private fun observed(at: Long, genre: String, vararg rooms: Room): ObservedRoomPage =
        ObservedRoomPage(
            query = RoomQuery(genre = requireNotNull(Genres[genre])),
            page = RoomListPage(
                genreKey = genre,
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

    @Test fun isolatesGenreAndKeepsLatestGenreFirst() {
        val now = 5_000_000L
        val pages = listOf(
            observed(now - 20_000L, "talk", room(1, "talk", "雑談したい")),
            observed(now - 10_000L, "chah", room(2, "chah", "カカオで連絡")),
        )
        assertEquals(listOf("chah", "talk"), ExistingRoomSnapshot.availableGenres(pages, now).map { it.key })
        assertEquals(listOf("カカオで連絡"), ExistingRoomSnapshot.select(pages, now, "chah"))
        assertEquals(listOf("雑談したい"), ExistingRoomSnapshot.select(pages, now, "talk"))
    }

    @Test fun doesNotOfferStaleOrMismatchedGenre() {
        val now = 5_000_000L
        val pages = listOf(
            observed(now - ExistingRoomSnapshot.MAX_AGE_MS - 1L, "chah", room(1, "chah", "古い")),
            observed(now, "talk", room(2, "chah", "別カテゴリーの部屋")),
        )
        assertTrue(ExistingRoomSnapshot.availableGenres(pages, now).isEmpty())
        assertTrue(ExistingRoomSnapshot.select(pages, now, "chah").isEmpty())
    }

    @Test fun literalMatchingDoesNotInventSynonyms() {
        assertTrue(ExistingRoomSnapshot.containsKeyword("ｶｶｵで待っています", "カカオ"))
        assertTrue(ExistingRoomSnapshot.containsKeyword("CHAT H", "chat h"))
        assertFalse(ExistingRoomSnapshot.containsKeyword("のんびり雑談", "カカオ"))
        assertFalse(ExistingRoomSnapshot.containsKeyword("チャH", "ちゃえっち"))
    }

    @Test fun shortQueryFallsBackButNaturalSentenceDoesNot() {
        assertEquals("カカオ", ExistingRoomSnapshot.comparisonKeyword(" カカオ ", ""))
        assertNull(ExistingRoomSnapshot.comparisonKeyword("ゆっくりお話しできる部屋を探しています", ""))
        assertEquals("チャH", ExistingRoomSnapshot.comparisonKeyword("長めの自然文で探す", " チャH "))
        assertTrue(runCatching {
            ExistingRoomSnapshot.comparisonKeyword("希望", "あ".repeat(41))
        }.isFailure)
    }

    @Test fun hybridSeparatesLiteralMatchesFromHighScoringUnrelatedItems() {
        val aiOrder = listOf(
            SemanticRanking.Result("無関係な雑談", 0.99, 0),
            SemanticRanking.Result("カカオで話そう", 0.71, 1),
            SemanticRanking.Result("ゲーム好き", 0.68, 2),
        )
        val result = ExistingRoomSnapshot.classify(aiOrder, "カカオ", "")
        assertEquals(listOf("カカオで話そう"), result.confirmed.map { it.text })
        assertEquals(listOf("無関係な雑談", "ゲーム好き"), result.aiOnly.map { it.text })
        assertEquals(1, result.aiTop10KeywordHits)
        assertEquals(0.99, result.highestCosine!!, 0.0001)
        assertEquals(0.68, result.lowestCosine!!, 0.0001)
    }

    @Test fun checksVectorDimensionsFiniteValuesAndDistinctFingerprints() {
        val valid = ExistingRoomSnapshot.inspectVectors(
            floatArrayOf(1f, 0f),
            listOf(
                "a" to floatArrayOf(0f, 1f),
                "b" to floatArrayOf(0f, 1f),
                "c" to floatArrayOf(1f, 0f),
            ),
        )
        assertEquals(2, valid.dimensions)
        assertEquals(2, valid.distinctDocumentFingerprints)
        assertTrue(runCatching {
            ExistingRoomSnapshot.inspectVectors(floatArrayOf(0f, 0f), listOf("a" to floatArrayOf(1f, 0f)))
        }.isFailure)
        assertTrue(runCatching {
            ExistingRoomSnapshot.inspectVectors(floatArrayOf(1f, 0f), listOf("a" to floatArrayOf(Float.NaN, 0f)))
        }.isFailure)
        assertTrue(runCatching {
            ExistingRoomSnapshot.inspectVectors(floatArrayOf(1f, 0f), listOf("a" to floatArrayOf(1f)))
        }.isFailure)
    }

    @Test fun noLiteralMatchDoesNotCountHighCosineAsConfirmed() {
        val ranked = listOf(SemanticRanking.Result("関係のない募集文", 0.95, 0))
        val result = ExistingRoomSnapshot.classify(ranked, "カカオ", "")
        assertTrue(result.confirmed.isEmpty())
        assertEquals(1, result.aiOnly.size)
        assertEquals(0, result.aiTop10KeywordHits)
        val natural = ExistingRoomSnapshot.classify(ranked, "のんびりと雑談できる相手とお話ししたい", "")
        assertNull(natural.keyword)
        assertTrue(natural.confirmed.isEmpty())
        assertEquals(1, natural.aiOnly.size)
    }
}
