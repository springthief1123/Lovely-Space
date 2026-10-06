package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.core.RoomIdentityEvidence
import io.github.springthief1123.lovelyspace.data.*
import io.github.springthief1123.lovelyspace.ui.components.QuietPanel

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RadarTargetCard(target: TrackedRoom, enabled: Boolean, onOpen: () -> Unit, onPin: () -> Unit, onNote: () -> Unit, onRemove: () -> Unit) {
    QuietPanel {
        Text("${if (target.pinned) "ピン留め · " else ""}${target.identity.name ?: "追跡先"}", style = MaterialTheme.typography.titleSmall)
        Text(when (target.evidence) {
            RoomIdentityEvidence.MATCH -> "最後に確認：${statusName(target.room.status)}"
            RoomIdentityEvidence.AMBIGUOUS -> "ID確認済み・プロフィール未確認"
            RoomIdentityEvidence.REUSED -> "異なるプロフィール・追跡停止"
            RoomIdentityEvidence.NOT_OBSERVED -> "追加後の確認待ち"
        }, style = MaterialTheme.typography.bodyMedium,
            color = if (target.evidence == RoomIdentityEvidence.REUSED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text(target.confirmedAt?.let { "プロフィール照合 ${formatObservationTime(it)}" } ?: "プロフィール照合の記録なし", style = MaterialTheme.typography.bodySmall)
        Text(target.observedAt?.let { "ID確認 ${formatObservationTime(it)} · ${target.observedPage}ページ" } ?: "ID確認の記録なし", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (target.note.isNotBlank()) Text(target.note, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onOpen, enabled = enabled) { Text("詳細を確認") }
            TextButton(onClick = onPin, enabled = enabled) { Text(if (target.pinned) "ピンを解除" else "ピン留め") }
            TextButton(onClick = onNote, enabled = enabled) { Text(if (target.note.isEmpty()) "メモを追加" else "メモを編集") }
            TextButton(onClick = onRemove, enabled = enabled) { Text("追跡を解除") }
        }
    }
}
