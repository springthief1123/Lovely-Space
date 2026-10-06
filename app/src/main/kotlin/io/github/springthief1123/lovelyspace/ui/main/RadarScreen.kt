package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.data.*
import io.github.springthief1123.lovelyspace.ui.components.*
import io.github.springthief1123.lovelyspace.ui.theme.*
import kotlinx.coroutines.launch

private data class RadarRoomsDisplay(val title: String, val at: Long, val page: Int, val lastPage: Int, val rooms: List<Room>)

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
fun RadarScreen(onFindRooms: () -> Unit, onEnterRoom: (Room) -> Unit, onPeekRoom: (Room) -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val state by app.radar.state.collectAsStateWithLifecycle()
    val savedVm: SavedSearchViewModel = viewModel(factory = viewModelFactory { initializer { SavedSearchViewModel(app.searchPresets) } })
    val savedState by savedVm.state.collectAsStateWithLifecycle()
    val saved = savedState.presets
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<RadarRoomSnapshot?>(null) }
    var result by remember { mutableStateOf<RadarRoomsDisplay?>(null) }
    var editing by rememberSaveable(stateSaver = SearchPresetSaver) { mutableStateOf<SearchPreset?>(null) }
    var pauseConfirm by rememberSaveable { mutableStateOf(false) }
    var candidateDraft by rememberSaveable(stateSaver = CandidateRuleSaver) { mutableStateOf<CandidateRule?>(null) }
    var historyUnread by rememberSaveable { mutableStateOf(false) }
    var historyKind by rememberSaveable { mutableStateOf("") }
    var historyOrigin by rememberSaveable { mutableStateOf("") }
    val historyFilter = RadarHistoryFilter(historyUnread, RadarEventKind.entries.firstOrNull { it.name == historyKind }, historyOrigin.ifBlank { null })
    val visibleEvents = state.events.filter { it.matches(historyFilter) }
    var deletingCandidate by rememberSaveable(stateSaver = CandidateRuleSaver) { mutableStateOf<CandidateRule?>(null) }
    fun action(block: suspend () -> Unit) {
        if (working) return
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
        item { RadarDashboard(state, saved.count { it.id in state.plans }, working,
            onScan = { scope.launch {
                try { app.radar.scan() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { error = "レーダーの状態を確認できませんでした。再読み込みしてお試しください。" }
            } }, onPause = { pauseConfirm = true }) }
        savedState.loadError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error); TextButton(onClick = savedVm::reload) { Text("条件を読み直す") } } }
        item { FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "巡回", 1 to "部屋追跡", 3 to "候補", 2 to if (state.unreadEvents == 0) "履歴" else "履歴 ${state.unreadEvents}").forEach { (index, label) -> FilterChip(section == index, { section = index }, label = { Text(label) }) }
        } }
        if (!state.loaded && state.error == null) item { CircularProgressIndicator() }
        (error ?: state.error)?.let { message -> item {
            Text(message, color = MaterialTheme.colorScheme.error)
            if (!state.loaded) TextButton(onClick = app.radar::reload, enabled = !state.running) { Text("再試行") }
        } }
        when (section) {
            0 -> {
                if (savedState.loading) item { CircularProgressIndicator() }
                if (!savedState.loading && savedState.loadError == null && saved.isEmpty()) item { QuietPanel { Text("巡回条件をつくりましょう", style = MaterialTheme.typography.titleSmall); Text("「見つける」の絞り込みで条件を保存すると、ここで巡回を有効にできます。", style = MaterialTheme.typography.bodySmall); TextButton(onClick = onFindRooms) { Text("条件を探す") } } }
                items(saved, key = { it.id }) { preset -> QuietPanel {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) { Text(preset.label, style = MaterialTheme.typography.titleSmall); Text(Genres[preset.genreKey]?.label ?: preset.genreKey, style = MaterialTheme.typography.bodySmall) }
                        Switch(preset.id in state.plans, { enabled -> action { app.radar.setPlan(preset.id, enabled) } }, enabled = state.loaded && !working && !state.running)
                    }
                    val found = state.resultFor(preset)
                    Text(if (found == null) "この条件はまだ確認していません" else state.scopes[preset.id].orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("次の巡回：${if (found == null) 1 else state.nextPages[preset.id] ?: 1}ページ · 取得したページだけを確認", style = MaterialTheme.typography.bodySmall)
                    if (found != null) TextButton(onClick = { result = RadarRoomsDisplay("確認した一致", found.at, found.page, found.lastPage, found.rooms) }, enabled = found.rooms.isNotEmpty()) { Text("一致した部屋を確認（${found.rooms.size}件）") }
                    TextButton(enabled = !state.running && !working && !savedState.working, onClick = { savedVm.clearEditError(); editing = preset }) { Text("計画を編集") }

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
                    TextButton(onClick = { selected = RadarRoomSnapshot(target.identity, target.confirmedAt, target.observedPage, target.evidence == RoomIdentityEvidence.REUSED, target.sourceQuery) }) { Text("部屋の詳細を確認") }
                    TextButton(onClick = { action { app.radar.removeTarget(target.room) } }, enabled = !working && state.loaded) { Text("追跡を解除") }
                } }
            }
            3 -> {
                item { QuietPanel {
                    Text("表示名から候補を見つける", style = MaterialTheme.typography.titleSmall)
                    Text("名前の一致と部屋の追跡は別の記録です。一致した候補の情報を見てから、気になる部屋を選べます。", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled = state.loaded && !working && state.candidateRules.size < 20 && !state.running, onClick = {
                        error = null
                        candidateDraft = CandidateRule(label = "", genreKey = Genres.default.key, term = "")
                    }) { Text("候補条件を追加") }
                } }
                items(state.candidateRules, key = { "candidate/${it.id}" }) { rule -> QuietPanel {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(rule.label, style = MaterialTheme.typography.titleSmall)
                            Text("${Genres[rule.genreKey]?.label ?: rule.genreKey} · ${if (rule.mode == CandidateMode.EXACT_NAME) "名前の完全一致" else "表示名の文字列"}", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(rule.enabled, { enabled -> action { app.radar.setCandidateEnabled(rule.id, enabled) } }, enabled = state.loaded && !working)
                    }
                    Text(rule.term, style = MaterialTheme.typography.bodyMedium)
                    val found = state.resultFor(rule)
                    Text(if (found == null) "この条件はまだ確認していません" else "${formatObservationTime(found.at)} · ${found.page}/${found.lastPage}ページ · 候補${found.rooms.size}件", style = MaterialTheme.typography.bodySmall)
                    Text("次の巡回：${state.nextCandidatePages[rule.id] ?: 1}ページ。名前非表示・未取得ページは判定しません。", style = MaterialTheme.typography.bodySmall)
                    if (found != null) TextButton(enabled = found.rooms.isNotEmpty(), onClick = {
                        result = RadarRoomsDisplay("確認した候補", found.at, found.page, found.lastPage, found.rooms)
                    }) { Text("一致した候補を確認（${found.rooms.size}件）") }
                    TextButton(enabled = state.loaded && !working && !state.running, onClick = { error = null; candidateDraft = rule }) { Text("条件を編集") }
                    TextButton(enabled = state.loaded && !working, onClick = { error = null; deletingCandidate = rule }) { Text("条件を削除") }
                } }
            }
            2 -> {
                item { QuietPanel {
                    Text("変化の履歴", style = MaterialTheme.typography.titleSmall)
                    Text("未読 ${state.unreadEvents}件 · 表示 ${visibleEvents.size}件", style = MaterialTheme.typography.bodySmall)
                    FilterChip(historyUnread, { historyUnread = !historyUnread }, label = { Text("未読のみ") })
                    RadarDropdown("種類", historyKind, listOf("" to "すべて") + RadarEventKind.entries.map { it.name to it.historyLabel() }, true) { historyKind = it }
                    val origins = state.events.mapNotNull { it.origin }.distinctBy { it.key }
                    RadarDropdown("条件・追跡先", historyOrigin, listOf("" to "すべて") + origins.map { it.key to it.historyLabel() }, true) { historyOrigin = it }
                    TextButton(enabled = state.loaded && !working && state.unreadEvents > 0, onClick = {
                        val ids = state.events.filterNot { it.read }.map { it.id }.toSet()
                        action { app.radar.markEventsRead(ids) }
                    }) { Text("すべて既読にする") }
                } }
                if (visibleEvents.isEmpty()) item { QuietPanel {
                    Text(if (state.events.isEmpty()) "変化の履歴はまだありません。初回確認以降の変化を端末内に記録します。" else "この絞り込みに合う履歴はありません。")
                    if (state.events.isNotEmpty()) TextButton(onClick = { historyUnread = false; historyKind = ""; historyOrigin = "" }) { Text("絞り込みを解除") }
                } }
                items(visibleEvents, key = { it.id }) { event -> QuietPanel {
                    Text("${if (event.read) "既読" else "未読"} · ${event.kind.historyLabel()}", style = MaterialTheme.typography.labelMedium,
                        color = if (event.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                    Text(event.text, style = MaterialTheme.typography.bodyMedium)
                    event.origin?.let { Text(it.historyLabel(), style = MaterialTheme.typography.bodySmall) }
                    Text(formatObservationTime(event.at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    event.rooms.forEach { room ->
                        TextButton(enabled = state.loaded && !working, onClick = { action {
                            app.radar.markEventsRead(setOf(event.id))
                            selected = RadarRoomSnapshot(room, event.at, event.page ?: 1, event.blocked, event.sourceQuery)
                        } }) { Text("${room.name ?: "記録の部屋"}の詳細") }
                    }
                    if (!event.read) TextButton(enabled = state.loaded && !working, onClick = { action { app.radar.markEventsRead(setOf(event.id)) } }) { Text("既読にする") }
                    if (event.rooms.size == 20) Text("履歴には最大20件を保存しています。", style = MaterialTheme.typography.bodySmall)
                } }
            }
        }
    }
    editing?.let { value ->
        RadarPlanEditor(value, savedState.working, savedState.editError,
            onDismiss = { editing = null }, onSave = { savedVm.save(it) { editing = null } })
    }
    if (pauseConfirm) AlertDialog(onDismissRequest = { if (!working) pauseConfirm = false }, title = { Text("計画・候補監視をすべて停止しますか？") },
        text = { Column {
            Text("計画・候補条件と確認済みの結果は残します。実行中の通信は完了させ、以降の計画・候補ページは取得しません。部屋の追跡設定は保持します。")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !working, onClick = { action { app.radar.pauseAllPlans(); pauseConfirm = false } }) { Text("すべて停止") } },
        dismissButton = { TextButton(enabled = !working, onClick = { pauseConfirm = false }) { Text("キャンセル") } })
    candidateDraft?.let { value ->
        CandidateRuleEditor(value, working, error, onDismiss = { candidateDraft = null },
            onSave = { action { app.radar.saveCandidate(it); candidateDraft = null } })
    }
    deletingCandidate?.let { rule ->
        AlertDialog(onDismissRequest = { if (!working) deletingCandidate = null }, title = { Text("候補条件を削除しますか？") },
            text = { Column { Text(rule.label); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
            confirmButton = { TextButton(enabled = !working, onClick = { action { app.radar.removeCandidate(rule.id); deletingCandidate = null } }) { Text("削除") } },
            dismissButton = { TextButton(enabled = !working, onClick = { deletingCandidate = null }) { Text("キャンセル") } })
    }
    result?.let { found ->
        ModalBottomSheet(onDismissRequest = { result = null }) {
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(found.title, style = MaterialTheme.typography.titleLarge)
                    Text("${formatObservationTime(found.at)} · ${found.page}/${found.lastPage}ページの一覧情報", style = MaterialTheme.typography.bodySmall)
                }
                items(found.rooms, key = { roomIdentity(it) }) { room ->
                    QuietPanel {
                        Text(room.name ?: "会話中の部屋", style = MaterialTheme.typography.titleSmall)
                        Text(room.message, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
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
