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
    PinAlert.AUTO -> "空いたら入室します（一覧などを見ている間だけ。入室できたら「空いたら知らせる」に戻ります）"
}

private fun pinAlertIcon(alert: PinAlert): ImageVector = when (alert) {
    PinAlert.OFF -> Icons.Outlined.NotificationsOff
    PinAlert.NOTIFY -> Icons.Outlined.NotificationsActive
    PinAlert.AUTO -> Icons.Outlined.MeetingRoom
}

/**
 * ピンの知らせ方のボタン。知らせないピンでは「空いたら知らせる」を枠だけで出し、押すとすぐ知らせるようにする。
 * 知らせているピンでは淡いローズの面で示し、押すと 3 つの知らせ方を選ぶメニューを開く（知らせない・自動で入室にもここから）。
 */
@Composable
internal fun PinAlertChip(alert: PinAlert, enabled: Boolean, onSelect: (PinAlert) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val on = alert != PinAlert.OFF
    Box(modifier) {
        QuietFilterChip(selected = on, onClick = { if (on) open = true else onSelect(PinAlert.NOTIFY) },
            label = pinAlertLabel(if (on) alert else PinAlert.NOTIFY), enabled = enabled,
            leadingIcon = if (on) pinAlertIcon(alert) else Icons.Outlined.NotificationAdd,
            modifier = Modifier.semantics { contentDescription = if (on) "知らせ方：${pinAlertLabel(alert)}" else "空いたら知らせる" })
        QuietDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PinAlert.entries.forEach { option ->
                QuietMenuRow(pinAlertLabel(option), onClick = { open = false; onSelect(option) }, icon = pinAlertIcon(option),
                    supporting = pinAlertDescription(option), selected = option == alert)
            }
        }
    }
}
