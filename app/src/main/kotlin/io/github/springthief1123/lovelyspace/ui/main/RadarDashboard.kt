package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.data.*
import io.github.springthief1123.lovelyspace.ui.components.QuietMenuItem
import io.github.springthief1123.lovelyspace.ui.components.QuietOverflowMenu
import io.github.springthief1123.lovelyspace.ui.components.QuietPanel
import androidx.compose.ui.Alignment

/**
 * レーダーの見張りの様子。自動の見張りの切り替え・ピンと条件の数・最終取得を上の2行に収め、
 * 巡回の結果と確認範囲の説明は必要なときだけ開く。見つけた一致の記録は通知ベルに残す。
 */
@Composable
internal fun RadarDashboard(state: RadarState, pins: Int, plans: Int, working: Boolean, onScan: () -> Unit, onPause: () -> Unit,
    onAutomatic: (Boolean) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val candidates = state.candidateRules.count { it.enabled }
    val report = state.lastScan
    QuietPanel {
        // 行全体でオン・オフを切り替え、読み上げでは状態の文とスイッチを 1 つの項目にする。
        Row(Modifier.fillMaxWidth().toggleable(value = state.automatic, role = Role.Switch, onValueChange = onAutomatic),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (state.automatic) "見張り中" else "条件の巡回を止めています", style = MaterialTheme.typography.titleSmall)
                Text("ピン ${pins}・条件 ${plans}・名前 ${candidates}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(state.automatic, onCheckedChange = null)
        }
        Text(state.lastConfirmedAt?.let { "最後の一覧取得 ${formatObservationTime(it)}" } ?: "この起動中の取得記録はまだありません",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (report != null && state.running) {
            LinearProgressIndicator(progress = { if (report.pages.isEmpty()) 0f else report.completed.toFloat() / report.pages.size }, modifier = Modifier.fillMaxWidth())
            Text("確認 ${report.completed}/${report.pages.size}ページ", style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // 巡回中・確認中でも押せる（本家の「最新更新」と同じく押すたびに取り直す）。
            FilledTonalButton(onClick = onScan, enabled = state.loaded && !working && plans + candidates > 0,
                modifier = Modifier.weight(1f)) {
                Text(when {
                    state.scanNowPending -> "新着を確認中…"
                    state.running -> "巡回中…（押すと新着を確認）"
                    else -> "今すぐ新着を確認"
                })
            }
            QuietOverflowMenu(listOf(
                QuietMenuItem(if (expanded) "確認範囲を閉じる" else "確認範囲と使い方") { expanded = !expanded },
                QuietMenuItem("条件の巡回をすべて止める", enabled = state.loaded && !working && plans + candidates > 0, destructive = true, onClick = onPause),
            ))
        }
        if (expanded) {
            Text("画面を開いている間、巡回する条件は最新ページを優先して更新し、残りのページも自動で巡回します。同じ一覧の取得は見つける画面と共有します。ピンは、どの画面が読んだ一覧からも様子を確かめ、満室のピンを知らせるときはその部屋のページを設定の間隔で読み直します。", style = MaterialTheme.typography.bodySmall)
            if (report != null && !state.running) {
                Text("${if (report.interrupted) "中断" else "前回の巡回"}：取得 ${report.confirmed}・失敗 ${report.failed}・見送り ${report.skipped}・一致 ${report.matches}件・新しい履歴 ${report.newEvents}件",
                    style = MaterialTheme.typography.bodySmall)
            }
            report?.pages?.forEach { page ->
                Text("${radarQueryLabel(page.query)} · ${when (page.status) {
                    RadarCheckStatus.PENDING -> "取得待ち"
                    RadarCheckStatus.CHECKING -> "確認中"
                    RadarCheckStatus.CONFIRMED -> "取得済み・一致${page.matches}件"
                    RadarCheckStatus.FAILED -> "取得失敗"
                    RadarCheckStatus.SKIPPED -> "見送り"
                }}", style = MaterialTheme.typography.bodySmall)
                page.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

internal fun radarQueryLabel(query: RoomQuery): String = listOfNotNull(query.genre.label, "${query.page}ページ",
    query.sex?.let { when (it) { Gender.FEMALE -> "女性"; Gender.MALE -> "男性"; else -> "性別指定" } },
    query.prefecture?.let { "地域指定 $it" }, query.ageBand?.let { "年齢 $it" },
    query.publicOnly?.let { if (it) "公開" else "非公開" }, query.waitingOnly?.let { if (it) "待機中" else "満室" },
    query.name?.let { "名前「$it」" }, query.message?.let { "待機メッセージ「$it」" }).joinToString(" · ")
