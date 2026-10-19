package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationAdd
import androidx.compose.material.icons.outlined.NotificationsOff
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
import io.github.springthief1123.lovelyspace.core.RoomStatus
import io.github.springthief1123.lovelyspace.core.roomIdentity
import io.github.springthief1123.lovelyspace.data.PinAlert
import io.github.springthief1123.lovelyspace.notify.rememberNotificationPermissionRequest
import io.github.springthief1123.lovelyspace.ui.rooms.RoomMenuItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 長押しメニューから、ピンの知らせ方を直接変える項目を作る。満室の部屋は「空いたら知らせる」でピンし（以前の順番待ち）、
 * 知らせているピンは知らせるのをやめられる。結果は [onNotice] で知らせる。
 */
@Composable
internal fun rememberRoomQuickActions(
    onNotice: (String) -> Unit,
): (Room, RoomQuery?) -> List<RoomMenuItem> {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val scope = rememberCoroutineScope()
    val pins by app.pins.pins.collectAsStateWithLifecycle()
    val records by app.pins.records.collectAsStateWithLifecycle()
    val requestNotifications = rememberNotificationPermissionRequest()
    // 一覧の各カードがメニューの項目を毎回作り直さないよう、ピンと知らせ方が変わったときだけ作り直す。
    val pinned = remember(pins) { pins.mapTo(HashSet()) { it.key } }
    val notifying = remember(records, pinned) { records.values.filter { it.notifies && it.key in pinned }.mapTo(HashSet()) { it.key } }
    val currentNotice by rememberUpdatedState(onNotice)
    val currentRequestNotifications by rememberUpdatedState(requestNotifications)

    fun save(failure: String, block: suspend () -> String) {
        scope.launch {
            val message = try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { e.message ?: failure }
            catch (e: Exception) { failure }
            currentNotice(message)
        }
    }

    return remember(pinned, notifying) { { room, _ ->
        val key = roomIdentity(room)
        buildList {
            if (key in notifying) {
                add(RoomMenuItem("知らせるのをやめる", Icons.Outlined.NotificationsOff) {
                    save("ピンを保存できませんでした。") { app.pins.setAlert(key, PinAlert.OFF); "知らせるのをやめました（ピンは残します）" }
                })
            } else if (room.status == RoomStatus.FULL) {
                add(RoomMenuItem("空いたら知らせる", Icons.Outlined.NotificationAdd) {
                    currentRequestNotifications()
                    save("ピンを保存できませんでした。") {
                        if (key in pinned) app.pins.setAlert(key, PinAlert.NOTIFY) else app.pins.pin(room, PinAlert.NOTIFY)
                        "ピンしました。空いたら知らせます"
                    }
                })
            }
        }
    } }
}
