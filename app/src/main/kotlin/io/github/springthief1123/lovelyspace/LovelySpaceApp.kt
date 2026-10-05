package io.github.springthief1123.lovelyspace

import android.app.Application
import androidx.room.Room
import io.github.springthief1123.lovelyspace.data.RoomListRepository
import io.github.springthief1123.lovelyspace.data.PresetDatabase
import io.github.springthief1123.lovelyspace.data.PresetRepository
import io.github.springthief1123.lovelyspace.data.SearchPresetRepository
import io.github.springthief1123.lovelyspace.core.ShaloveClient
import io.github.springthief1123.lovelyspace.core.SharedSiteCookieJar
import io.github.springthief1123.lovelyspace.settings.SettingsRepository
import io.github.springthief1123.lovelyspace.settings.WebViewCookieStore
import io.github.springthief1123.lovelyspace.ui.chat.ActiveRooms

class LovelySpaceApp : Application() {
    /** 本家への通信はアプリ全体でこの 1 つを共有し、アクセス間隔の制限を一元化する。 */
    val client: ShaloveClient by lazy {
        ShaloveClient(ShaloveClient.defaultHttpClient(SharedSiteCookieJar(WebViewCookieStore())))
    }
    val roomLists: RoomListRepository by lazy { RoomListRepository(client) }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    private val database: PresetDatabase by lazy {
        Room.databaseBuilder(this, PresetDatabase::class.java, "lovely-space.db")
            .addMigrations(PresetDatabase.MIGRATION_1_2).build()
    }
    val presets: PresetRepository by lazy { PresetRepository(database, settings) }
    val searchPresets: SearchPresetRepository by lazy { SearchPresetRepository(database) }

    /** 入室中の部屋（pwd を route に載せないための一時置き場）。 */
    val activeRooms: ActiveRooms = ActiveRooms()
}
