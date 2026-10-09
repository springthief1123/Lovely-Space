package io.github.springthief1123.lovelyspace

import android.app.Application
import androidx.room.Room
import io.github.springthief1123.lovelyspace.data.PresetDatabase
import io.github.springthief1123.lovelyspace.data.PresetRepository
import io.github.springthief1123.lovelyspace.data.RoomListRepository
import io.github.springthief1123.lovelyspace.data.RoomPreferenceRepository
import io.github.springthief1123.lovelyspace.data.SearchPresetRepository
import io.github.springthief1123.lovelyspace.core.RefreshPacing
import io.github.springthief1123.lovelyspace.core.ShaloveClient
import io.github.springthief1123.lovelyspace.core.SharedSiteCookieJar
import io.github.springthief1123.lovelyspace.settings.SettingsRepository
import io.github.springthief1123.lovelyspace.settings.WebViewCookieStore
import io.github.springthief1123.lovelyspace.notify.AppNotifier
import io.github.springthief1123.lovelyspace.notify.NotificationInbox
import io.github.springthief1123.lovelyspace.notify.NotificationInboxStore
import io.github.springthief1123.lovelyspace.notify.toOpenedNotification
import io.github.springthief1123.lovelyspace.ui.chat.ActiveRooms
import io.github.springthief1123.lovelyspace.background.BackgroundSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class LovelySpaceApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        notifier.ensureChannels()
        // 通信の間隔の設定を、通信のたびに読める置き場へ写す。
        appScope.launch { settings.refreshPacing.collect { pacing.value = it } }
        // 前回の実行が空きの通知を出す前に止まっていれば、起動時に出し直す。
        appScope.launch { waitlist.deliverPending() }
        // 背景で巡回する計画か、待っている（または通知を出し終えていない）順番待ちがあるときだけ周期実行を登録し、
        // どちらも無くなれば解除する。順番待ちがあるときは最短の 15 分ごとに確認する。
        appScope.launch {
            combine(radar.state, waitlist.entries) { state, _ ->
                // レーダーの読み込み中は判断を待つ。読み込めなかったときは、レーダーの計画は無いものとして順番待ちだけで決める。
                if (!state.loaded && state.error == null) return@combine null
                val plans = state.loaded && state.activeBackgroundPlans.isNotEmpty()
                val waiting = waitlist.hasPendingWork()
                (plans || waiting) to
                    if (waiting || !state.loaded) BackgroundSync.INTERVAL_MINUTES else state.backgroundIntervalMinutes.toLong()
            }.filterNotNull()
                .distinctUntilChanged()
                .collect { (enabled, minutes) -> BackgroundSync.update(this@LovelySpaceApp, enabled, minutes) }
        }
    }


    /** 通信の間隔の設定（設定画面で選ぶ）。読み込むまでは既定値。 */
    val pacing = MutableStateFlow(RefreshPacing())

    /** 本家への通信はアプリ全体でこの 1 つを共有し、アクセス間隔の制限を一元化する。 */
    val client: ShaloveClient by lazy {
        ShaloveClient(ShaloveClient.defaultHttpClient(SharedSiteCookieJar(WebViewCookieStore())), pacing = { pacing.value })
    }
    val roomLists: RoomListRepository by lazy { RoomListRepository(client) }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    /** アプリロック。起動直後に画面を隠せるよう、設定は同期で読む。 */
    val appLock: io.github.springthief1123.lovelyspace.lock.AppLockController by lazy {
        io.github.springthief1123.lovelyspace.lock.AppLockController(
            io.github.springthief1123.lovelyspace.lock.AppLockStore(getSharedPreferences(io.github.springthief1123.lovelyspace.lock.AppLockStore.PREFS, MODE_PRIVATE)))
    }
    private val database: PresetDatabase by lazy {
        Room.databaseBuilder(this, PresetDatabase::class.java, "lovely-space.db")
            .addMigrations(PresetDatabase.MIGRATION_1_2, PresetDatabase.MIGRATION_2_3, PresetDatabase.MIGRATION_3_4)
            .build()
    }
    val presets: PresetRepository by lazy { PresetRepository(database, settings) }
    val searchPresets: SearchPresetRepository by lazy { SearchPresetRepository(database) }
    val radar: io.github.springthief1123.lovelyspace.data.RadarRepository by lazy {
        io.github.springthief1123.lovelyspace.data.RadarRepository(database.presets(), roomLists, searchPresets, roomPreferences)
    }
    val roomPreferences: RoomPreferenceRepository by lazy { RoomPreferenceRepository(database) }

    /** お知らせ（通知ベル）の履歴。端末通知と同じ内容を残す。 */
    val notificationInbox: NotificationInbox by lazy {
        NotificationInbox(NotificationInboxStore(getSharedPreferences(NotificationInboxStore.PREFS, MODE_PRIVATE)))
    }
    /** 順番待ち。空きを見つけたら端末通知とお知らせに出す。 */
    val waitlist: io.github.springthief1123.lovelyspace.data.WaitlistRepository by lazy {
        io.github.springthief1123.lovelyspace.data.WaitlistRepository(
            io.github.springthief1123.lovelyspace.data.WaitlistStore(getSharedPreferences(io.github.springthief1123.lovelyspace.data.WaitlistStore.PREFS, MODE_PRIVATE)),
            roomLists,
            onOpened = { entry -> entry.toOpenedNotification()?.let { notifier.post(it) } },
        )
    }
    val notifier: AppNotifier by lazy { AppNotifier(this, notificationInbox) { settings.notificationPreview.first() } }

    /** 入室中の部屋（pwd を route に載せないための置き場）。進行中の部屋は暗号化して端末に残す。 */
    val activeRooms: ActiveRooms by lazy {
        ActiveRooms(io.github.springthief1123.lovelyspace.data.ActiveRoomStore(
            getSharedPreferences(io.github.springthief1123.lovelyspace.data.ActiveRoomStore.PREFS, MODE_PRIVATE),
            io.github.springthief1123.lovelyspace.data.KeystoreSecretBox(),
        ))
    }
}
