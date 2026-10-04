package io.github.springthief1123.lovelyspace

import android.app.Application
import io.github.springthief1123.lovelyspace.core.ShaloveClient
import io.github.springthief1123.lovelyspace.settings.SettingsRepository
import io.github.springthief1123.lovelyspace.ui.chat.ActiveRooms

class LovelySpaceApp : Application() {
    /** 本家への通信はアプリ全体でこの 1 つを共有し、アクセス間隔の制限を一元化する。 */
    val client: ShaloveClient by lazy { ShaloveClient() }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }

    /** 入室中の部屋（pwd を route に載せないための一時置き場）。 */
    val activeRooms: ActiveRooms = ActiveRooms()
}
