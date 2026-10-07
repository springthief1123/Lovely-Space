package io.github.springthief1123.lovelyspace.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * 背景での一覧取得の登録。背景で動く機能（定期巡回・順番待ちなど）が 1 つも有効でなければ登録しない。
 * 周期は WorkManager の最短（15 分）以上で、1 回の実行で取得するページ数も [RadarSyncWorker.MAX_PAGES] に抑える。
 */
object BackgroundSync {
    const val PERIODIC = "radar-sync"
    const val ONCE = "radar-sync-once"
    const val INTERVAL_MINUTES = 15L

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** 背景機能の有効・無効に合わせて周期実行を登録・解除する。 */
    fun update(context: Context, enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!enabled) {
            work.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<RadarSyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** 1 回だけ実行する。すでに待っている実行があれば重ねない。 */
    fun runOnce(context: Context) {
        val request = OneTimeWorkRequestBuilder<RadarSyncWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, request)
    }
}

/** 前面の巡回と同じ `RadarRepository.scan`（＝ ListSync）で、新着の先頭ページだけを確認する。 */
class RadarSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val radar = (applicationContext as LovelySpaceApp).radar
        val state = withTimeoutOrNull(LOAD_TIMEOUT_MS) { radar.state.first { it.loaded || it.error != null } }
        if (state == null || !state.loaded) return Result.retry()
        // 前面で巡回中なら、同じページを二重に取らないよう今回は見送る。
        if (state.running) return Result.success()
        return try {
            radar.scan(latestFirst = true, force = false, maxPages = MAX_PAGES)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        /** 1 回の実行で取得するページ数の上限。 */
        const val MAX_PAGES = 3
        private const val LOAD_TIMEOUT_MS = 30_000L
    }
}
