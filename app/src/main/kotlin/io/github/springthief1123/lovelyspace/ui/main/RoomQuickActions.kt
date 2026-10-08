package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationAdd
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomQuery
import io.github.springthief1123.lovelyspace.core.RoomStatus
import io.github.springthief1123.lovelyspace.core.roomIdentity
import io.github.springthief1123.lovelyspace.data.WaitlistRepository
import io.github.springthief1123.lovelyspace.data.WaitlistStatus
import io.github.springthief1123.lovelyspace.notify.rememberNotificationPermissionRequest
import io.github.springthief1123.lovelyspace.ui.rooms.RoomMenuItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 長押しメニューから追跡・順番待ちを直接行うための項目を作る。詳細シートと同じ処理を呼び、
 * 結果は [onNotice] で知らせる。追跡の解除はメモも消えるため、確認のある詳細シートを [onOpenDetails] で開く。
 */
@Composable
internal fun rememberRoomQuickActions(
    onNotice: (String) -> Unit,
    onOpenDetails: (Room) -> Unit,
): (Room, RoomQuery?) -> List<RoomMenuItem> {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val scope = rememberCoroutineScope()
    val radar by app.radar.state.collectAsStateWithLifecycle()
    val waitlist by app.waitlist.entries.collectAsStateWithLifecycle()
    val requestNotifications = rememberNotificationPermissionRequest()
    val tracked = radar.targets.mapTo(HashSet()) { roomIdentity(it.room) }
    val waiting = waitlist.filter { it.status == WaitlistStatus.WATCHING }.mapTo(HashSet()) { it.key }

    fun save(failure: String, block: suspend () -> String) {
        scope.launch {
            val message = try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { e.message ?: failure }
            catch (e: Exception) { failure }
            onNotice(message)
        }
    }

    return { room, sourceQuery ->
        val key = roomIdentity(room)
        buildList {
            if (key in tracked) {
                add(RoomMenuItem("追跡を解除…", Icons.Outlined.TrackChanges, enabled = radar.loaded) { onOpenDetails(room) })
            } else {
                add(RoomMenuItem("この部屋を追跡", Icons.Outlined.TrackChanges, enabled = radar.loaded && room.name != null) {
                    save("追跡設定を保存できませんでした。") { app.radar.track(room, sourceQuery); "レーダーに追加しました" }
                })
            }
            // 満室の部屋は順番待ちに登録できる。空いたら通知し、入室とロボット確認は利用者が行う。
            if (key in waiting) {
                add(RoomMenuItem("順番待ちを取り消す", Icons.Outlined.NotificationsOff) {
                    save("順番待ちを保存できませんでした。") { app.waitlist.remove(key); "順番待ちを取り消しました" }
                })
            } else if (room.status == RoomStatus.FULL) {
                add(RoomMenuItem("空いたら知らせる", Icons.Outlined.NotificationAdd) {
                    requestNotifications()
                    save("順番待ちを保存できませんでした。") {
                        app.waitlist.register(room, sourceQuery)
                        "順番待ちに登録しました。空いたら通知します（${WaitlistRepository.DEFAULT_HOURS}時間まで）"
                    }
                })
            }
        }
    }
}
