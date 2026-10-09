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
 * レーダーの要約。自動巡回の切り替え・件数・最終取得を上の2行に収め、
 * 巡回の結果と確認範囲の説明は必要なときだけ開く。
 */
@Composable
internal fun RadarDashboard(state: RadarState, plans: Int, working: Boolean, onScan: () -> Unit, onPause: () -> Unit, onAutomatic: (Boolean) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val candidates = state.candidateRules.count { it.enabled }
    val targets = state.targets.count { it.evidence != RoomIdentityEvidence.REUSED }
    val report = state.lastScan
    QuietPanel {
        // 行全体でオン・オフを切り替え、読み上げでは状態の文とスイッチを 1 つの項目にする。
        Row(Modifier.fillMaxWidth().toggleable(value = state.automatic, role = Role.Switch, onValueChange = onAutomatic),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (state.automatic) "自動巡回中" else "自動巡回を停止中", style = MaterialTheme.typography.titleSmall)
                Text("巡回 ${plans}・候補 ${candidates}・追跡 ${targets}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(state.automatic, onCheckedChange = null)
        }
        Text(state.lastConfirmedAt?.let { "最後の一覧取得 ${formatObservationTime(it)}" } ?: "この起動中の取得記録はまだありません",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (report != null) {
            if (state.running) {
                LinearProgressIndicator(progress = { if (report.pages.isEmpty()) 0f else report.completed.toFloat() / report.pages.size }, modifier = Modifier.fillMaxWidth())
                Text("確認 ${report.completed}/${report.pages.size}ページ", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("${if (report.interrupted) "中断" else "前回の巡回"}：取得 ${report.confirmed}・失敗 ${report.failed}・見送り ${report.skipped}・一致 ${report.matches}件・新しい履歴 ${report.newEvents}件",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = onScan, enabled = state.loaded && !state.running && !working && plans + candidates + targets > 0,
                modifier = Modifier.weight(1f)) { Text(if (state.running) "巡回中…" else "今すぐ新着を確認") }
            QuietOverflowMenu(listOf(
                QuietMenuItem(if (expanded) "確認範囲を閉じる" else "確認範囲と使い方") { expanded = !expanded },
                QuietMenuItem("計画・候補監視をすべて停止", enabled = state.loaded && !working && plans + candidates > 0, destructive = true, onClick = onPause),
            ))
        }
        if (expanded) {
            Text("画面を開いている間、最新ページを優先して更新し、残りのページも自動で巡回します。同じ一覧の取得は共有します。部屋追跡は記録したページと絞り込みを使います。", style = MaterialTheme.typography.bodySmall)
            Text("初回は比較基準を作ります。未取得のページから不在を判断しません。一致数はページごとの件数で、全ページの人数ではありません。履歴件数は巡回中に記録された変化です。", style = MaterialTheme.typography.bodySmall)
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
