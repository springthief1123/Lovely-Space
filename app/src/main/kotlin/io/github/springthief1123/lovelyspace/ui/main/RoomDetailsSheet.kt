package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.data.statusName
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RoomDetailsSheet(room: Room, favorite: Boolean, actionsEnabled: Boolean,
    onDismiss: () -> Unit, onFavorite: () -> Unit, onHide: (() -> Unit)? = null,
    onEnter: (Room) -> Unit, onPeek: (Room) -> Unit, allowEntry: Boolean = true,
    verificationContent: @Composable () -> Unit = {}, sourceQuery: RoomQuery? = null) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val scope = rememberCoroutineScope()
    val radar by app.radar.state.collectAsStateWithLifecycle()
    var working by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmUntrack by remember { mutableStateOf(false) }
    val tracking = radar.targets.any { roomIdentity(it.room) == roomIdentity(room) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(room.name ?: "会話中の部屋", style = MaterialTheme.typography.titleLarge)
            Text(listOfNotNull(Genres[room.genreKey]?.label, statusName(room.status), room.age?.let { "${it}歳" }, room.area).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(room.message.ifBlank { "一覧に待機メッセージは表示されていません。" }, style = MaterialTheme.typography.bodyLarge)
            Text("取得時の一覧情報です。入室時には空き状況が変わっている場合があります。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            verificationContent()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onFavorite, enabled = actionsEnabled) { Text(if (favorite) "保存を解除" else "部屋を保存") }
                OutlinedButton(enabled = radar.loaded && !working && (tracking || (allowEntry && room.name != null)), onClick = {
                    if (tracking) confirmUntrack = true else {
                        working = true
                        scope.launch {
                            try { app.radar.track(room, sourceQuery); notice = "レーダーに追加しました" }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { notice = "追跡設定を保存できませんでした。" }
                            finally { working = false }
                        }
                    }
                }) { Text(if (tracking) "追跡を解除" else "この部屋を追跡") }
                // 非表示はスワイプと同じく即時に行い、一覧側の「元に戻す」で取り消せるようにする。
                onHide?.let { TextButton(onClick = it, enabled = actionsEnabled) { Text("非表示にする") } }
            }
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = { onDismiss(); if (room.action == RoomAction.PEEK) onPeek(room) else onEnter(room) }, enabled = allowEntry && room.action != RoomAction.NONE, modifier = Modifier.fillMaxWidth()) {
                Text(if (room.action == RoomAction.PEEK) "公開ルームを見る" else if (room.action == RoomAction.NONE) "満室です" else "入室へ進む")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmUntrack) AlertDialog(onDismissRequest = { if (!working) confirmUntrack = false }, title = { Text("追跡を解除しますか？") },
        text = { Column { Text("追跡と自分用メモを削除します。変化の履歴は残ります。")
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) } } },
        confirmButton = { TextButton(enabled = !working && radar.loaded, onClick = {
            working = true
            scope.launch {
                try { app.radar.removeTarget(room); confirmUntrack = false; notice = "追跡を解除しました" }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { notice = "追跡設定を保存できませんでした。" }
                finally { working = false }
            }
        }) { Text("追跡を解除") } },
        dismissButton = { TextButton(enabled = !working, onClick = { confirmUntrack = false }) { Text("戻る") } })
}
