package io.github.springthief1123.lovelyspace.ai

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocDiskCacheQualityTest {
    private val model = "a".repeat(64)
    private val corpus = "b".repeat(64)
    private fun tmp(): File = File(Files.createTempDirectory("lovely-vector-test").toFile(), "vectors.bin")

    @Test fun roundTripAndChangeInvalidation() {
        val file = tmp()
        try {
            val key = PocDiskCache.hashString(PocCorpus.all[0])
            val saved = floatArrayOf(0.1f, -0.2f, 0.4f)
            PocDiskCache.write(file, model, corpus, mapOf(key to saved))
            assertArrayEquals(saved, PocDiskCache.read(file, model, corpus).getValue(key), 0.00001f)
            assertTrue(PocDiskCache.read(file, "c".repeat(64), corpus).isEmpty())
            assertTrue(PocDiskCache.read(file, model, "d".repeat(64)).isEmpty())
            assertEquals(1, PocDiskCache.read(file, model, corpus).size)
        } finally { file.delete(); file.parentFile?.delete() }
    }

    @Test fun detectsCorruptionAndTruncation() {
        val file = tmp()
        try {
            val key = PocDiskCache.hashString(PocCorpus.all[1])
            PocDiskCache.write(file, model, corpus, mapOf(key to floatArrayOf(1f, 2f)))
            val damaged = file.readBytes()
            damaged[damaged.size / 2] = (damaged[damaged.size / 2].toInt() xor 0x40).toByte()
            file.writeBytes(damaged)
            assertTrue(PocDiskCache.read(file, model, corpus).isEmpty())
            file.writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(PocDiskCache.read(file, model, corpus).isEmpty())
        } finally { file.delete(); file.parentFile?.delete() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteEmbedding() {
        val file = tmp()
        try {
            PocDiskCache.write(file, model, corpus,
                mapOf(PocDiskCache.hashString(PocCorpus.all[0]) to floatArrayOf(Float.NaN)))
        } finally { file.delete(); file.parentFile?.delete() }
    }

    @Test fun modelHashDependsOnContents() {
        val file = tmp()
        try {
            file.writeText("model A")
            val a = PocDiskCache.hashFile(file)
            file.writeText("model B")
            val b = PocDiskCache.hashFile(file)
            assertEquals(64, a.length)
            assertFalse(a == b)
        } finally { file.delete(); file.parentFile?.delete() }
    }

    @Test fun qualityMetricsAreDeterministic() {
        val ranked = listOf(8, 2, 7, 3, 9, 0, 1, 4, 5, 6)
        val metric = PocQuality.metric(ranked, setOf(2, 3, 7, 4))
        assertEquals(3.0 / 5, metric.precisionAt5, 0.000001)
        assertEquals(1.0, metric.recallAt10, 0.000001)
        assertEquals(0.5, metric.mrrAt10, 0.000001)
    }

    @Test fun qualityLabelsUseValidDistinctCorpusIndices() {
        assertEquals(7, PocQuality.cases.size)
        assertEquals(7, PocQuality.cases.map { it.id }.distinct().size)
        assertTrue(PocQuality.cases.all { c ->
            c.relevantIndexes.isNotEmpty() && c.relevantIndexes.all { it in PocCorpus.all.indices }
        })
        assertEquals(100, PocQuality.literalRanking(PocQuality.cases[0]).size)
    }
}
