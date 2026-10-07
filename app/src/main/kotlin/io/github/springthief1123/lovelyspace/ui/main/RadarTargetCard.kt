package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.core.RoomIdentityEvidence
import io.github.springthief1123.lovelyspace.data.*
import io.github.springthief1123.lovelyspace.ui.components.QuietListPanel
import io.github.springthief1123.lovelyspace.ui.components.QuietMenuItem
import io.github.springthief1123.lovelyspace.ui.components.QuietOverflowMenu
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** 追跡先の1行。タップで詳細、ピン・メモ・解除は「︙」にまとめる。 */
@Composable
internal fun RadarTargetCard(target: TrackedRoom, openEnabled: Boolean, editEnabled: Boolean, onOpen: () -> Unit, onPin: () -> Unit, onNote: () -> Unit, onRemove: () -> Unit) {
    QuietListPanel(onClick = if (openEnabled) onOpen else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (target.pinned) Icon(Icons.Outlined.PushPin, contentDescription = "ピン留め", tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp).size(16.dp))
                    Text(target.identity.name ?: "追跡先", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(when (target.evidence) {
                    RoomIdentityEvidence.MATCH -> "最後に確認：${statusName(target.room.status)}"
                    RoomIdentityEvidence.AMBIGUOUS -> "ID確認済み・プロフィール未確認"
                    RoomIdentityEvidence.REUSED -> "異なるプロフィール・追跡停止"
                    RoomIdentityEvidence.NOT_OBSERVED -> "追加後の確認待ち"
                }, style = MaterialTheme.typography.bodyMedium,
                    color = if (target.evidence == RoomIdentityEvidence.REUSED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            QuietOverflowMenu(listOf(
                QuietMenuItem("詳細を確認", enabled = openEnabled, onClick = onOpen),
                QuietMenuItem(if (target.pinned) "ピンを解除" else "ピン留め", enabled = editEnabled, onClick = onPin),
                QuietMenuItem(if (target.note.isEmpty()) "メモを追加" else "メモを編集", enabled = editEnabled, onClick = onNote),
                QuietMenuItem("追跡を解除", enabled = editEnabled, destructive = true, onClick = onRemove),
            ), contentDescription = "追跡先の操作")
        }
        Text(listOf(
            target.confirmedAt?.let { "照合 ${formatObservationTime(it)}" } ?: "照合の記録なし",
            target.observedAt?.let { "ID確認 ${formatObservationTime(it)}・${target.observedPage}ページ" } ?: "ID確認の記録なし",
        ).joinToString("　"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 10.dp))
        if (target.note.isNotBlank()) Text(target.note, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 10.dp))
    }
}
