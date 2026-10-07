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
import io.github.springthief1123.lovelyspace.notify.toMatchNotification
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
    /** WorkManager の最短周期。これより短くしない。 */
    const val INTERVAL_MINUTES = 15L

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * 背景機能の有効・無効と間隔に合わせて周期実行を登録・解除する。
     * 同じ設定で何度呼んでも周期はやり直さない（UPDATE は登録済みの実行の時刻を引き継ぐ）。
     */
    fun update(context: Context, enabled: Boolean, intervalMinutes: Long = INTERVAL_MINUTES) {
        val work = WorkManager.getInstance(context)
        if (!enabled) {
            work.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<RadarSyncWorker>(intervalMinutes.coerceAtLeast(INTERVAL_MINUTES), TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** 1 回だけ実行する。すでに待っている実行があれば重ねない。 */
    fun runOnce(context: Context) {
        val request = OneTimeWorkRequestBuilder<RadarSyncWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, request)
    }
}

/**
 * 前面の巡回と同じ `RadarRepository.scan`（＝ ListSync）で、背景で巡回する計画の新着の先頭ページだけを確認し、
 * 新しく一致した部屋を通知する。取得できなかったページは次の周期で同じページを確認する（すぐには再試行しない）。
 */
class RadarSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LovelySpaceApp
        val radar = app.radar
        val state = withTimeoutOrNull(LOAD_TIMEOUT_MS) { radar.state.first { it.loaded || it.error != null } }
        if (state == null || !state.loaded) return Result.retry()
        // 計画がすべて止まっていれば何も取得しない（登録の解除は LovelySpaceApp が行う）。
        if (state.activeBackgroundPlans.isEmpty()) return Result.success()
        // 前面で巡回中なら、同じページを二重に取らないよう今回は見送る。
        if (state.running) return Result.success()
        return try {
            radar.scan(latestFirst = true, force = false, maxPages = MAX_PAGES, backgroundOnly = true)
            // 一致は履歴に「未通知」として保存されている。前回の実行が通知の途中で止まった分もここで出す
            // （同じ ID の通知・お知らせは置き換わるので、二重には並ばない）。
            val pending = radar.pendingNotices()
            pending.forEach { event -> event.toMatchNotification()?.let { app.notifier.post(it) } }
            radar.markNoticed(pending.map { it.id }.toSet())
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
