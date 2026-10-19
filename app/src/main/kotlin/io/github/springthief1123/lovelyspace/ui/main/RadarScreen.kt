package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.ui.components.QuietNotice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import kotlin.math.roundToInt

/** 一致の一覧のシート。[scope] は見出しの下に出す範囲（「3/5ページの一覧情報」など）。 */
/** [pageOf] は部屋ごとの、載っていたページ。 */
private data class RadarRoomsDisplay(val title: String, val at: Long, val pageOf: (Room) -> Int, val scope: String, val rooms: List<Room>)

/** 条件の 1 件に、手元の一覧（全ページ）でいま一致している部屋と、まだ見ていない新しい一致。 */
private data class ConditionMatches(val rooms: List<Room>, val fresh: List<Room>, val pageOf: Map<String, Int>)

/**
 * レーダー。気になる部屋（ピン）と保存した条件の 2 つを見張る（Yuya の決定、2026-10-19。案 A「レーダーに一本化」）。
 * 先頭の「動きあり」に、空いたピン・閉じたピンに似た部屋・条件の新着を、押せばすぐ動ける形で並べる。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
fun RadarScreen(onFindRooms: () -> Unit, onEnterRoom: (Room) -> Unit, onPeekRoom: (Room) -> Unit, onApplyPreset: (SearchPreset) -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val state by app.radar.state.collectAsStateWithLifecycle()
    val savedVm: SavedSearchViewModel = viewModel(factory = viewModelFactory { initializer { SavedSearchViewModel(app.searchPresets) } })
    val savedState by savedVm.state.collectAsStateWithLifecycle()
    val saved = savedState.presets
    val preferencesVm: io.github.springthief1123.lovelyspace.ui.rooms.RoomPreferenceViewModel = viewModel(
        factory = viewModelFactory { initializer { io.github.springthief1123.lovelyspace.ui.rooms.RoomPreferenceViewModel(app.roomPreferences) } })
    val preferences by preferencesVm.state.collectAsStateWithLifecycle()
    val ng by app.ngWords.filter.collectAsStateWithLifecycle()
    val pins by app.pins.pins.collectAsStateWithLifecycle()
    val pinsLoaded by app.pins.loaded.collectAsStateWithLifecycle()
    val observations by app.roomLists.observations.collectAsStateWithLifecycle()
    // 条件ごとの一致は、見つける画面と同じく手元にあるジャンルの全ページから出す（非表示・NG ワードの部屋は外す）。
    // 新着は、巡回で記録した新しい一致のうちまだ見ていないもので、いまも一致している部屋。
    val conditions = remember(observations, saved, ng, preferences, state.events) {
        val windows = saved.mapNotNull { Genres[it.genreKey] }.distinct().associateWith { observations.genreWindow(it) }
        saved.associate { preset ->
            val window = Genres[preset.genreKey]?.let(windows::get)
            val rooms = window?.let { w -> searchRooms(w.rooms.filter { !preferences.isHidden(it) && !ng.matches(it) }, preset.criteria) }.orEmpty()
            val unseen = state.events.filter { !it.read && it.kind == RadarEventKind.SEARCH_MATCH && it.origin?.let { o -> o.type == RadarOriginType.PLAN && o.id == preset.id } == true }
                .flatMap { it.rooms }.mapTo(HashSet(), ::roomIdentity)
            preset.id to ConditionMatches(rooms, rooms.filter { roomIdentity(it) in unseen }, window?.roomPages.orEmpty())
        }
    }
    val scope = rememberCoroutineScope()
    var scanJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    ForegroundPolling(state.automatic, onStop = { scanJob?.cancel() }) { app.radar.monitor { app.pacing.value } }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var section by rememberRadarSection()
    var selected by remember { mutableStateOf<RadarRoomSnapshot?>(null) }
    var result by remember { mutableStateOf<RadarRoomsDisplay?>(null) }
    var details by remember { mutableStateOf<Room?>(null) }
    var editing by rememberSaveable(stateSaver = SearchPresetSaver) { mutableStateOf<SearchPreset?>(null) }
    var deleting by rememberSaveable(stateSaver = SearchPresetSaver) { mutableStateOf<SearchPreset?>(null) }
    var pauseConfirm by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var candidateDraft by rememberSaveable(stateSaver = CandidateRuleSaver) { mutableStateOf<CandidateRule?>(null) }
    var deletingCandidate by rememberSaveable(stateSaver = CandidateRuleSaver) { mutableStateOf<CandidateRule?>(null) }
    var expandedPin by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedCondition by rememberSaveable { mutableStateOf<String?>(null) }
    var memoPin by rememberSaveable { mutableStateOf<String?>(null) }
    // 操作の失敗（error）を「再試行」したときに、失敗した操作そのものをやり直す。やり直せない失敗では null。
    var retry by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun fail(message: String, again: (() -> Unit)? = null) { error = message; retry = again }
    fun action(block: suspend () -> Unit) {
        if (working) return
        working = true
        scope.launch {
            try { block(); error = null; retry = null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { fail("設定を保存できませんでした。もう一度お試しください。", again = { action(block) }) }
            finally { working = false }
        }
    }
    /** 本家の「最新更新」と同じく、巡回中・確認中でも押すたびに取り直す（まとめ方と間隔は [RadarRepository.scanNow]）。 */
    fun scan() {
        val active = scanJob?.isActive == true
        val job = scope.launch {
            try { app.radar.scanNow() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { fail("レーダーの状態を確認できませんでした。もう一度お試しください。", again = { scan() }) }
        }
        // 画面を離れたときに止めるのは、実際に取り直している最初の呼び出し。
        if (!active) scanJob = job
    }
    fun openRoom(room: Room) {
        when (room.action) { RoomAction.ENTER -> onEnterRoom(room); RoomAction.PEEK -> onPeekRoom(room); RoomAction.NONE -> details = room }
    }
    /** 条件を開いたら、その条件の新着は見たことにする。 */
    fun openCondition(preset: SearchPreset) {
        expandedCondition = if (expandedCondition == preset.id) null else preset.id
        val ids = state.events.filter { !it.read && it.origin?.let { o -> o.type == RadarOriginType.PLAN && o.id == preset.id } == true }.map { it.id }.toSet()
        if (ids.isNotEmpty() && state.loaded) scope.launch { runCatching { app.radar.markEventsRead(ids) } }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(preferencesVm) {
        preferencesVm.hiddenRooms.collect { room ->
            if (snackbar.showSnackbar("部屋を非表示にしました", actionLabel = "元に戻す", withDismissAction = true) == SnackbarResult.ActionPerformed) preferencesVm.unhide(room)
        }
    }
    // ピンを外すのは 1 回の操作で起きるので「元に戻す」を出す。知らせ方・空いた記録は残してあるので、戻せばそのまま続く。
    LaunchedEffect(preferencesVm) {
        preferencesVm.unfavorited.collect { value ->
            if (snackbar.showSnackbar("ピンを外しました", actionLabel = "元に戻す", withDismissAction = true) == SnackbarResult.ActionPerformed) preferencesVm.restoreFavorite(value)
        }
    }
    val planCount = saved.count { it.id in state.plans }
    val requestNotifications = io.github.springthief1123.lovelyspace.notify.rememberNotificationPermissionRequest()
    // 背景の巡回は一致を通知で知らせる。通知が出せないなら、巡回していても知らせられないことを伝える。
    val radarNotificationsAllowed = io.github.springthief1123.lovelyspace.notify.rememberNotificationsAllowed(
        io.github.springthief1123.lovelyspace.notify.NotificationKind.RADAR_MATCH)
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val opened = pins.filter { it.state == PinState.OPEN && it.record.notifies }
    val similar = pins.filter { it.similar != null }
    val freshConditions = saved.filter { conditions[it.id]?.fresh?.isNotEmpty() == true }
    val moving = opened.size + similar.size + if (freshConditions.isEmpty()) 0 else 1
    QuietPage {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = LovelySpacing.screenHorizontal, end = LovelySpacing.screenHorizontal,
        top = lovelyMainContentTopPadding(), bottom = lovelyMainContentBottomInset() + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { QuietHeading("レーダー") }
        savedState.loadError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error); TextButton(onClick = savedVm::reload) { Text("条件を読み直す") } } }
        item { RadarDashboard(state, pins.size, planCount, working,
            onScan = ::scan, onPause = { pauseConfirm = true }, onAutomatic = app.radar::automatic, onHistory = { showHistory = true }) }
        if (!state.loaded && state.error == null) item { CircularProgressIndicator() }
        // 読み込みの失敗は読み込み直し、操作の失敗は失敗した操作をやり直す「再試行」を、読み込み済みでも出す。
        // 操作の失敗は閉じることもできる。
        (error ?: state.error)?.let { message -> item {
            val again = if (error != null) retry else ({ app.radar.reload() })
            QuietNotice(message, error = true, onDismiss = if (error != null) ({ error = null; retry = null }) else null,
                actionLabel = again?.let { "再試行" }, actionEnabled = !state.running && !working,
                onAction = again?.let { run -> { error = null; retry = null; run() } })
        } }
        if (moving > 0) {
            item(key = "moving") { QuietSectionHeader("動きあり", "${moving}件") }
            items(opened, key = { "moving/open/${it.key}" }) { pin ->
                MovingRow(Icons.Outlined.MeetingRoom, "${pin.name}の部屋が空いています",
                    listOfNotNull(pin.record.stateAt?.let(::formatObservationTime), pinAlertLabel(pin.record.alert)).joinToString("・")) {
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { openRoom(pin.room) }) { Text(if (pin.room.action == RoomAction.PEEK) "覗く" else "入室") }
                }
            }
            items(similar, key = { "moving/similar/${it.key}" }) { pin ->
                val candidate = pin.similar!!
                MovingRow(Icons.Outlined.Replay, "${pin.name}に似た部屋が建ちました",
                    "待機メッセージが${(candidate.similarity * 100).roundToInt()}%同じ・${candidate.room.name ?: "名前の見えない部屋"}",
                    quote = candidate.room.message) {
                    TextButton(enabled = !working, onClick = { action { app.pins.dismiss(pin.key, roomIdentity(candidate.room)) } }) { Text("別の人") }
                    Spacer(Modifier.weight(1f))
                    Button(enabled = !working, onClick = { action { app.pins.adopt(pin, candidate.room) } }) { Text("同じ人として引き継ぐ") }
                }
            }
            if (freshConditions.isNotEmpty()) item(key = "moving/conditions") {
                MovingRow(Icons.Outlined.AutoAwesome, "条件に新着 ${freshConditions.sumOf { conditions[it.id]?.fresh?.size ?: 0 }}件",
                    freshConditions.joinToString("・") { "${it.label} ${conditions[it.id]?.fresh?.size ?: 0}" }) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { section = RadarSection.CONDITIONS; if (expandedCondition != freshConditions.first().id) openCondition(freshConditions.first()) }) { Text("見る") }
                }
            }
        }
        item { QuietTabs(listOf(
            RadarSection.PINS to "ピン ${pins.size}",
            RadarSection.CONDITIONS to "条件 ${saved.size}",
        ), section) { section = it } }
        when (section) {
            RadarSection.PINS -> {
                if (!pinsLoaded && preferences.loading) item { CircularProgressIndicator() }
                if (pinsLoaded && pins.isEmpty()) item {
                    RadarEmpty("ピンした部屋はまだありません。部屋カードを右へスワイプするか、長押しのメニューから「ピン」を選ぶと加わります。", "部屋を探す", onFindRooms)
                }
                items(pins, key = { "pin/${it.key}" }) { pin ->
                    PinCard(pin, expanded = expandedPin == pin.key, enabled = !working && preferences.canEdit(pin.room), now = now,
                        onToggle = { expandedPin = if (expandedPin == pin.key) null else pin.key },
                        onAlert = { alert ->
                            if (alert != PinAlert.OFF) requestNotifications()
                            action {
                                if (alert == PinAlert.AUTO) app.autoEntry.resetTarget(AutoEntryController.pinTarget(pin.key))
                                app.pins.setAlert(pin.key, alert)
                            }
                        },
                        onOpen = { openRoom(pin.room) },
                        onDetails = { details = pin.room },
                        onMemo = { memoPin = pin.key; error = null },
                        onUnpin = { preferencesVm.clearFavorite(pin.preference) })
                }
                if (pins.isNotEmpty()) item { RadarNote("空いた部屋・似た部屋が建った部屋が上に来ます。閉じた部屋が同じ名前・待機メッセージで立て直されたら、ピンは新しい部屋へ移ります。待機メッセージを少し書き換えた部屋は、同じ人か確かめてから引き継ぎます。") }
                item(key = "pins/names") { QuietSectionHeader("名前で待つ", "その名前の部屋が建ったら見つけます。名前が見えない部屋は判定しません。") {
                    TextButton(enabled = state.loaded && !working && state.candidateRules.size < 20 && !state.running, onClick = {
                        error = null
                        candidateDraft = CandidateRule(label = "", genreKey = Genres.default.key, term = "")
                    }) { Text("追加") }
                } }
                if (state.candidateRules.isEmpty()) item { RadarEmpty("名前で待つ条件はまだありません。") }
                items(state.candidateRules, key = { "candidate/${it.id}" }) { rule ->
                    val found = state.resultFor(rule)
                    RadarRuleRow(
                        title = rule.label,
                        switchLabel = "「${rule.label}」の部屋を待つ",
                        subtitle = "${Genres[rule.genreKey]?.label ?: rule.genreKey}・${if (rule.mode == CandidateMode.EXACT_NAME) "名前が「${rule.term}」" else "表示名に「${rule.term}」"}",
                        enabled = rule.enabled,
                        switchEnabled = state.loaded && !working,
                        onEnabled = { enabled -> action { app.radar.setCandidateEnabled(rule.id, enabled) } },
                        status = (if (found == null) "まだ確認していません" else "${formatObservationTime(found.at)}・${found.pagesRead}/${found.lastPage}ページ分") +
                            if (rule.autoEnterActive(now)) "・新しい部屋に自動で入室（${formatObservationTime(rule.autoEnterUntil)}まで）" else "",
                        matches = found?.rooms?.size,
                        onMatches = { found?.let { result = RadarRoomsDisplay("いま一致している部屋", it.at, it::pageOf, "${it.pagesRead}/${it.lastPage}ページ分の一覧情報", it.rooms) } },
                        menuLabel = "名前で待つ条件の操作",
                        menu = listOf(
                            QuietMenuItem("条件を編集", enabled = state.loaded && !working && !state.running) { error = null; candidateDraft = rule },
                        ) + (if (rule.mode != CandidateMode.EXACT_NAME) emptyList<QuietMenuItem>() else if (rule.autoEnterActive(now))
                            listOf(QuietMenuItem("自動入室をやめる", enabled = state.loaded && !working) { action { app.radar.setCandidateAutoEnter(rule.id, false) } })
                        else listOf(QuietMenuItem("新しい部屋に自動で入室する（${AutoEntryController.PINNED_MS / 3_600_000}時間）", enabled = state.loaded && !working && rule.enabled) {
                            action { app.autoEntry.resetTarget(AutoEntryController.candidateTarget(rule.id)); app.radar.setCandidateAutoEnter(rule.id, true) }
                        })) + listOf(
                            QuietMenuItem("条件を削除", enabled = state.loaded && !working, destructive = true) { error = null; deletingCandidate = rule },
                        ),
                    )
                }
            }
            RadarSection.CONDITIONS -> {
                if (savedState.loading) item { CircularProgressIndicator() }
                if (!savedState.loading && savedState.loadError == null && saved.isEmpty()) item {
                    RadarEmpty("保存した条件はまだありません。「見つける」の検索で条件を保存すると加わります。", "条件を探す", onFindRooms)
                }
                if (state.activeBackgroundPlans.isNotEmpty() && !radarNotificationsAllowed) item {
                    QuietNotice(backgroundRadarNoticeText(), error = true, actionLabel = "通知の設定を開く",
                        onAction = { context.startActivity(io.github.springthief1123.lovelyspace.notify.appNotificationSettingsIntent(context)) })
                }
                savedState.editError?.takeIf { editing == null && deleting == null }?.let { message -> item {
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } }
                items(saved, key = { "plan/${it.id}" }) { preset ->
                    val found = conditions[preset.id] ?: ConditionMatches(emptyList(), emptyList(), emptyMap())
                    val patrolling = preset.id in state.plans
                    val notifying = preset.id in state.activeBackgroundPlans
                    ConditionCard(preset, found, expanded = expandedCondition == preset.id, patrolling = patrolling, notifying = notifying,
                        switchEnabled = state.loaded && !working && !state.running,
                        onToggle = { openCondition(preset) },
                        onNotify = { on ->
                            // 新着の通知は背景の巡回で出すので、オンにするときは巡回もオンにし、通知の許可も求める。
                            if (on) requestNotifications()
                            action {
                                if (on && !patrolling) app.radar.setPlan(preset.id, true)
                                app.radar.setPlanBackground(preset.id, on)
                            }
                        },
                        menu = listOf(
                            QuietMenuItem("見つけるで開く") { onApplyPreset(preset) },
                            QuietMenuItem(if (patrolling) "巡回をやめる" else "巡回する", enabled = state.loaded && !working && !state.running) {
                                action { app.radar.setPlan(preset.id, !patrolling) }
                            },
                            QuietMenuItem("条件を編集", enabled = !state.running && !working && !savedState.working) { savedVm.clearEditError(); editing = preset },
                            QuietMenuItem("削除", enabled = !savedState.working, destructive = true) { savedVm.clearEditError(); deleting = preset },
                        ),
                    ) {
                        if (found.rooms.isEmpty()) RadarNote(if (patrolling) "いま一致している部屋はありません。" else "巡回していないので、見つける画面などで読んだ一覧だけで数えています。")
                        val freshKeys = found.fresh.mapTo(HashSet(), ::roomIdentity)
                        found.rooms.take(CONDITION_PREVIEW).forEach { room ->
                            io.github.springthief1123.lovelyspace.ui.rooms.RoomCard(room,
                                onClick = { openRoom(room) },
                                onDetailsClick = { details = room }, isFavorite = preferences.isFavorite(room),
                                actionsEnabled = preferences.canEdit(room), onFavoriteClick = { preferencesVm.toggleFavorite(room) }, onHideClick = { preferencesVm.hide(room) })
                            if (roomIdentity(room) in freshKeys) Text("新着", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp))
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (found.rooms.size > CONDITION_PREVIEW) Text("ほか${found.rooms.size - CONDITION_PREVIEW}件", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { onApplyPreset(preset) }) { Text("見つけるで開く") }
                        }
                    }
                }
                if (saved.isNotEmpty()) item { RadarNote("条件を押すと、いま一致している部屋がその場で開きます。巡回する条件は画面を開いている間に全ページを回り、「新着を知らせる」をオンにすると、アプリを閉じていても新着の1ページ目を確認して通知します。") }
                if (state.activeBackgroundPlans.isNotEmpty()) item {
                    RadarDropdown("アプリを閉じている間に確認する間隔", state.backgroundIntervalMinutes.toString(),
                        RadarState.BACKGROUND_INTERVALS.map { it.toString() to if (it < 60) "${it}分ごと" else "${it / 60}時間ごと" },
                        state.loaded && !working) { value -> action { app.radar.setBackgroundInterval(value.toInt()) } }
                }
            }
        }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = lovelyMainContentBottomInset()))
    }
    pins.firstOrNull { it.key == memoPin }?.let { pin ->
        PinMemoEditor(pin, working, error, onDismiss = { memoPin = null },
            onSave = { memo -> action { app.pins.setMemo(pin.key, memo); memoPin = null } })
    }
    editing?.let { value ->
        RadarPlanEditor(value, savedState.working, savedState.editError,
            onDismiss = { editing = null }, onSave = { savedVm.save(it) { editing = null } })
    }
    deleting?.let { value ->
        QuietConfirmDialog(title = "保存した条件を削除しますか？", text = value.label,
            confirmLabel = "削除", onConfirm = { savedVm.delete(value.id) { deleting = null } }, onDismiss = { deleting = null },
            destructive = true, enabled = !savedState.working, error = savedState.editError)
    }
    if (pauseConfirm) QuietConfirmDialog(title = "条件の巡回をすべて止めますか？",
        text = "保存した条件と名前で待つ条件、確認済みの結果は残します。実行中の通信は完了させ、以降の巡回のページは取得しません。アプリを閉じている間の確認も止めます。ピンの見張りは続けます。",
        confirmLabel = "すべて止める", onConfirm = { action { app.radar.pauseAllPlans(); pauseConfirm = false } },
        onDismiss = { pauseConfirm = false }, enabled = !working, error = error)
    candidateDraft?.let { value ->
        CandidateRuleEditor(value, working, error, onDismiss = { candidateDraft = null },
            onSave = { action { app.radar.saveCandidate(it); candidateDraft = null } })
    }
    deletingCandidate?.let { rule ->
        QuietConfirmDialog(title = "名前で待つ条件を削除しますか？", text = rule.label,
            confirmLabel = "削除", onConfirm = { action { app.radar.removeCandidate(rule.id); deletingCandidate = null } },
            onDismiss = { deletingCandidate = null }, destructive = true, enabled = !working, error = error)
    }
    if (showHistory) RadarHistorySheet(state, working, onDismiss = { showHistory = false },
        onRead = { ids -> action { app.radar.markEventsRead(ids) } },
        onRoom = { event, room ->
            val snapshot = RadarRoomSnapshot(room, event.at, event.page ?: 1, event.blocked, event.sourceQuery)
            showHistory = false
            if (event.read) selected = snapshot else action { app.radar.markEventsRead(setOf(event.id)); selected = snapshot }
        })
    result?.let { found ->
        QuietSheet(onDismissRequest = { result = null }) {
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = LovelySpacing.screenHorizontal, end = LovelySpacing.screenHorizontal, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    QuietSheetHeader(found.title, "${formatObservationTime(found.at)} · ${found.scope}")
                }
                items(found.rooms, key = { roomIdentity(it) }) { room ->
                    QuietPanel {
                        Text(room.name ?: "会話中の部屋", style = MaterialTheme.typography.titleSmall)
                        Text(room.message, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { result = null; selected = RadarRoomSnapshot(room, found.at, found.pageOf(room)) }) { Text("部屋の詳細を確認") }
                    }
                }
            }
        }
    }
    details?.let { room -> RoomDetailsSheet(room, preferences.isFavorite(room), preferences.canEdit(room),
        onDismiss = { details = null }, onFavorite = { preferencesVm.toggleFavorite(room) },
        onHide = { preferencesVm.hide(room); details = null }, onEnter = onEnterRoom, onPeek = onPeekRoom) }
    selected?.let { snapshot ->
        RadarRoomSheet(snapshot, onDismiss = { selected = null }, onFindRooms, onEnterRoom, onPeekRoom)
    }
}

/** 条件のカードに、開いたときに並べる部屋の数。それより多ければ「見つけるで開く」で全部を見る。 */
private const val CONDITION_PREVIEW = 10

/** 「動きあり」の 1 件。何が起きたかを 1 行で示し、その場で動けるボタンを下に並べる。 */
@Composable
private fun MovingRow(icon: ImageVector, title: String, detail: String, quote: String? = null, actions: @Composable RowScope.() -> Unit) {
    QuietListPanel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        quote?.takeIf { it.isNotBlank() }?.let {
            Text("「$it」", style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

/** ピンの様子の名前。 */
internal fun pinStateLabel(state: PinState): String = when (state) {
    PinState.OPEN -> "空いています"
    PinState.FULL -> "満室"
    PinState.CLOSED -> "閉じた"
    PinState.UNSEEN -> "未確認"
}

/** ピンの様子の小さな札。空き・満室は一覧の状態と同じ色にする。 */
@Composable
private fun PinStateTag(state: PinState) {
    val lovely = LocalLovelyColors.current
    val color = when (state) {
        PinState.OPEN -> lovely.waiting
        PinState.FULL -> lovely.full
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(pinStateLabel(state), style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1,
        modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp))
}

/**
 * ピンの 1 件。見出しを押すと、空いた時間帯・最後に空いた時刻と、入室・メモ・ピンを外すを開く。
 * 知らせ方はカードの下のチップから選ぶ。
 */
@Composable
private fun PinCard(pin: PinView, expanded: Boolean, enabled: Boolean, now: Long, onToggle: () -> Unit, onAlert: (PinAlert) -> Unit,
    onOpen: () -> Unit, onDetails: () -> Unit, onMemo: () -> Unit, onUnpin: () -> Unit) {
    val room = pin.room
    val record = pin.record
    QuietListPanel {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (expanded) "閉じる" else "詳しく見る", onClick = onToggle)
            .semantics { stateDescription = if (expanded) "開いています" else "閉じています" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(pin.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(Genres[room.genreKey]?.label,
                    when (room.gender) { Gender.FEMALE -> "女性"; Gender.MALE -> "男性"; Gender.UNKNOWN -> null },
                    room.age?.let { "${it}歳" }, room.area, pin.page?.let { "${it}ページ" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PinStateTag(pin.state)
        }
        if (room.message.isNotBlank()) Text(room.message, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) 6 else 2, overflow = TextOverflow.Ellipsis)
        if (record.memo.isNotBlank()) Text("メモ：${record.memo}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
            maxLines = if (expanded) 6 else 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PinAlertChip(record.alert, record.autoActive(now), enabled, onAlert)
            Text(listOfNotNull(
                record.vacancies.size.takeIf { it > 0 }?.let { "空き${it}回" },
                record.rebuilds.takeIf { it > 0 }?.let { "立て直し${it}回" },
                if (record.alert == PinAlert.AUTO && record.autoActive(now)) record.autoUntil?.let { "${formatObservationTime(it)}まで自動" } else null,
            ).joinToString("・"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (!expanded && pin.state == PinState.OPEN && room.action != RoomAction.NONE) Button(onClick = onOpen) { Text(if (room.action == RoomAction.PEEK) "覗く" else "入室") }
        }
        if (expanded) {
            HorizontalDivider(color = LocalLovelyColors.current.divider)
            val stats = VacancyStats.of(record.vacancies, now)
            if (stats.count == 0) RadarNote("まだ空いた記録はありません。満室・閉じた部屋が待機中になると、時刻を記録します。")
            else {
                Text("空いた時間帯（直近${VacancyStats.DAYS}日）" + (stats.peakHour?.let { "・多いのは${it}時台" } ?: ""), style = MaterialTheme.typography.labelMedium)
                VacancyHistogram(stats.hours)
                Text("最後に空いた：${stats.last?.let(::formatObservationTime) ?: "-"}・これまでに${stats.count}回", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (pin.state == PinState.CLOSED) RadarNote("閉じています。同じ名前・待機メッセージで立て直されたら、ピンを新しい部屋へ移します。")
            if (pin.state == PinState.UNSEEN) RadarNote("まだ一覧で確かめていません。そのジャンルの一覧を読むと分かります。")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (pin.state == PinState.OPEN && room.action != RoomAction.NONE) Button(onClick = onOpen) { Text(if (room.action == RoomAction.PEEK) "覗く" else "入室") }
                TextButton(onClick = onDetails) { Text("詳細") }
                TextButton(onClick = onMemo, enabled = enabled) { Text("メモ") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onUnpin, enabled = enabled) { Text("ピンを外す") }
            }
        }
    }
}

/** 0〜23 時の空いた回数の棒。読み上げでは多い時間帯を文で伝える（見出しにある）ので、図は飛ばす。 */
@Composable
private fun VacancyHistogram(hours: List<Int>) {
    val max = (hours.maxOrNull() ?: 0).coerceAtLeast(1)
    val color = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth().height(32.dp).clearAndSetSemantics {}, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            hours.forEach { count ->
                Box(Modifier.weight(1f).fillMaxHeight(if (count == 0) 0.08f else 0.2f + 0.8f * count / max)
                    .background(color.copy(alpha = if (count == 0) 0.12f else 0.35f + 0.65f * count / max), RoundedCornerShape(2.dp)))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("0時", "6時", "12時", "18時", "24時").forEachIndexed { i, label ->
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (i < 4) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** ピンのメモ。自分の端末内だけに残す。 */
@Composable
private fun PinMemoEditor(pin: PinView, working: Boolean, error: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var memo by rememberSaveable(pin.key) { mutableStateOf(pin.record.memo) }
    QuietSheet(onDismissRequest = { if (!working) onDismiss() }) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = LovelySpacing.screenHorizontal).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            QuietSheetHeader("${pin.name}のメモ", "自分の端末内に保存します。相手には送信されません。")
            OutlinedTextField(memo, { memo = it }, label = { Text("自分用メモ") }, enabled = !working, minLines = 3, maxLines = 6,
                isError = memo.length > PinRecord.MAX_MEMO, supportingText = { Text("${memo.length}/${PinRecord.MAX_MEMO}文字") }, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { onSave(memo) }, enabled = !working && memo.length <= PinRecord.MAX_MEMO, modifier = Modifier.fillMaxWidth()) { Text(if (working) "保存中…" else "メモを保存") }
            TextButton(onClick = onDismiss, enabled = !working) { Text("戻る") }
        }
    }
}

/**
 * 保存した条件の 1 件。見出しを押すと、いま一致している部屋（[content]）がその場で開く。
 * スイッチは「新着を知らせる」（アプリを閉じていても新着を確かめて通知する）。巡回・編集・削除は「︙」にまとめる。
 */
@Composable
private fun ConditionCard(preset: SearchPreset, found: ConditionMatches, expanded: Boolean, patrolling: Boolean, notifying: Boolean,
    switchEnabled: Boolean, onToggle: () -> Unit, onNotify: (Boolean) -> Unit, menu: List<QuietMenuItem>, content: @Composable ColumnScope.() -> Unit) {
    QuietListPanel {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (expanded) "閉じる" else "一致している部屋を見る", onClick = onToggle)
            .semantics { stateDescription = if (expanded) "開いています" else "閉じています" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(preset.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(Genres[preset.genreKey]?.label ?: preset.genreKey, radarPlanDescription(preset).ifBlank { null },
                    if (patrolling) "巡回中" else null).joinToString("・"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                if (found.fresh.isNotEmpty()) Text("新着 ${found.fresh.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("${found.rooms.size}件", style = MaterialTheme.typography.titleMedium)
                Text("いま一致", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            QuietOverflowMenu(menu, contentDescription = "「${preset.label}」の操作")
        }
        QuietSwitchRow(notifying, onNotify, title = if (notifying) "新着を知らせる" else "知らせない（開いたときだけ確認）", enabled = switchEnabled,
            contentPadding = PaddingValues(vertical = 2.dp), modifier = Modifier.semantics { contentDescription = "「${preset.label}」の新着を知らせる" })
        if (expanded) {
            HorizontalDivider(color = LocalLovelyColors.current.divider)
            content()
        }
    }
}

/** 変化の履歴。レーダーの右上から開く（いずれ通知ベルへ移す）。 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun RadarHistorySheet(state: RadarState, working: Boolean, onDismiss: () -> Unit, onRead: (Set<String>) -> Unit, onRoom: (RadarEvent, Room) -> Unit) {
    var historyUnread by rememberSaveable { mutableStateOf(false) }
    var historyKind by rememberSaveable { mutableStateOf("") }
    var historyOrigin by rememberSaveable { mutableStateOf("") }
    val historyFilter = RadarHistoryFilter(historyUnread, RadarEventKind.entries.firstOrNull { it.name == historyKind }, historyOrigin.ifBlank { null })
    val visibleEvents = state.events.filter { it.matches(historyFilter) }
    QuietSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = LovelySpacing.screenHorizontal, end = LovelySpacing.screenHorizontal, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { QuietSheetHeader("変化の履歴", "未読 ${state.unreadEvents}件・表示 ${visibleEvents.size}件") }
            item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    QuietFilterChip(historyUnread, { historyUnread = !historyUnread }, label = "未読のみ")
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = state.loaded && !working && state.unreadEvents > 0, onClick = {
                        onRead(state.events.filterNot { it.read }.map { it.id }.toSet())
                    }) { Text("すべて既読にする") }
                }
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
                    if (!event.read) Box(Modifier.padding(end = 6.dp).size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Text(listOfNotNull(event.kind.historyLabel(), event.origin?.historyLabel()).joinToString("・"),
                        style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (event.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f))
                    Text(formatObservationTime(event.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    QuietOverflowMenu(listOfNotNull(
                        if (!event.read) QuietMenuItem("既読にする", enabled = state.loaded && !working) { onRead(setOf(event.id)) } else null,
                    ), contentDescription = "履歴の操作")
                    if (event.read) Spacer(Modifier.width(10.dp))
                }
                Text(event.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 10.dp))
                if (event.rooms.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    event.rooms.forEach { room ->
                        QuietAssistChip(enabled = !working && (event.read || state.loaded), onClick = { onRoom(event, room) }, label = room.name ?: "記録の部屋")
                    }
                }
                if (event.rooms.size == 20) Text("履歴には最大20件を保存しています。", style = MaterialTheme.typography.bodySmall)
            } }
        }
    }
}

/**
 * 名前で待つ条件の1行。スイッチと一致件数を右に寄せ、編集・削除は「︙」にまとめる。
 * [switchLabel] はスイッチの読み上げ名で、どの条件のオン・オフかを伝える。
 */
@Composable
private fun RadarRuleRow(
    title: String, switchLabel: String, subtitle: String, enabled: Boolean, switchEnabled: Boolean, onEnabled: (Boolean) -> Unit,
    status: String, matches: Int?, onMatches: () -> Unit, menuLabel: String, menu: List<QuietMenuItem>,
) {
    QuietListPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Switch(enabled, onEnabled, enabled = switchEnabled,
                modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = switchLabel })
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

@Composable
private fun RadarNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 一覧が空のときの案内。操作は1つだけにする。 */
@Composable
private fun RadarEmpty(text: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        actionLabel?.let { OutlinedButton(onClick = onAction) { Text(it) } }
    }
}

/** 背景の巡回をオンにしているのに、通知が出せないときの文言。 */
internal fun backgroundRadarNoticeText(): String =
    "新着を知らせる条件がありますが、通知がオフのため、一致しても知らせられません。"

/** レーダーの切り替え。値は画面の状態として保存されるので変えない。 */
internal object RadarSection {
    const val PINS = 0
    const val CONDITIONS = 1
}

/** レーダーの切り替えを、ほかの画面から戻ったときや画面の作り直しのあとも保つ。最初はピンを出す。 */
@Composable
// 以前の切り替え（一致・見張り・履歴）の番号は意味が違うので、別の保存キーにして復元しない。
internal fun rememberRadarSection(): MutableIntState =
    rememberSaveable(key = "radar-section-v3") { mutableIntStateOf(RadarSection.PINS) }
