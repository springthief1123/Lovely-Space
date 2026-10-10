package io.github.springthief1123.lovelyspace.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PocBenchmarkTest {
    @Test fun corpusIsSyntheticUniqueAndHasExpectedSizes() {
        assertEquals(100, PocCorpus.all.size)
        assertEquals(100, PocCorpus.all.toSet().size)
        assertEquals(5, PocCorpus.take(5).size)
        assertEquals(30, PocCorpus.take(30).size)
        assertEquals(100, PocCorpus.take(100).size)
        assertTrue(PocCorpus.take(30).contains("5分だけ暇つぶししよう"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedSize() {
        PocCorpus.take(3)
    }

    @Test fun keywordComparisonDifferentiatesAndOr() {
        val texts = listOf("静かな雑談をゆっくり", "ゆっくり読書", "雑談だけ")
        val words = PocKeywordBaseline.terms("雑談、ゆっくり,雑談")
        assertEquals(listOf("雑談", "ゆっくり"), words)
        assertEquals(1, PocKeywordBaseline.matchedCount(texts, words, requireAll = true))
        assertEquals(3, PocKeywordBaseline.matchedCount(texts, words, requireAll = false))
        assertFalse(PocKeywordBaseline.isMatch("雑談だけ", emptyList(), requireAll = false))
    }

    @Test fun cachedDocumentsCanBeReusedAndCleared() {
        val text = PocCorpus.all.first()
        try {
            PocVectorCache.clear()
            assertNull(PocVectorCache.get(text))
            PocVectorCache.put(text, floatArrayOf(0.1f, 0.2f))
            assertNotNull(PocVectorCache.get(text))
            assertEquals(1, PocVectorCache.size())
            PocVectorCache.clear()
            assertNull(PocVectorCache.get(text))
        } finally {
            PocVectorCache.clear()
        }
    }

    @Test fun csvEscapesSpecialCharactersAndDoesNotNeedRawQuery() {
        assertEquals("\"A,B\",\"C\"\"D\",\"E F\"\n",
            PocLogs.csv(listOf("A,B", "C\"D", "E\nF")))
    }
}
