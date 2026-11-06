package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.NoMeetingRoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomQuery
import io.github.springthief1123.lovelyspace.core.roomIdentity
import io.github.springthief1123.lovelyspace.data.PinAlert
import io.github.springthief1123.lovelyspace.notify.rememberNotificationPermissionRequest
import io.github.springthief1123.lovelyspace.ui.rooms.RoomMenuItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 長押しメニューに、ピンした部屋の自動入室を切り替える項目を作る。ピンはどれも空いたら知らせるので（2026-11-06）、
 * ピンしていない部屋には何も足さない（「ピンする」だけで知らせる）。結果は [onNotice] で知らせる。
 */
@Composable
internal fun rememberRoomQuickActions(
    onNotice: (String) -> Unit,
): (Room, RoomQuery?) -> List<RoomMenuItem> {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val scope = rememberCoroutineScope()
    val pins by app.pins.pins.collectAsStateWithLifecycle()
    val records by app.pins.records.collectAsStateWithLifecycle()
    // 一覧の各カードがメニューの項目を毎回作り直さないよう、ピンと自動入室が変わったときだけ作り直す。
    val pinned = remember(pins) { pins.mapTo(HashSet()) { it.key } }
    val autoEntering = remember(records, pinned) { records.values.filter { it.autoEnters && it.key in pinned }.mapTo(HashSet()) { it.key } }
    val currentNotice by rememberUpdatedState(onNotice)

    return remember(pinned, autoEntering) { { room, _ ->
        val key = roomIdentity(room)
        if (key !in pinned) emptyList() else {
            val on = key in autoEntering
            listOf(RoomMenuItem(if (on) "自動入室をやめる" else "自動入室をオンにする", if (on) Icons.Outlined.NoMeetingRoom else Icons.Outlined.MeetingRoom) {
                scope.launch {
                    val message = try {
                        app.pins.setAlert(key, if (on) PinAlert.NOTIFY else PinAlert.AUTO)
                        autoEntryNotice(!on)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { "ピンを保存できませんでした。" }
                    currentNotice(message)
                }
            })
        }
    } }
}

/**
 * ピンするときに通知の許可を求める。ピンはどれも空いたら知らせるので、まだ求めたことがなければ許可のダイアログを出す。
 * 断られた後は端末の設定へ飛ばさない（ピンのタブで通知がオフなことを伝える）。
 */
@Composable
internal fun rememberPinNotificationPrompt(): () -> Unit = rememberNotificationPermissionRequest(openSettings = false)
