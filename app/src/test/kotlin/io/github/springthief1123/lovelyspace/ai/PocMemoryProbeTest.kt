package io.github.springthief1123.lovelyspace.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocMemoryProbeTest {
    @Test fun chatProbeStopsAt90SecondsAndAfterBackgroundGrace() {
        assertTrue(PocMemoryProbePolicy.shouldContinue(0, null))
        assertTrue(PocMemoryProbePolicy.shouldContinue(89_999L, null))
        assertFalse(PocMemoryProbePolicy.shouldContinue(90_000L, null))
        assertTrue(PocMemoryProbePolicy.shouldContinue(5_000L, 4_999L))
        assertFalse(PocMemoryProbePolicy.shouldContinue(5_000L, 5_000L))
        assertTrue(PocMemoryProbePolicy.shouldContinue(0, 0))
    }

    @Test fun releaseMeasurementsAreBoundedAndIncreasing() {
        val values = PocMemoryProbePolicy.RELEASE_DELAYS_MS
        assertEquals(listOf(0L, 250L, 1_000L, 3_000L), values)
        assertEquals(values.size, values.distinct().size)
        assertTrue(values.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun serializedCsvIncludesOnlyNumericMetricsAndControlledLabels() {
        val sample = PocMemorySample(
            timeUtc = "2026-10-11T00:00:00Z",
            runId = "synthetic-run",
            phase = "release_after_1000ms",
            screen = "poc",
            elapsedMs = 1_030L,
            engineLoaded = false,
            pssKb = 410_000,
            nativePssKb = 250_000,
            dalvikPssKb = 80_000,
            otherPssKb = 80_000,
            privateDirtyKb = 300_000,
            javaHeapKb = 35_000,
            nativeHeapKb = 250_000L,
            processCpuMs = 120L,
            batteryPercent = 65,
            batteryTemperatureC = 36.9,
            thermalStatus = 0,
        )
        val row = PocMemoryLogs.serialize(sample)
        assertEquals(17, row.trimEnd().split(",").size)
        assertTrue(row.contains("\"release_after_1000ms\""))
        assertTrue(row.contains("\"false\""))
        assertTrue(row.contains("\"410000\""))
        assertFalse(row.contains("chat.shalove.net"))
        assertFalse(row.contains("pwd="))
    }
}
