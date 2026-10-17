package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.core.NgExcluded
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.roomIdentity
import io.github.springthief1123.lovelyspace.ui.components.QuietSheet
import io.github.springthief1123.lovelyspace.ui.components.QuietSheetHeader
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpacing

/** 見つける画面に出す NG ワードの状態。[activeSets] は効いているセットの数、[suspended] は「今だけ外す」中か、[failed] は読み込めなかったか。 */
internal data class NgSummary(val activeSets: Int, val suspended: Boolean, val onManage: () -> Unit, val failed: Boolean = false)

/** 詳しい条件の「除外する語」の下に置く 1 行。常に効く NG ワードがいくつ効いているかと、管理への入口。 */
@Composable
internal fun NgSummaryRow(ng: NgSummary) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(ngSummaryText(ng.activeSets, ng.suspended, ng.failed), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = ng.onManage) { Text("NGワードを管理") }
    }
}

internal fun ngSummaryText(activeSets: Int, suspended: Boolean, failed: Boolean = false): String = when {
    failed -> "NGワードを読み込めなかったため、今は除外していません。"
    suspended -> "NGワードを今だけ外しています。"
    activeSets == 0 -> "NGワードは使っていません。"
    else -> "ほかに、NGワード ${activeSets}セットに当たる部屋をいつも除外しています。"
}

/** 状態行の下に置く、NG ワードで外した件数。押すと中身を確認できる。「今だけ外す」中は戻すボタンを出す。 */
@Composable
internal fun NgStatusRow(count: Int, suspended: Boolean, onShow: () -> Unit, onResume: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (suspended) "NGワードを今だけ外しています" else "NGワードで${count}件を除外",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { stateDescription = if (suspended) "NGワードを外し中" else "NGワードを適用中" })
        if (suspended) TextButton(onClick = onResume) { Text("NGワードを戻す") }
        else TextButton(onClick = onShow) { Text("確認") }
    }
}

/** NG ワードで外した部屋の一覧。誤って外れた部屋に気づけるよう、当たった語を添える。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NgExcludedSheet(
    excluded: List<NgExcluded>,
    onDismiss: () -> Unit,
    onSuspend: () -> Unit,
    onManage: () -> Unit,
    onOpenRoom: (Room) -> Unit,
) {
    QuietSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = LovelySpacing.screenHorizontal, end = LovelySpacing.screenHorizontal, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QuietSheetHeader("NGワードで除外した部屋", "${excluded.size}件。当たった語を右に出しています。語は設定の NGワードで変えられます。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSuspend) { Text("今だけ外す") }
                TextButton(onClick = onManage) { Text("NGワードを管理") }
            }
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(excluded, key = { roomIdentity(it.room) }) { (room, match) ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenRoom(room) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(room.name ?: "会話中の部屋", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(room.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text("${match.setLabel}・${match.word}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary, maxLines = 2)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
