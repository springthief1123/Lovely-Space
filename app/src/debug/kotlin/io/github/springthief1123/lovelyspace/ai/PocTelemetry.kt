package io.github.springthief1123.lovelyspace.ai

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.util.UUID

/** Androidの公開APIで取得できるアプリプロセスの測定値。端末全体のCPU使用率ではない。 */
internal data class PocSample(
    val elapsedMs: Long,
    val processCpuMs: Long,
    val pssKb: Int,
    val javaHeapKb: Long,
)

internal data class PocResourceReport(
    val elapsedMs: Long,
    val processCpuMs: Long,
    val averageCpuCorePercent: Double,
    val peakSampledPssKb: Int,
    val finalPssKb: Int,
    val peakSampledHeapKb: Long,
    val batteryPercent: Int?,
    val batteryTemperatureC: Double?,
    val samples: List<PocSample>,
)

internal class PocTelemetry(private val context: Context) {
    private val wallStart = SystemClock.elapsedRealtime()
    private val cpuStart = Process.getElapsedCpuTime()
    private val samples = mutableListOf<PocSample>()

    fun sample() {
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        val runtime = Runtime.getRuntime()
        val snapshot = PocSample(
            elapsedMs = (SystemClock.elapsedRealtime() - wallStart).coerceAtLeast(0),
            processCpuMs = (Process.getElapsedCpuTime() - cpuStart).coerceAtLeast(0),
            pssKb = memory.totalPss,
            javaHeapKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024,
        )
        synchronized(samples) { samples += snapshot }
    }

    fun finish(): PocResourceReport {
        val rows = synchronized(samples) { samples.toList() }
        val last = rows.last()
        val elapsed = (SystemClock.elapsedRealtime() - wallStart).coerceAtLeast(1)
        val cpuMs = (Process.getElapsedCpuTime() - cpuStart).coerceAtLeast(0)
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return PocResourceReport(
            elapsedMs = elapsed,
            processCpuMs = cpuMs,
            // マルチコアでは100%を超えうる「CPUコア1個換算」の平均使用率。
            averageCpuCorePercent = cpuMs * 100.0 / elapsed,
            peakSampledPssKb = rows.maxOf { it.pssKb },
            finalPssKb = last.pssKb,
            peakSampledHeapKb = rows.maxOf { it.javaHeapKb },
            batteryPercent = if (level >= 0 && scale > 0) (level * 100 / scale) else null,
            batteryTemperatureC = if (temp >= 0) temp / 10.0 else null,
            samples = rows,
        )
    }
}

internal data class PocLogRecord(
    val timestampUtc: String,
    val id: String,
    val corpusSize: Int,
    val requireAll: Boolean,
    val keywordMatches: Int,
    val cacheHits: Int,
    val cacheMisses: Int,
    val initializationMs: Long,
    val embeddingMs: Long,
    val rankingMs: Long,
    val topSimilarity: Double?,
    val resources: PocResourceReport,
) {
    companion object {
        fun new(
            size: Int,
            requireAll: Boolean,
            keywordMatches: Int,
            cacheHits: Int,
            cacheMisses: Int,
            initializationMs: Long,
            embeddingMs: Long,
            rankingMs: Long,
            topSimilarity: Double?,
            resources: PocResourceReport,
        ) = PocLogRecord(
            Instant.now().toString(), UUID.randomUUID().toString(),
            size, requireAll, keywordMatches, cacheHits, cacheMisses,
            initializationMs, embeddingMs, rankingMs, topSimilarity, resources,
        )
    }
}

/** テキスト・検索文・端末識別IDを保存せず、数値データだけを端末内CSVに記録する。 */
internal object PocLogs {
    private const val FILE_NAME = "poc-performance.csv"
    private const val MAX_LOG_BYTES = 6 * 1024 * 1024L
    private const val HEADER = "utc,run_id,row_type,device_model,android_sdk,corpus_size,keyword_mode,keyword_hits,cache_hits,cache_misses,elapsed_ms,init_ms,embedding_ms,rank_ms,process_cpu_ms,avg_cpu_core_percent,pss_kb,sampled_peak_pss_kb,java_heap_kb,sampled_peak_heap_kb,battery_percent,battery_temperature_c,top_cosine_similarity"

    private fun file(context: Context) = File(File(context.filesDir, "ai-poc"), FILE_NAME)

    fun size(context: Context): Long = file(context).takeIf { it.isFile }?.length() ?: 0L
    fun exists(context: Context): Boolean = size(context) > 0L
    fun delete(context: Context): Boolean = !file(context).exists() || file(context).delete()

    /** 値は引用・改行を安全に処理する。自由入力の検索文は引数に持たない。 */
    internal fun csv(fields: List<Any?>): String = fields.joinToString(",") {
        val value = it?.toString() ?: ""
        "\"" + value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\""
    } + "\n"

    internal fun serialize(record: PocLogRecord): String = buildString {
        val r = record.resources
        val common = listOf<Any?>(
            record.timestampUtc, record.id, "summary", Build.MODEL, Build.VERSION.SDK_INT,
            record.corpusSize, if (record.requireAll) "AND" else "OR", record.keywordMatches,
            record.cacheHits, record.cacheMisses,
        )
        append(csv(common + listOf(
            r.elapsedMs, record.initializationMs, record.embeddingMs, record.rankingMs,
            r.processCpuMs, "%.2f".format(java.util.Locale.ROOT, r.averageCpuCorePercent),
            r.finalPssKb, r.peakSampledPssKb, r.samples.last().javaHeapKb, r.peakSampledHeapKb,
            r.batteryPercent, r.batteryTemperatureC, record.topSimilarity,
        )))
        for (s in r.samples) {
            append(csv(listOf<Any?>(
                record.timestampUtc, record.id, "sample", Build.MODEL, Build.VERSION.SDK_INT,
                record.corpusSize, if (record.requireAll) "AND" else "OR",
                null, null, null,
                s.elapsedMs, null, null, null, s.processCpuMs, null, s.pssKb, null,
                s.javaHeapKb, null, null, null, null,
            )))
        }
    }

    fun append(context: Context, record: PocLogRecord) {
        val target = file(context)
        check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true)
        val rows = serialize(record)
        check(size(context) + rows.toByteArray(Charsets.UTF_8).size < MAX_LOG_BYTES) {
            "ログが6MBに達しました。CSVを保存した後、ログを削除してください"
        }
        OutputStreamWriter(FileOutputStream(target, true), Charsets.UTF_8).use { out ->
            if (target.length() == 0L) out.write(HEADER + "\n")
            out.write(rows)
        }
    }

    fun export(context: Context, uri: Uri) {
        val source = file(context)
        check(source.isFile) { "保存したログがありません" }
        val output = context.contentResolver.openOutputStream(uri) ?: error("書き出し先を開けません")
        output.use { target -> source.inputStream().use { it.copyTo(target) } }
    }
}
