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
import io.github.springthief1123.lovelyspace.data.ListSync
import io.github.springthief1123.lovelyspace.data.genreWindow
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
 * 新しく一致した部屋を通知する。続けて順番待ちの部屋（満室のピン）と空いているピンのページ、立て直しを待つピンのジャンルの 1 ページ目を確認する。
 * 取得できなかったページは次の周期で同じページを確認する（すぐには再試行しない）。
 */
class RadarSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LovelySpaceApp
        val radar = app.radar
        val state = withTimeoutOrNull(LOAD_TIMEOUT_MS) { radar.state.first { it.loaded || it.error != null } }
        return try {
            // 1 回の実行で取得するのは、背景の巡回と順番待ちを合わせて MAX_PAGES まで。
            // 順番待ちがあれば 1 ページは順番待ちに残し、巡回が使わなかった分も順番待ちに回す。
            val attemptsBefore = app.roomLists.fetchAttempts
            // ピンの様子（記録）を読み込んでから、空いている・立て直しを待つピンの読むページを決める。
            withTimeoutOrNull(LOAD_TIMEOUT_MS) { app.pins.loaded.first { it } }
            val rebuildPages = app.pins.rebuildPages()
            // 満室のピン（順番待ち）と空いているピンのページに 1 ページ、立て直しを待つピンの 1 ページ目に 1 ページを残す。
            val reserved = (if (app.waitlist.hasActive() || app.pins.openPages().isNotEmpty()) 1 else 0) +
                (if (rebuildPages.isNotEmpty()) 1 else 0)
            val radarPages = MAX_PAGES - reserved
            // レーダーを読み込めなかったときも、順番待ちの確認は続ける（順番待ちはレーダーとは別に保存している）。
            if (state != null && state.loaded) {
                // 前面で巡回中なら、同じページを二重に取らないよう今回の巡回は見送る。計画がすべて止まっていれば取得しない。
                if (state.activeBackgroundPlans.isNotEmpty() && !state.running) {
                    radar.scan(latestFirst = true, force = false, maxPages = radarPages, backgroundOnly = true)
                }
                // 一致は「未通知」として保存されている。前回の実行が通知の途中で止まった分もここで出す
                // （同じ ID の通知・お知らせは置き換わるので、二重には並ばない）。
                app.radarNotices.deliver()
            }
            // 順番待ちは待っている部屋のページだけを取得する。空きの通知は WaitlistRepository が出す。
            val used = (app.roomLists.fetchAttempts - attemptsBefore).toInt()
            val failed = app.waitlist.check((MAX_PAGES - used - if (rebuildPages.isNotEmpty()) 1 else 0).coerceAtLeast(0))
            // 立て直しを待つピンのジャンルの 1 ページ目。読めば立て直しの引き継ぎ（保存の記録の移動）とピンの見張りが反映する。
            // 順番待ちの取得が失敗していれば、障害中に続けて取りに行かない。
            if (!failed && rebuildPages.isNotEmpty()) {
                val left = (MAX_PAGES - (app.roomLists.fetchAttempts - attemptsBefore).toInt()).coerceAtLeast(0)
                ListSync(app.roomLists).sync(rebuildPages, left, force = false, priority = true)
                // 立て直していれば保存の記録を新しい部屋へ移し、ピンの見張りが引き継ぐのを待ってから知らせる（終わる前に止まらないように）。
                // 背景では古い部屋の載っていたページを読まないので、ピンの記録で閉じたと分かっている部屋として探す。
                val closed = app.pins.pins.value.filter { it.state == io.github.springthief1123.lovelyspace.core.PinState.CLOSED }.mapTo(HashSet()) { it.key }
                rebuildPages.forEach { query -> app.roomPreferences.followRebuilds(app.roomLists.observations.value.genreWindow(query.genre), closed) }
                withTimeoutOrNull(MOVE_TIMEOUT_MS) { app.roomPreferences.awaitPinMoves() }
            }
            // 読んだページでピンした部屋が空いた・似た部屋が建ったら、終わる前に知らせる。
            app.pins.refresh()
            if (state == null) Result.retry() else Result.success()
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
        private const val MOVE_TIMEOUT_MS = 10_000L
    }
}
