package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.data.*
import io.github.springthief1123.lovelyspace.ui.components.*
import io.github.springthief1123.lovelyspace.ui.theme.*
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun RadarScreen(onFindRooms: () -> Unit, onEnterRoom: (Room) -> Unit, onPeekRoom: (Room) -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val state by app.radar.state.collectAsStateWithLifecycle()
    val saved by app.searchPresets.presets.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<RadarRoomSnapshot?>(null) }
    var result by remember { mutableStateOf<RadarResult?>(null) }
    fun action(block: suspend () -> Unit) {
        working = true
        scope.launch {
            try { block(); error = null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = "設定を保存できませんでした。もう一度お試しください。" }
            finally { working = false }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = LovelySpacing.screenHorizontal, end = LovelySpacing.screenHorizontal,
        top = lovelyMainContentTopPadding(), bottom = LovelySpacing.bottomContentInset + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { QuietHeading("LET THE MOMENT FIND YOU", "出会いの変化を、そっと。", "保存した条件の巡回と、気になる部屋の追跡。") }
        item { QuietPanel {
            Text("端末内のレーダー", style = MaterialTheme.typography.titleSmall)
            Text("手動で巡回すると、計画ごとに1ページを確認します。次の巡回で次ページへ進みます。追跡は先頭ページと「見つける」で取得した一覧から確認します。", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { action { app.radar.scan() } }, enabled = state.loaded && !state.running && !working && (state.targets.isNotEmpty() || saved.any { it.id in state.plans }), modifier = Modifier.fillMaxWidth()) { Text(if (state.running) "巡回中…" else "いま巡回する") }
            Text("初回は比較の基準を作ります。バックグラウンド巡回・端末通知はまだ実行しません。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("巡回", "追跡", "履歴").forEachIndexed { index, label -> FilterChip(section == index, { section = index }, label = { Text(label) }) }
        } }
        if (!state.loaded && state.error == null) item { CircularProgressIndicator() }
        (error ?: state.error)?.let { message -> item {
            Text(message, color = MaterialTheme.colorScheme.error)
            if (!state.loaded) TextButton(onClick = app.radar::reload, enabled = !state.running) { Text("再試行") }
        } }
        when (section) {
            0 -> {
                if (saved.isEmpty()) item { QuietPanel { Text("巡回条件をつくりましょう", style = MaterialTheme.typography.titleSmall); Text("「見つける」の絞り込みで条件を保存すると、ここで巡回を有効にできます。", style = MaterialTheme.typography.bodySmall); TextButton(onClick = onFindRooms) { Text("条件を探す") } } }
                items(saved, key = { it.id }) { preset -> QuietPanel {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) { Text(preset.label, style = MaterialTheme.typography.titleSmall); Text(Genres[preset.genreKey]?.label ?: preset.genreKey, style = MaterialTheme.typography.bodySmall) }
                        Switch(preset.id in state.plans, { enabled -> action { app.radar.setPlan(preset.id, enabled) } }, enabled = state.loaded && !working && !state.running)
                    }
                    Text(if (state.resultFor(preset) == null) "この条件はまだ確認していません" else state.scopes[preset.id].orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.resultFor(preset)?.let { found ->
                        TextButton(onClick = { result = found }, enabled = found.rooms.isNotEmpty()) { Text("一致した部屋を確認（${found.rooms.size}件）") }
                    }
                } }
            }
            1 -> {
                if (state.targets.isEmpty()) item { QuietPanel { Text("気になる部屋を追跡", style = MaterialTheme.typography.titleSmall); Text("部屋の詳細から「この部屋を追跡」を選んでください。人の本人確認ではなく、一覧で確認できる部屋の変化を記録します。", style = MaterialTheme.typography.bodySmall); TextButton(onClick = onFindRooms) { Text("部屋を見つける") } } }
                items(state.targets, key = { roomIdentity(it.room) }) { target -> QuietPanel {
                    Text(target.room.name ?: "追跡中の部屋", style = MaterialTheme.typography.titleSmall)
                    Text(when (target.evidence) {
                        RoomIdentityEvidence.MATCH -> "最後に確認：${statusName(target.room.status)}"
                        RoomIdentityEvidence.AMBIGUOUS -> "同じIDを確認・プロフィール未確認"
                        RoomIdentityEvidence.REUSED -> "異なるプロフィールを確認・追跡停止"
                        RoomIdentityEvidence.NOT_OBSERVED -> "未確認"
                    }, color = if (target.evidence == RoomIdentityEvidence.REUSED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text(target.observedAt?.let { "このIDを確認 ${formatObservationTime(it)}" } ?: "追加後のID確認はまだありません", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(target.confirmedAt?.let { "プロフィール照合 ${formatObservationTime(it)}" } ?: "プロフィールを照合した記録はまだありません", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("未取得のページにある可能性があります。表示は現在の在室を保証しません。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { selected = RadarRoomSnapshot(target.identity, target.confirmedAt, target.observedPage, target.evidence == RoomIdentityEvidence.REUSED) }) { Text("部屋の詳細を確認") }
                    TextButton(onClick = { action { app.radar.removeTarget(target.room) } }, enabled = !working && state.loaded) { Text("追跡を解除") }
                } }
            }
            2 -> {
                if (state.events.isEmpty()) item { QuietPanel { Text("変化の履歴はまだありません。初回確認以降の変化を端末内に記録します。") } }
                items(state.events, key = { it.id }) { event -> QuietPanel {
                    Text(event.text, style = MaterialTheme.typography.bodyMedium)
                    Text(formatObservationTime(event.at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    event.rooms.forEach { room ->
                        TextButton(onClick = { selected = RadarRoomSnapshot(room, event.at, event.page ?: 1, event.blocked) }) { Text("${room.name ?: "記録の部屋"}の詳細") }
                    }
                    if (event.rooms.size == 20) Text("履歴には最大20件を保存しています。", style = MaterialTheme.typography.bodySmall)
                } }
            }
        }
    }
    result?.let { found ->
        ModalBottomSheet(onDismissRequest = { result = null }) {
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("確認した一致", style = MaterialTheme.typography.titleLarge)
                    Text("${formatObservationTime(found.at)} · ${found.page}/${found.lastPage}ページの一覧情報", style = MaterialTheme.typography.bodySmall)
                }
                items(found.rooms, key = { roomIdentity(it) }) { room ->
                    QuietPanel {
                        Text(room.name ?: "会話中の部屋", style = MaterialTheme.typography.titleSmall)
                        Text(room.message, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { result = null; selected = RadarRoomSnapshot(room, found.at, found.page) }) { Text("部屋の詳細を確認") }
                    }
                }
            }
        }
    }
    selected?.let { snapshot ->
        RadarRoomSheet(snapshot, onDismiss = { selected = null }, onFindRooms, onEnterRoom, onPeekRoom)
    }
}
