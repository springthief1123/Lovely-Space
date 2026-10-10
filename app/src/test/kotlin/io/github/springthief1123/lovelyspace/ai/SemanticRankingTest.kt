package io.github.springthief1123.lovelyspace.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticRankingTest {
    @Test fun relatedDocumentsComeFirst() {
        val ranked = SemanticRanking.rank(
            floatArrayOf(1f, 0f),
            listOf("反対" to floatArrayOf(-1f, 0f), "近い" to floatArrayOf(3f, 0f), "無関係" to floatArrayOf(0f, 1f)),
        )
        assertEquals(listOf("近い", "無関係", "反対"), ranked.map { it.text })
        assertEquals(1.0, ranked.first().similarity, 0.00001)
        assertEquals(-1.0, ranked.last().similarity, 0.00001)
    }

    @Test fun tiesRetainInputOrder() {
        val ranked = SemanticRanking.rank(
            floatArrayOf(1f, 1f),
            listOf("先" to floatArrayOf(2f, 2f), "後" to floatArrayOf(1f, 1f)),
        )
        assertEquals(listOf("先", "後"), ranked.map { it.text })
        assertEquals(listOf(0, 1), ranked.map { it.originalIndex })
    }

    @Test fun emptyDocumentsAreAllowed() {
        assertTrue(SemanticRanking.rank(floatArrayOf(1f), emptyList()).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyQuery() {
        SemanticRanking.rank(floatArrayOf(), emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroVector() {
        SemanticRanking.rank(floatArrayOf(1f, 0f), listOf("空" to floatArrayOf(0f, 0f)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteVector() {
        SemanticRanking.rank(floatArrayOf(Float.NaN), emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMismatchedDimensions() {
        SemanticRanking.rank(floatArrayOf(1f, 2f), listOf("不正" to floatArrayOf(1f)))
    }
}
