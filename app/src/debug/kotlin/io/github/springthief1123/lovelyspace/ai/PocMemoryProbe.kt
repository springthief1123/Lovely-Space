package io.github.springthief1123.lovelyspace.ai

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 実チャット本文・モデルの絶対パス・端末IDを一切記録しないプロセス観測。 */
internal data class PocMemorySample(
    val timeUtc: String,
    val runId: String,
    val phase: String,
    val screen: String,
    val elapsedMs: Long,
    val engineLoaded: Boolean,
    val pssKb: Int,
    val nativePssKb: Int,
    val dalvikPssKb: Int,
    val otherPssKb: Int,
    val privateDirtyKb: Int,
    val javaHeapKb: Long,
    val nativeHeapKb: Long,
    val processCpuMs: Long,
    val batteryPercent: Int?,
    val batteryTemperatureC: Double?,
    val thermalStatus: Int?,
)

internal object PocMemoryProbePolicy {
    const val CHAT_LIMIT_MS = 90_000L
    const val SAMPLE_INTERVAL_MS = 1_000L
    const val BACKGROUND_GRACE_MS = 5_000L
    val RELEASE_DELAYS_MS = listOf(0L, 250L, 1_000L, 3_000L)

    /** メイン画面以外を長く離れたら記録を終了。90秒を超える追跡はしない。 */
    fun shouldContinue(elapsedMs: Long, backgroundMs: Long?): Boolean =
        elapsedMs < CHAT_LIMIT_MS && (backgroundMs == null || backgroundMs < BACKGROUND_GRACE_MS)
}

internal object PocMemoryMetrics {
    fun read(
        context: Context,
        runId: String,
        phase: String,
        screen: String,
        startWallMs: Long,
        startCpuMs: Long,
    ): PocMemorySample {
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        val runtime = Runtime.getRuntime()
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
            }.getOrNull()
        } else null
        return PocMemorySample(
            timeUtc = Instant.now().toString(),
            runId = runId,
            phase = phase,
            screen = screen,
            elapsedMs = (SystemClock.elapsedRealtime() - startWallMs).coerceAtLeast(0),
            engineLoaded = PocModelSession.hasActiveEngine(),
            pssKb = memory.totalPss,
            nativePssKb = memory.nativePss,
            dalvikPssKb = memory.dalvikPss,
            otherPssKb = memory.otherPss,
            privateDirtyKb = memory.totalPrivateDirty,
            javaHeapKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024,
            nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024,
            processCpuMs = (Process.getElapsedCpuTime() - startCpuMs).coerceAtLeast(0),
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
            batteryTemperatureC = if (temperature >= 0) temperature / 10.0 else null,
            thermalStatus = thermal,
        )
    }
}

/** ログはdebugのアプリ内部領域だけに保存し、明示操作以外で共有しない。 */
internal object PocMemoryLogs {
    private const val FILE_NAME = "poc-memory-lifecycle-v1.csv"
    private const val LIMIT_BYTES = 2 * 1024 * 1024L
    private const val HEADER = "utc,run_id,phase,screen,elapsed_ms,engine_loaded,pss_kb,native_pss_kb,dalvik_pss_kb,other_pss_kb,private_dirty_kb,java_heap_kb,native_heap_kb,process_cpu_ms,battery_percent,battery_temperature_c,thermal_status"

    private fun file(context: Context) = File(File(context.filesDir, "ai-poc"), FILE_NAME)
    fun size(context: Context): Long = file(context).takeIf { it.isFile }?.length() ?: 0
    fun delete(context: Context): Boolean = !file(context).exists() || file(context).delete()

    internal fun serialize(sample: PocMemorySample): String = PocLogs.csv(listOf(
        sample.timeUtc, sample.runId, sample.phase, sample.screen, sample.elapsedMs,
        sample.engineLoaded, sample.pssKb, sample.nativePssKb,
        sample.dalvikPssKb, sample.otherPssKb, sample.privateDirtyKb,
        sample.javaHeapKb, sample.nativeHeapKb, sample.processCpuMs,
        sample.batteryPercent, sample.batteryTemperatureC, sample.thermalStatus,
    ))

    @Synchronized
    fun append(context: Context, sample: PocMemorySample) {
        val csv = serialize(sample)
        val target = file(context)
        check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true)
        check(size(context) + csv.toByteArray(Charsets.UTF_8).size + HEADER.length < LIMIT_BYTES) {
            "メモリ計測ログが2MiBに達しました。書き出し後に削除してください"
        }
        OutputStreamWriter(FileOutputStream(target, true), Charsets.UTF_8).use { writer ->
            if (target.length() == 0L) writer.write(HEADER + "\n")
            writer.write(csv)
        }
    }

    fun export(context: Context, uri: android.net.Uri) {
        val source = file(context)
        check(source.isFile) { "メモリ計測ログがありません" }
        val output = context.contentResolver.openOutputStream(uri) ?: error("書き出し先を開けません")
        output.use { dest -> source.inputStream().use { it.copyTo(dest) } }
    }
}

internal data class PocReleaseResult(
    val engineWasLoaded: Boolean,
    val engineLoadedAfter: Boolean,
    val pssBeforeKb: Int,
    val pssAfter3SecondsKb: Int,
    val runId: String,
)

/** 明示解放の直前/直後/0.25/1/3秒後の観測値を取得。測定のためにGCは強制しない。 */
internal suspend fun measurePocRelease(context: Context): PocReleaseResult = withContext(Dispatchers.IO) {
    val runId = UUID.randomUUID().toString()
    val wall = SystemClock.elapsedRealtime()
    val cpu = Process.getElapsedCpuTime()
    fun record(phase: String): PocMemorySample = PocMemoryMetrics.read(
        context, runId, phase, "poc", wall, cpu,
    ).also { PocMemoryLogs.append(context, it) }
    val before = record("release_before")
    try {
        PocModelSession.releaseNow()
    } catch (t: Throwable) {
        record("release_failed")
        throw t
    }
    record("release_after_0")
    var previous = 0L
    var finalSample = before
    for (offset in PocMemoryProbePolicy.RELEASE_DELAYS_MS.drop(1)) {
        delay(offset - previous)
        previous = offset
        finalSample = record("release_after_${offset}ms")
    }
    PocReleaseResult(
        engineWasLoaded = before.engineLoaded,
        engineLoadedAfter = finalSample.engineLoaded,
        pssBeforeKb = before.pssKb,
        pssAfter3SecondsKb = finalSample.pssKb,
        runId = runId,
    )
}

/**
 * ユーザーが通常画面を操作中、最大90秒だけ同一プロセスを監視。
 * AI推論・通信・ポーリングは行わない。チャット本文、URL、スクリーン情報は記録しない。
 */
internal object PocChatCoexistenceProbe {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var screen: String = "poc"
    @Volatile private var backgroundSinceMs: Long? = null
    @Volatile private var hasVisitedMain = false

    fun isRunning(): Boolean = synchronized(lock) { job?.isActive == true }

    fun start(context: Context): Boolean {
        val app = context.applicationContext as Application
        synchronized(lock) {
            if (job?.isActive == true) return false
            screen = "poc"
            backgroundSinceMs = null
            hasVisitedMain = false
            val runId = UUID.randomUUID().toString()
            val wall = SystemClock.elapsedRealtime()
            val cpu = Process.getElapsedCpuTime()
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    val next = when (activity) {
                        is io.github.springthief1123.lovelyspace.MainActivity -> "main"
                        is EmbeddingPocActivity -> "poc"
                        else -> "other"
                    }
                    screen = next
                    backgroundSinceMs = null
                    if (next == "main") hasVisitedMain = true
                    if (next == "poc" && hasVisitedMain) stop()
                }
                override fun onActivityPaused(activity: Activity) {
                    screen = "background"
                    backgroundSinceMs = SystemClock.elapsedRealtime()
                }
                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
            app.registerActivityLifecycleCallbacks(callbacks)
            job = scope.launch {
                try {
                    var last = ""
                    while (isActive) {
                        val now = SystemClock.elapsedRealtime()
                        val elapsed = now - wall
                        val backgroundMs = backgroundSinceMs?.let { (now - it).coerceAtLeast(0) }
                        if (!PocMemoryProbePolicy.shouldContinue(elapsed, backgroundMs)) break
                        val current = screen
                        val phase = if (current == last) "coexistence_sample" else "coexistence_transition"
                        last = current
                        val sample = PocMemoryMetrics.read(app, runId, phase, current, wall, cpu)
                        PocMemoryLogs.append(app, sample)
                        delay(PocMemoryProbePolicy.SAMPLE_INTERVAL_MS)
                    }
                } finally {
                    app.unregisterActivityLifecycleCallbacks(callbacks)
                    // 旧計測の終了が新しい計測のJob参照を消さないようにする。
                    val ended = coroutineContext[Job]
                    synchronized(lock) { if (job === ended) job = null }
                }
            }
            return true
        }
    }

    fun stop() {
        synchronized(lock) { job?.cancel() }
    }
}
