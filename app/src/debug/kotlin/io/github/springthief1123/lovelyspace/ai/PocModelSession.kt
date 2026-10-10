package io.github.springthief1123.lovelyspace.ai

import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal data class PocSessionRun<T>(
    val value: T,
    val initializationMs: Long,
    val reusedEngine: Boolean,
)

/**
 * 一時的なCPUモデル再利用の検証専用。SDKエンジンへのアクセスは直列化する。
 * 明示的な解放／画面背景化／低メモリ時は、実行中の処理が終わってから解放する。
 */
internal object PocModelSession {
    private val lock = Any()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var active: DebugEmbeddingEngine? = null
    private var activeKey: String? = null
    @Volatile private var releaseRequested = false

    fun <T> run(model: File, keepWarm: Boolean, action: (DebugEmbeddingEngine) -> T): PocSessionRun<T> =
        synchronized(lock) {
            releaseRequested = false
            val key = "${model.absolutePath}:${model.length()}:${model.lastModified()}"
            if (!keepWarm || activeKey != key) closeLocked()
            val reused = active != null
            val start = SystemClock.elapsedRealtime()
            val engine = active ?: DebugEmbeddingEngine(model.absolutePath).also {
                active = it
                activeKey = key
            }
            val initMs = if (reused) 0L else SystemClock.elapsedRealtime() - start
            try {
                PocSessionRun(action(engine), initMs, reused)
            } catch (e: Throwable) {
                closeLocked()
                throw e
            } finally {
                if (!keepWarm || releaseRequested) closeLocked()
            }
        }

    private fun closeLocked() {
        try {
            active?.close()
        } finally {
            active = null
            activeKey = null
        }
    }

    /** debug計測でのみ使用。SDKエンジンを保持しているかを同期して確認する。 */
    fun hasActiveEngine(): Boolean = synchronized(lock) { active != null }

    fun releaseNow() = synchronized(lock) {
        releaseRequested = true
        closeLocked()
    }

    /** UIスレッドをブロックしない。in-flight推論が完了すれば確実に解放される。 */
    fun releaseAsync() {
        releaseRequested = true
        cleanupScope.launch { releaseNow() }
    }
}
