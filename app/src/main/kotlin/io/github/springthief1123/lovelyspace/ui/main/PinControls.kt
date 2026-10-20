package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.NotificationAdd
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.springthief1123.lovelyspace.data.AutoEntryController
import io.github.springthief1123.lovelyspace.data.PinAlert
import io.github.springthief1123.lovelyspace.ui.components.QuietFilterChip
import io.github.springthief1123.lovelyspace.ui.components.QuietDropdownMenu
import io.github.springthief1123.lovelyspace.ui.components.QuietMenuRow

/** ピンの知らせ方の名前。 */
internal fun pinAlertLabel(alert: PinAlert): String = when (alert) {
    PinAlert.OFF -> "知らせない"
    PinAlert.NOTIFY -> "空いたら知らせる"
    PinAlert.AUTO -> "自動で入室"
}

/** 知らせ方を選ぶときの説明。 */
internal fun pinAlertDescription(alert: PinAlert): String = when (alert) {
    PinAlert.OFF -> "様子と空いた時刻を記録するだけ"
    PinAlert.NOTIFY -> "空いた・似た部屋が建ったら通知します"
    PinAlert.AUTO -> "空いたら入室画面を開きます（${AutoEntryController.PINNED_MS / 3_600_000}時間・${AutoEntryController.MAX_ATTEMPTS}回まで。一覧などを見ている間だけ）"
}

private fun pinAlertIcon(alert: PinAlert): ImageVector = when (alert) {
    PinAlert.OFF -> Icons.Outlined.NotificationsOff
    PinAlert.NOTIFY -> Icons.Outlined.NotificationsActive
    PinAlert.AUTO -> Icons.Outlined.MeetingRoom
}

/**
 * ピンの知らせ方のボタン。知らせないピンでは「空いたら知らせる」を枠だけで出し、押すとすぐ知らせるようにする。
 * 知らせているピンでは淡いローズの面で示し、押すと 3 つの知らせ方を選ぶメニューを開く（知らせない・自動で入室にもここから）。
 * 「自動で入室」は期限（[active] が false）を過ぎると「空いたら知らせる」と同じに働くので、そう表示する。
 */
@Composable
internal fun PinAlertChip(alert: PinAlert, active: Boolean, enabled: Boolean, onSelect: (PinAlert) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val shown = if (alert == PinAlert.AUTO && !active) PinAlert.NOTIFY else alert
    val on = shown != PinAlert.OFF
    Box(modifier) {
        QuietFilterChip(selected = on, onClick = { if (on) open = true else onSelect(PinAlert.NOTIFY) },
            label = pinAlertLabel(if (on) shown else PinAlert.NOTIFY), enabled = enabled,
            leadingIcon = if (on) pinAlertIcon(shown) else Icons.Outlined.NotificationAdd,
            modifier = Modifier.semantics { contentDescription = if (on) "知らせ方：${pinAlertLabel(shown)}" else "空いたら知らせる" })
        QuietDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PinAlert.entries.forEach { option ->
                QuietMenuRow(pinAlertLabel(option), onClick = { open = false; onSelect(option) }, icon = pinAlertIcon(option),
                    supporting = pinAlertDescription(option), selected = option == shown)
            }
        }
    }
}
