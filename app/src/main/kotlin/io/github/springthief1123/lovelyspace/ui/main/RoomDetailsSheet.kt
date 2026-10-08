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
import io.github.springthief1123.lovelyspace.notify.NotificationKind
import io.github.springthief1123.lovelyspace.notify.appNotificationSettingsIntent
import io.github.springthief1123.lovelyspace.notify.rememberNotificationPermissionRequest
import io.github.springthief1123.lovelyspace.notify.rememberNotificationsAllowed
import io.github.springthief1123.lovelyspace.ui.components.QuietNotice
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
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
    var error by remember { mutableStateOf<String?>(null) }
    var confirmUntrack by remember { mutableStateOf(false) }
    val tracking = radar.targets.any { roomIdentity(it.room) == roomIdentity(room) }
    val waitlist by app.waitlist.entries.collectAsStateWithLifecycle()
    val waiting = waitlist.any { it.key == roomIdentity(room) && it.status == io.github.springthief1123.lovelyspace.data.WaitlistStatus.WATCHING }
    // 通知の許可は、ダイアログや端末の設定から戻ったときに確かめ直す。
    val notificationsAllowed = rememberNotificationsAllowed(NotificationKind.WAITLIST)
    val requestNotifications = rememberNotificationPermissionRequest()
    val context = LocalContext.current
    val trackReason = trackUnavailableReason(room, allowEntry, tracking)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(room.name ?: "会話中の部屋", style = MaterialTheme.typography.titleLarge)
            Text(roomDetailsSubtitle(room), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(room.message.ifBlank { "一覧に待機メッセージは表示されていません。" }, style = MaterialTheme.typography.bodyLarge)
            Text("取得時の一覧情報です。入室時には空き状況が変わっている場合があります。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            verificationContent()
            // 結果はボタンの状態（「保存済み」「追跡中」）で示し、失敗だけをお知らせに出す。
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StateButton(if (favorite) "保存済み" else "部屋を保存", done = favorite, enabled = actionsEnabled, onClick = onFavorite)
                StateButton(if (tracking) "追跡中" else "この部屋を追跡", done = tracking,
                    enabled = radar.loaded && !working && (tracking || trackReason == null), onClick = {
                    if (tracking) confirmUntrack = true else {
                        working = true
                        error = null
                        scope.launch {
                            try { app.radar.track(room, sourceQuery) }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { error = "追跡設定を保存できませんでした。もう一度お試しください。" }
                            finally { working = false }
                        }
                    }
                })
                // 非表示はスワイプと同じく即時に行い、一覧側の「元に戻す」で取り消せるようにする。
                onHide?.let { TextButton(onClick = it, enabled = actionsEnabled) { Text("非表示にする") } }
            }
            trackReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            // 満室の部屋は順番待ちに登録できる。空いたら通知し、入室とロボット確認は利用者が行う。
            if (room.status == RoomStatus.FULL || waiting) {
                StateButton(if (waiting) "順番待ち中（押すと取り消す）" else "空いたら知らせる（順番待ち）", done = waiting,
                    enabled = !working, modifier = Modifier.fillMaxWidth(), onClick = {
                    working = true
                    error = null
                    // 通知の許可を求めても登録は進める。文言は許可の結果（戻ってきたときの状態）で決める。
                    if (!waiting && !notificationsAllowed) requestNotifications()
                    scope.launch {
                        try {
                            if (waiting) app.waitlist.remove(roomIdentity(room)) else app.waitlist.register(room, sourceQuery)
                        }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: IllegalArgumentException) { error = e.message }
                        catch (e: Exception) { error = "順番待ちを保存できませんでした。もう一度お試しください。" }
                        finally { working = false }
                    }
                })
                if (waiting) QuietNotice(
                    waitlistNoticeText(notificationsAllowed, io.github.springthief1123.lovelyspace.data.WaitlistRepository.DEFAULT_HOURS),
                    error = !notificationsAllowed,
                    actionLabel = if (notificationsAllowed) null else "通知の設定を開く",
                    onAction = { context.startActivity(appNotificationSettingsIntent(context)) },
                )
            }
            error?.let { QuietNotice(it, error = true, onDismiss = { error = null }) }
            Button(onClick = { onDismiss(); if (room.action == RoomAction.PEEK) onPeek(room) else onEnter(room) }, enabled = allowEntry && room.action != RoomAction.NONE, modifier = Modifier.fillMaxWidth()) {
                Text(if (room.action == RoomAction.PEEK) "公開ルームを見る" else if (room.action == RoomAction.NONE) "満室です" else "入室へ進む")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmUntrack) AlertDialog(onDismissRequest = { if (!working) confirmUntrack = false }, title = { Text("追跡を解除しますか？") },
        text = { Column { Text("追跡と自分用メモを削除します。変化の履歴は残ります。")
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } } },
        confirmButton = { TextButton(enabled = !working && radar.loaded, onClick = {
            working = true
            scope.launch {
                try { app.radar.removeTarget(room); confirmUntrack = false; error = null }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { error = "追跡設定を保存できませんでした。もう一度お試しください。" }
                finally { working = false }
            }
        }) { Text("追跡を解除") } },
        dismissButton = { TextButton(enabled = !working, onClick = { confirmUntrack = false }) { Text("戻る") } })
}

/** 押した後の状態を見た目で示すボタン。済んだ状態ではチェックを付けて塗る。 */
@Composable
private fun StateButton(label: String, done: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (done) FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    } else OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
}

/** 詳細シートの見出しの下の行。カードと同じく性別・年齢・地域の順に並べる。 */
internal fun roomDetailsSubtitle(room: Room): String = listOfNotNull(
    Genres[room.genreKey]?.label,
    statusName(room.status),
    when (room.gender) { Gender.FEMALE -> "女性"; Gender.MALE -> "男性"; Gender.UNKNOWN -> null },
    room.age?.let { "${it}歳" },
    room.area,
).joinToString(" · ")

/** 「この部屋を追跡」が押せない理由。押せるときは null。 */
internal fun trackUnavailableReason(room: Room, allowEntry: Boolean, tracking: Boolean): String? = when {
    tracking -> null
    !allowEntry -> "最新の一覧でこの部屋を確認できていないため、追跡できません。"
    room.name == null -> "名前が表示されていない部屋（会話中・満室）は追跡できません。"
    else -> null
}

/** 順番待ちに登録した後の文言。通知が出せないときは、空いても知らせられないことを伝える。 */
internal fun waitlistNoticeText(notificationsAllowed: Boolean, hours: Int): String =
    if (notificationsAllowed) "順番待ちに登録しました。空いたら通知します（${hours}時間まで）。"
    else "順番待ちに登録しました。通知がオフのため、空いても知らせられません。"
