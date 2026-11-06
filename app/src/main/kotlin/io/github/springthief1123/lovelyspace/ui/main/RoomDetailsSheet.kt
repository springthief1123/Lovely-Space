package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.ui.theme.LovelySpacing
import io.github.springthief1123.lovelyspace.ui.theme.LocalLovelyColors
import io.github.springthief1123.lovelyspace.ui.components.QuietSheetHeader
import io.github.springthief1123.lovelyspace.ui.components.QuietSheet
import io.github.springthief1123.lovelyspace.ui.components.QuietPanel
import io.github.springthief1123.lovelyspace.ui.components.QuietSwitchRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.data.PinAlert
import io.github.springthief1123.lovelyspace.data.PinView
import io.github.springthief1123.lovelyspace.data.formatObservationTime
import io.github.springthief1123.lovelyspace.notify.NotificationKind
import io.github.springthief1123.lovelyspace.notify.appNotificationSettingsIntent
import io.github.springthief1123.lovelyspace.notify.rememberNotificationsAllowed
import io.github.springthief1123.lovelyspace.ui.components.QuietNotice
import io.github.springthief1123.lovelyspace.ui.rooms.MetaChip
import io.github.springthief1123.lovelyspace.ui.rooms.roomStatusColor
import io.github.springthief1123.lovelyspace.ui.rooms.statusLabel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Lock
import kotlinx.coroutines.launch

/**
 * 部屋の詳細。上から 見出し（名前・プロフィール）→ 様子のチップ → 待機メッセージ・メモ → ピン（自動入室・空いた記録）→ 入室 → ピン・非表示 の順。
 * ピンした部屋は、ピンの見張りが確かめた様子（閉じた・未確認など）で出し、手元の古い姿の「満室」などは出さない（2026-11-06 Yuya）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RoomDetailsSheet(room: Room, favorite: Boolean, actionsEnabled: Boolean,
    onDismiss: () -> Unit, onFavorite: () -> Unit, onHide: (() -> Unit)? = null,
    onEnter: (Room) -> Unit, onPeek: (Room) -> Unit, allowEntry: Boolean = true,
    verificationContent: @Composable () -> Unit = {}, sourceQuery: RoomQuery? = null) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pins by app.pins.pins.collectAsStateWithLifecycle()
    val pin = pins.firstOrNull { it.key == roomIdentity(room) }?.takeIf { favorite }
    // 通知の許可は、ダイアログや端末の設定から戻ったときに確かめ直す。
    val notificationsAllowed = rememberNotificationsAllowed(NotificationKind.WAITLIST)
    val askNotifications = rememberPinNotificationPrompt()
    val context = LocalContext.current
    fun save(block: suspend () -> Unit) {
        working = true
        error = null
        scope.launch {
            try { block() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: IllegalArgumentException) { error = e.message ?: "ピンを保存できませんでした。" }
            catch (e: Exception) { error = "ピンを保存できませんでした。もう一度お試しください。" }
            finally { working = false }
        }
    }
    // 閉じた・まだ確かめていないピンは、手元の姿の状態（満室など）が古いので、入室の操作を出さない。
    val current = pin == null || pin.listed && pin.state != PinState.CLOSED
    QuietSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = LovelySpacing.screenHorizontal).padding(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            QuietSheetHeader(room.name ?: pin?.name ?: "会話中の部屋", roomDetailsSubtitle(room).ifEmpty { null })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (pin != null && !current) MetaChip(pinStateLabel(pin.state), MaterialTheme.colorScheme.onSurfaceVariant, filled = true)
                else {
                    MetaChip(statusLabel(room.status), roomStatusColor(room.status), filled = true)
                    MetaChip(if (room.isPublic) "公開" else "非公開", MaterialTheme.colorScheme.onSurfaceVariant, filled = false,
                        icon = if (room.isPublic) Icons.Outlined.Visibility else Icons.Outlined.Lock)
                }
                Genres[room.genreKey]?.let { MetaChip(it.label, MaterialTheme.colorScheme.onSurfaceVariant, filled = false) }
            }
            Text(room.message.ifBlank { "一覧に待機メッセージは表示されていません。" }, style = MaterialTheme.typography.bodyLarge)
            pin?.record?.memo?.takeIf { it.isNotBlank() }?.let {
                Text("メモ：$it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            verificationContent()
            pin?.let { PinDetails(it, enabled = !working && actionsEnabled, notificationsAllowed = notificationsAllowed,
                onAutoEntry = { on -> save { app.pins.setAlert(it.key, if (on) PinAlert.AUTO else PinAlert.NOTIFY) } },
                onOpenSettings = { context.startActivity(appNotificationSettingsIntent(context)) }) }
            error?.let { QuietNotice(it, error = true, onDismiss = { error = null }) }
            Button(onClick = { onDismiss(); if (room.action == RoomAction.PEEK) onPeek(room) else onEnter(room) },
                enabled = allowEntry && current && room.action != RoomAction.NONE, modifier = Modifier.fillMaxWidth()) {
                Text(when {
                    !allowEntry || !current -> "一覧に出ていません"
                    room.action == RoomAction.PEEK -> "公開ルームを見る"
                    room.action == RoomAction.NONE -> "満室です"
                    else -> "入室へ進む"
                })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 結果はボタンの文言で示し、失敗だけをお知らせに出す。ピンはどれも空いたら知らせる。
                OutlinedButton(onClick = { if (!favorite) askNotifications(); onFavorite() }, enabled = actionsEnabled, modifier = Modifier.weight(1f)) {
                    Icon(if (favorite) Icons.Filled.PushPin else Icons.Outlined.PushPin, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (favorite) "ピンを外す" else "ピンする")
                }
                // 非表示はスワイプと同じく即時に行い、一覧側の「元に戻す」で取り消せるようにする。
                onHide?.let { TextButton(onClick = it, enabled = actionsEnabled, modifier = Modifier.weight(1f)) { Text("非表示にする") } }
            }
            Text(if (pin == null) "取得時の一覧情報です。入室時には空き状況が変わっている場合があります。"
                else "ピンした部屋は空いたら通知します。閉じても同じ名前・待機メッセージで立て直されたら、ピンを新しい部屋へ移します。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 詳細のピンの欄。自動入室の切り替え、空いた記録、通知がオフのときの案内。 */
@Composable
private fun PinDetails(pin: PinView, enabled: Boolean, notificationsAllowed: Boolean, onAutoEntry: (Boolean) -> Unit, onOpenSettings: () -> Unit) {
    val record = pin.record
    QuietPanel {
        QuietSwitchRow(checked = record.alert == PinAlert.AUTO, onCheckedChange = onAutoEntry, title = "自動入室", enabled = enabled,
            description = "空いたら入室します（一覧などを見ている間だけ）。入室できたら自動入室は終わります。",
            contentPadding = PaddingValues(0.dp))
        HorizontalDivider(color = LocalLovelyColors.current.divider)
        val stats = VacancyStats.of(record.vacancies, System.currentTimeMillis())
        if (stats.count == 0) Text("まだ空いた記録はありません。満室・閉じた部屋が待機中になると、時刻を記録します。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            Text("空いた時間帯（直近${VacancyStats.DAYS}日）" + (stats.peakHour?.let { "・多いのは${it}時台" } ?: ""), style = MaterialTheme.typography.labelMedium)
            VacancyHistogram(stats.hours)
            Text("最後に空いた：${stats.last?.let(::formatObservationTime) ?: "-"}・これまでに${stats.count}回" +
                (record.rebuilds.takeIf { it > 0 }?.let { "・立て直し${it}回" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (pin.state) {
            PinState.CLOSED -> "閉じています" + (record.stateAt?.let { "（${formatObservationTime(it)}）" } ?: "") + "。立て直されたら、ピンを新しい部屋へ移します。"
            PinState.UNSEEN -> "まだ一覧で確かめていません。そのジャンルの一覧を読むと分かります。"
            else -> null
        }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (!notificationsAllowed) QuietNotice(pinNoticeText(false), error = true, actionLabel = "通知の設定を開く", onAction = onOpenSettings)
}

/** 詳細シートの見出しの下の行。カードと同じく性別・年齢・地域の順に並べる（様子・ジャンルはチップで出す）。 */
internal fun roomDetailsSubtitle(room: Room): String = listOfNotNull(
    when (room.gender) { Gender.FEMALE -> "女性"; Gender.MALE -> "男性"; Gender.UNKNOWN -> null },
    room.age?.let { "${it}歳" },
    room.area,
).joinToString(" · ")

/** ピンの通知の文言。通知が出せないときは、空いても知らせられないことを伝える。 */
internal fun pinNoticeText(notificationsAllowed: Boolean): String =
    if (notificationsAllowed) "ピンした部屋が空いたら通知します。"
    else "通知がオフのため、ピンした部屋が空いても知らせられません。"
