package io.github.springthief1123.lovelyspace.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.data.formatObservationTime
import io.github.springthief1123.lovelyspace.notify.AppNotification
import io.github.springthief1123.lovelyspace.notify.unreadCount
import io.github.springthief1123.lovelyspace.ui.components.QuietMenuDefaults

/** 通知ベルを開いたときの一覧。新しい順に並べ、未読には印を付ける。 */
@Composable
internal fun NotificationPanel(
    entries: List<AppNotification>,
    onOpen: (AppNotification) -> Unit,
    onMarkAllRead: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.widthIn(min = 260.dp, max = 320.dp).padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("お知らせ", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (entries.unreadCount > 0) TextButton(onClick = onMarkAllRead) { Text("すべて既読") }
        }
        if (entries.isEmpty()) {
            Text("お知らせはまだありません", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
        } else {
            // 保存上限まで全件を確認できるよう、一覧部分だけをスクロール可能にする。
            // DropdownMenu（QuietDropdownMenu）は中身の幅を固有サイズで測るため、LazyColumn は使えない（測れずに落ちる）。
            // 件数は保存上限（100 件）までなので、通常の Column で足りる。
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                entries.forEach { entry ->
                    key(entry.id) { NotificationPanelItem(entry, onClick = { onOpen(entry) }) }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 4.dp))
        TextButton(onClick = onOpenSettings, modifier = Modifier.padding(start = 6.dp)) { Text("通知の設定") }
    }
}

@Composable
private fun NotificationPanelItem(entry: AppNotification, onClick: () -> Unit) {
    Row(
        // メニューの行と同じく、左右を少し空けた角丸の範囲を押せるようにする（文字の位置は見出しと同じ 18dp）。
        Modifier.fillMaxWidth().padding(horizontal = QuietMenuDefaults.itemOuterPadding).clip(QuietMenuDefaults.itemShape)
            .clickable(onClick = onClick).padding(horizontal = QuietMenuDefaults.itemInnerPadding, vertical = 10.dp)
            .testTag("notification-${entry.id}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).background(
            if (entry.read) Color.Transparent else MaterialTheme.colorScheme.primary, CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.title, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (entry.read) FontWeight.Normal else FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(entry.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            entry.message?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(formatObservationTime(entry.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

