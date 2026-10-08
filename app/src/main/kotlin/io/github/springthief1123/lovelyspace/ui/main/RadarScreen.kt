package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
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
    val preferencesVm: io.github.springthief1123.lovelyspace.ui.rooms.RoomPreferenceViewModel = viewModel(
        factory = viewModelFactory { initializer { io.github.springthief1123.lovelyspace.ui.rooms.RoomPreferenceViewModel(app.roomPreferences) } })
    val preferences by preferencesVm.state.collectAsStateWithLifecycle()
    val liveRooms = state.livePages.values.flatMap { it.rooms }.distinctBy(::roomIdentity).filter { room ->
        !preferences.isHidden(room) && (saved.any { it.id in state.plans && it.genreKey == room.genreKey && searchRooms(listOf(room), it.criteria).isNotEmpty() } ||
            state.candidateRules.any { it.enabled && it.matches(room) })
    }
    LaunchedEffect(liveRooms) { preferencesVm.observe(liveRooms) }
    var liveDetails by remember { mutableStateOf<Room?>(null) }
    val scope = rememberCoroutineScope()
    var scanJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    ForegroundPolling(state.automatic, onStop = { scanJob?.cancel() }) { app.radar.monitor() }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableIntStateOf(4) }
    var selected by remember { mutableStateOf<RadarRoomSnapshot?>(null) }
    var result by remember { mutableStateOf<RadarRoomsDisplay?>(null) }
    var editing by rememberSaveable(stateSaver = SearchPresetSaver) { mutableStateOf<SearchPreset?>(null) }
    var pauseConfirm by rememberSaveable { mutableStateOf(false) }
    var candidateDraft by rememberSaveable(stateSaver = CandidateRuleSaver) { mutableStateOf<CandidateRule?>(null) }
    var editingTargetKey by rememberSaveable { mutableStateOf<String?>(null) }
    var removingTargetKey by rememberSaveable { mutableStateOf<String?>(null) }
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
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(preferencesVm) {
        preferencesVm.hiddenRooms.collect { room ->
            if (snackbar.showSnackbar("部屋を非表示にしました", actionLabel = "元に戻す", withDismissAction = true) == SnackbarResult.ActionPerformed) preferencesVm.unhide(room)
        }
    }
    val planCount = saved.count { it.id in state.plans }
    val waitlist by app.waitlist.entries.collectAsStateWithLifecycle()
    val requestNotifications = io.github.springthief1123.lovelyspace.notify.rememberNotificationPermissionRequest()
    QuietPage {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = LovelySpacing.screenHorizontal, end = LovelySpacing.screenHorizontal,
        top = lovelyMainContentTopPadding(), bottom = lovelyMainContentBottomInset() + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { QuietHeading("レーダー") }
        item { RadarDashboard(state, planCount, working,
            onScan = { scanJob?.cancel(); scanJob = scope.launch {
                try { app.radar.scan(latestFirst = true) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { error = "レーダーの状態を確認できませんでした。再読み込みしてお試しください。" }
            } }, onPause = { pauseConfirm = true }, onAutomatic = app.radar::automatic) }
        savedState.loadError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error); TextButton(onClick = savedVm::reload) { Text("条件を読み直す") } } }
        item { QuietTabs(listOf(
            4 to "一致 ${liveRooms.size}",
            0 to "巡回 $planCount",
            1 to "追跡 ${state.targets.size}",
            3 to "候補 ${state.candidateRules.count { it.enabled }}",
            5 to "順番待ち ${waitlist.count { it.status == WaitlistStatus.WATCHING }}",
            2 to if (state.unreadEvents == 0) "履歴" else "履歴 ${state.unreadEvents}",
        ), section) { section = it } }
        if (!state.loaded && state.error == null) item { CircularProgressIndicator() }
        (error ?: state.error)?.let { message -> item {
            Text(message, color = MaterialTheme.colorScheme.error)
            if (!state.loaded) TextButton(onClick = app.radar::reload, enabled = !state.running) { Text("再試行") }
        } }
        when (section) {
            4 -> {
                if (liveRooms.isEmpty()) item { RadarEmpty(
                    if (planCount == 0 && state.candidateRules.none { it.enabled }) "巡回か候補条件を有効にすると、条件に合う部屋がここに並びます。"
                    else "いま条件に合う部屋はありません。新着を確認すると、ここに並びます。",
                    if (planCount == 0) "巡回を設定" else null) { section = 0 } }
                items(liveRooms, key = { "live/${roomIdentity(it)}" }) { room ->
                    io.github.springthief1123.lovelyspace.ui.rooms.RoomCard(room,
                        onClick = { when (room.action) { RoomAction.ENTER -> onEnterRoom(room); RoomAction.PEEK -> onPeekRoom(room); RoomAction.NONE -> liveDetails = room } },
                        onDetailsClick = { liveDetails = room }, isFavorite = preferences.isFavorite(room),
                        actionsEnabled = preferences.canEdit(room), onFavoriteClick = { preferencesVm.toggleFavorite(room) }, onHideClick = { preferencesVm.hide(room) })
                }
            }
            0 -> {
                if (savedState.loading) item { CircularProgressIndicator() }
                if (!savedState.loading && savedState.loadError == null && saved.isEmpty()) item {
                    RadarEmpty("「見つける」の絞り込みで条件を保存すると、ここで巡回を有効にできます。", "条件を探す", onFindRooms)
                }
                if (saved.isNotEmpty()) item { RadarNote("有効にした条件は、画面を開いている間に新着を優先して全ページを巡回します。「︙」から背景でも巡回するを選ぶと、アプリを閉じていても新着の1ページ目を確認し、新しく一致した部屋を通知します。") }
                if (state.activeBackgroundPlans.isNotEmpty()) item {
                    RadarDropdown("背景で巡回する間隔", state.backgroundIntervalMinutes.toString(),
                        RadarState.BACKGROUND_INTERVALS.map { it.toString() to if (it < 60) "${it}分ごと" else "${it / 60}時間ごと" },
                        state.loaded && !working) { value -> action { app.radar.setBackgroundInterval(value.toInt()) } }
                }
                items(saved, key = { it.id }) { preset ->
                    val found = state.resultFor(preset)
                    val background = preset.id in state.activeBackgroundPlans
                    RadarRuleRow(
                        title = preset.label,
                        subtitle = listOfNotNull(Genres[preset.genreKey]?.label ?: preset.genreKey, if (background) "背景でも巡回" else null).joinToString("・"),
                        enabled = preset.id in state.plans,
                        switchEnabled = state.loaded && !working && !state.running,
                        onEnabled = { enabled -> action { app.radar.setPlan(preset.id, enabled) } },
                        status = if (found == null) "まだ確認していません" else state.scopes[preset.id].orEmpty(),
                        matches = found?.rooms?.size,
                        onMatches = { found?.let { result = RadarRoomsDisplay("確認した一致", it.at, it.page, it.lastPage, it.rooms) } },
                        menuLabel = "計画の操作",
                        menu = listOf(
                            QuietMenuItem("計画を編集", enabled = !state.running && !working && !savedState.working) { savedVm.clearEditError(); editing = preset },
                            QuietMenuItem(if (background) "背景の巡回をやめる" else "背景でも巡回する", enabled = state.loaded && !working && !state.running) {
                                // 背景の巡回は一致を端末通知で知らせるので、オンにするときに通知の許可も求める。
                                if (!background) requestNotifications()
                                action { app.radar.setPlanBackground(preset.id, !background) }
                            },
                        ),
                    )
                }
            }
            1 -> {
                if (state.targets.isEmpty()) item {
                    RadarEmpty("部屋の詳細から「この部屋を追跡」を選ぶと、一覧で確認できる部屋の変化を記録します。人の本人確認ではありません。", "部屋を見つける", onFindRooms)
                }
                if (state.targets.isNotEmpty()) item {
                    RadarDropdown("並べ替え", state.targetSort.name, RadarTargetSort.entries.map { it.name to when (it) {
                        RadarTargetSort.LAST_CONFIRMED -> "プロフィール照合が新しい順"
                        RadarTargetSort.LAST_OBSERVED -> "IDの確認が新しい順"
                        RadarTargetSort.NAME -> "名前順"
                    } }, state.loaded && !working) { value -> action { app.radar.setTargetSort(RadarTargetSort.valueOf(value)) } }
                }
                items(state.targets.organized(state.targetSort), key = { roomIdentity(it.identity) }) { target ->
                    RadarTargetCard(
                        target = target,
                        openEnabled = !working,
                        editEnabled = state.loaded && !working,
                        onOpen = { selected = RadarRoomSnapshot(target.identity, target.confirmedAt, target.observedPage, target.evidence == RoomIdentityEvidence.REUSED, target.sourceQuery) },
                        onPin = { action { app.radar.updateTarget(target.identity, pinned = !target.pinned) } },
                        onNote = { editingTargetKey = roomIdentity(target.identity); error = null },
                        onRemove = { removingTargetKey = roomIdentity(target.identity); error = null })
                }
            }
            3 -> {
                item { QuietSectionHeader("候補条件", "名前で部屋を探します。部屋の追跡とは別の記録です。") {
                    TextButton(enabled = state.loaded && !working && state.candidateRules.size < 20 && !state.running, onClick = {
                        error = null
                        candidateDraft = CandidateRule(label = "", genreKey = Genres.default.key, term = "")
                    }) { Text("追加") }
                } }
                if (state.candidateRules.isEmpty()) item { RadarEmpty("候補条件はまだありません。名前の一致や表示名の文字列で、新着の部屋を見つけられます。名前非表示の部屋は判定しません。") }
                items(state.candidateRules, key = { "candidate/${it.id}" }) { rule ->
                    val found = state.resultFor(rule)
                    RadarRuleRow(
                        title = rule.label,
                        subtitle = "${Genres[rule.genreKey]?.label ?: rule.genreKey}・${if (rule.mode == CandidateMode.EXACT_NAME) "名前が「${rule.term}」" else "表示名に「${rule.term}」"}",
                        enabled = rule.enabled,
                        switchEnabled = state.loaded && !working,
                        onEnabled = { enabled -> action { app.radar.setCandidateEnabled(rule.id, enabled) } },
                        status = if (found == null) "まだ確認していません" else "${formatObservationTime(found.at)}・${found.page}/${found.lastPage}ページ",
                        matches = found?.rooms?.size,
                        onMatches = { found?.let { result = RadarRoomsDisplay("確認した候補", it.at, it.page, it.lastPage, it.rooms) } },
                        menuLabel = "候補条件の操作",
                        menu = listOf(
                            QuietMenuItem("条件を編集", enabled = state.loaded && !working && !state.running) { error = null; candidateDraft = rule },
                            QuietMenuItem("条件を削除", enabled = state.loaded && !working, destructive = true) { error = null; deletingCandidate = rule },
                        ),
                    )
                }
            }
            5 -> {
                item { RadarNote("満室の部屋の詳細から「空いたら知らせる」を選ぶと、${WaitlistRepository.DEFAULT_HOURS}時間まで空きを待ちます（同時に${WaitlistRepository.MAX_ACTIVE}件まで）。アプリを閉じている間は15分ごとに確認します。入室とロボット確認はご自身で行ってください。") }
                if (waitlist.isEmpty()) item { RadarEmpty("順番待ちはまだありません。", "部屋を見つける", onFindRooms) }
                items(waitlist, key = { "waitlist/${it.key}" }) { entry ->
                    WaitlistRow(entry, onEnter = { entry.openedRoom?.let(onEnterRoom) },
                        onRemove = {
                            val index = waitlist.indexOf(entry)
                            scope.launch {
                                try { app.waitlist.remove(entry.key) }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) { error = "順番待ちを取り消せませんでした。もう一度お試しください。"; return@launch }
                                val label = if (entry.active(System.currentTimeMillis())) "順番待ちを取り消しました" else "記録を削除しました"
                                if (snackbar.showSnackbar(label, actionLabel = "元に戻す", withDismissAction = true) == SnackbarResult.ActionPerformed) {
                                    try { app.waitlist.restore(entry, index) }
                                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                    catch (e: Exception) { error = e.message ?: "順番待ちを元に戻せませんでした。" }
                                }
                            }
                        })
                }
            }
            2 -> {
                item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("未読 ${state.unreadEvents}件・表示 ${visibleEvents.size}件", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(enabled = state.loaded && !working && state.unreadEvents > 0, onClick = {
                            val ids = state.events.filterNot { it.read }.map { it.id }.toSet()
                            action { app.radar.markEventsRead(ids) }
                        }) { Text("すべて既読にする") }
                    }
                    FilterChip(historyUnread, { historyUnread = !historyUnread }, label = { Text("未読のみ") })
                    QuietFieldPair(
                        { m -> Box(m) { RadarDropdown("種類", historyKind, listOf("" to "すべて") + RadarEventKind.entries.map { it.name to it.historyLabel() }, true) { historyKind = it } } },
                        { m -> Box(m) { RadarDropdown("条件・追跡先", historyOrigin, listOf("" to "すべて") + historyOriginOptions(state.events), true) { historyOrigin = it } } },
                    )
                } }
                if (visibleEvents.isEmpty()) item {
                    if (state.events.isEmpty()) RadarEmpty("変化の履歴はまだありません。初回確認以降の変化を端末内に記録します。")
                    else RadarEmpty("この絞り込みに合う履歴はありません。", "絞り込みを解除") { historyUnread = false; historyKind = ""; historyOrigin = "" }
                }
                items(visibleEvents, key = { it.id }) { event -> QuietListPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!event.read) Box(Modifier.padding(end = 6.dp).size(8.dp)
                            .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape))
                        Text(listOfNotNull(event.kind.historyLabel(), event.origin?.historyLabel()).joinToString("・"),
                            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (event.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f))
                        Text(formatObservationTime(event.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        QuietOverflowMenu(listOfNotNull(
                            if (!event.read) QuietMenuItem("既読にする", enabled = state.loaded && !working) { action { app.radar.markEventsRead(setOf(event.id)) } } else null,
                        ), contentDescription = "履歴の操作")
                        if (event.read) Spacer(Modifier.width(10.dp))
                    }
                    Text(event.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 10.dp))
                    if (event.rooms.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        event.rooms.forEach { room ->
                            AssistChip(enabled = !working && (event.read || state.loaded), onClick = {
                                val snapshot = RadarRoomSnapshot(room, event.at, event.page ?: 1, event.blocked, event.sourceQuery)
                                if (event.read) selected = snapshot else action { app.radar.markEventsRead(setOf(event.id)); selected = snapshot }
                            }, label = { Text(room.name ?: "記録の部屋", maxLines = 1, overflow = TextOverflow.Ellipsis) })
                        }
                    }
                    if (event.rooms.size == 20) Text("履歴には最大20件を保存しています。", style = MaterialTheme.typography.bodySmall)
                } }
            }
        }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = lovelyMainContentBottomInset()))
    }
    state.targets.firstOrNull { roomIdentity(it.identity) == editingTargetKey }?.let { target ->
        RadarTargetNoteEditor(target, working, error, onDismiss = { editingTargetKey = null },
            onSave = { note -> action { app.radar.updateTarget(target.identity, note = note); editingTargetKey = null } })
    }
    state.targets.firstOrNull { roomIdentity(it.identity) == removingTargetKey }?.let { target ->
        AlertDialog(onDismissRequest = { if (!working) removingTargetKey = null }, title = { Text("追跡を解除しますか？") },
            text = { Column { Text("${target.identity.name}の追跡と自分用メモを削除します。変化の履歴は残ります。")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
            confirmButton = { TextButton(enabled = !working, onClick = { action { app.radar.removeTarget(target.room); removingTargetKey = null } }) { Text("追跡を解除") } },
            dismissButton = { TextButton(enabled = !working, onClick = { removingTargetKey = null }) { Text("戻る") } })
    }
    editing?.let { value ->
        RadarPlanEditor(value, savedState.working, savedState.editError,
            onDismiss = { editing = null }, onSave = { savedVm.save(it) { editing = null } })
    }
    if (pauseConfirm) AlertDialog(onDismissRequest = { if (!working) pauseConfirm = false }, title = { Text("計画・候補監視をすべて停止しますか？") },
        text = { Column {
            Text("計画・候補条件と確認済みの結果は残します。実行中の通信は完了させ、以降の計画・候補ページは取得しません。背景の巡回も止めます。部屋の追跡設定は保持します。")
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
    liveDetails?.let { room -> RoomDetailsSheet(room, preferences.isFavorite(room), preferences.canEdit(room),
        onDismiss = { liveDetails = null }, onFavorite = { preferencesVm.toggleFavorite(room) },
        onHide = { preferencesVm.hide(room); liveDetails = null }, onEnter = onEnterRoom, onPeek = onPeekRoom) }
    selected?.let { snapshot ->
        RadarRoomSheet(snapshot, onDismiss = { selected = null }, onFindRooms, onEnterRoom, onPeekRoom)
    }
}

/** 巡回計画・候補条件の1行。スイッチと一致件数を右に寄せ、編集・削除は「︙」にまとめる。 */
@Composable
private fun RadarRuleRow(
    title: String, subtitle: String, enabled: Boolean, switchEnabled: Boolean, onEnabled: (Boolean) -> Unit,
    status: String, matches: Int?, onMatches: () -> Unit, menuLabel: String, menu: List<QuietMenuItem>,
) {
    QuietListPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Switch(enabled, onEnabled, enabled = switchEnabled, modifier = Modifier.padding(start = 8.dp))
            QuietOverflowMenu(menu, contentDescription = menuLabel)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (matches != null && matches > 0) TextButton(onClick = onMatches) { Text("一致 ${matches}件") }
            else Spacer(Modifier.width(10.dp))
        }
    }
}

/** 順番待ちの1件。空いたら入室へ進めるようにし、取り消し・削除は「︙」にまとめる。 */
@Composable
private fun WaitlistRow(entry: WaitlistEntry, onEnter: () -> Unit, onRemove: () -> Unit) {
    val now = System.currentTimeMillis()
    QuietListPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.openedRoom?.name ?: entry.room.name ?: "会話中の部屋", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(Genres[entry.room.genreKey]?.label, entry.room.message.takeIf { it.isNotBlank() }).joinToString("・"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            QuietOverflowMenu(listOf(QuietMenuItem(if (entry.active(now)) "順番待ちを取り消す" else "記録を削除", destructive = entry.active(now), onClick = onRemove)),
                contentDescription = "順番待ちの操作")
        }
        Text(when {
            entry.status == WaitlistStatus.OPENED -> "${formatObservationTime(entry.changedAt ?: now)}に空きを確認しました。入室時には埋まっている場合があります。"
            entry.status == WaitlistStatus.STOPPED -> "同じIDに別の部屋を確認したため、待つのをやめました。"
            !entry.active(now) -> "期限（${formatObservationTime(entry.expiresAt)}）を過ぎました。"
            entry.missed > 0 -> "満室を待っています・最近の確認では一覧に見つかっていません（閉鎖とは限りません）・期限 ${formatObservationTime(entry.expiresAt)}"
            else -> "満室を待っています・${entry.lastSeenAt?.let { formatObservationTime(it) } ?: "-"}に確認・期限 ${formatObservationTime(entry.expiresAt)}"
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (entry.status == WaitlistStatus.OPENED) FilledTonalButton(onClick = onEnter, modifier = Modifier.fillMaxWidth()) { Text("入室へ進む") }
    }
}

@Composable
private fun RadarNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** タブが空のときの案内。操作は1つだけにする。 */
@Composable
private fun RadarEmpty(text: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        actionLabel?.let { OutlinedButton(onClick = onAction) { Text(it) } }
    }
}
