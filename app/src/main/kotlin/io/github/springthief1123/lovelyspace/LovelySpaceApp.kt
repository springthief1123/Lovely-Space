package io.github.springthief1123.lovelyspace

import android.app.Application
import androidx.room.Room
import io.github.springthief1123.lovelyspace.data.PresetDatabase
import io.github.springthief1123.lovelyspace.data.PresetRepository
import io.github.springthief1123.lovelyspace.data.RoomListRepository
import io.github.springthief1123.lovelyspace.data.RoomPreferenceRepository
import io.github.springthief1123.lovelyspace.data.SearchPresetRepository
import io.github.springthief1123.lovelyspace.core.ShaloveClient
import io.github.springthief1123.lovelyspace.core.SharedSiteCookieJar
import io.github.springthief1123.lovelyspace.settings.SettingsRepository
import io.github.springthief1123.lovelyspace.settings.WebViewCookieStore
import io.github.springthief1123.lovelyspace.notify.AppNotifier
import io.github.springthief1123.lovelyspace.notify.NotificationInbox
import io.github.springthief1123.lovelyspace.notify.NotificationInboxStore
import io.github.springthief1123.lovelyspace.ui.chat.ActiveRooms
import io.github.springthief1123.lovelyspace.background.BackgroundSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

class LovelySpaceApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        notifier.ensureChannels()
        // 背景で巡回する計画があるときだけ周期実行を登録し、すべて止めたら解除する。
        appScope.launch {
            radar.state.mapNotNull { state -> if (state.loaded) state.activeBackgroundPlans.isNotEmpty() to state.backgroundIntervalMinutes else null }
                .distinctUntilChanged()
                .collect { (enabled, minutes) -> BackgroundSync.update(this@LovelySpaceApp, enabled, minutes.toLong()) }
        }
    }


    /** 本家への通信はアプリ全体でこの 1 つを共有し、アクセス間隔の制限を一元化する。 */
    val client: ShaloveClient by lazy {
        ShaloveClient(ShaloveClient.defaultHttpClient(SharedSiteCookieJar(WebViewCookieStore())))
    }
    val roomLists: RoomListRepository by lazy { RoomListRepository(client) }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
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
    val notifier: AppNotifier by lazy { AppNotifier(this, notificationInbox) { settings.notificationPreview.first() } }

    /** 入室中の部屋（pwd を route に載せないための置き場）。進行中の部屋は暗号化して端末に残す。 */
    val activeRooms: ActiveRooms by lazy {
        ActiveRooms(io.github.springthief1123.lovelyspace.data.ActiveRoomStore(
            getSharedPreferences(io.github.springthief1123.lovelyspace.data.ActiveRoomStore.PREFS, MODE_PRIVATE),
            io.github.springthief1123.lovelyspace.data.KeystoreSecretBox(),
        ))
    }
}
