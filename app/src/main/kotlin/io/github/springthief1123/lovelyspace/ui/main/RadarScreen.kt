package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.ui.components.QuietNotice

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
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
import io.github.springthief1123.lovelyspace.ui.rooms.ActionChip
import io.github.springthief1123.lovelyspace.ui.rooms.LocalRoomMessageMaxLines
import io.github.springthief1123.lovelyspace.ui.rooms.MetaChip
import io.github.springthief1123.lovelyspace.ui.rooms.RoomCardHeading
import io.github.springthief1123.lovelyspace.ui.rooms.RoomCardPanel
import io.github.springthief1123.lovelyspace.ui.rooms.RoomCardSwipeBox
import io.github.springthief1123.lovelyspace.ui.rooms.RoomActionMenu
import io.github.springthief1123.lovelyspace.ui.rooms.RoomMenuItem
import io.github.springthief1123.lovelyspace.ui.rooms.cardActionLabel
import io.github.springthief1123.lovelyspace.ui.rooms.roomStatusColor
import io.github.springthief1123.lovelyspace.ui.rooms.statusLabel
import io.github.springthief1123.lovelyspace.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * ピンのタブ。ピンした部屋（入りたい部屋）の様子と、閉じた部屋の立て直しだけを見る（Yuya の決定、2026-11-06）。
 * 検索条件で探すのは見つける画面に一本化し、ここには条件・名前で待つ・見張りの切り替えを置かない。
 * 並びは 空いています → 立て直しかも → 満室 → 未確認 → 立て直し待ち の 1 列。似た部屋は元のピンのカードの中に出す。
 * 満室のピンは知らせ方に関係なく、どの画面にいても設定の「空き枠の確認」の間隔で確かめる（[PinRepository.fastWatch]）。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun RadarScreen(onFindRooms: () -> Unit, onEnterRoom: (Room) -> Unit, onPeekRoom: (Room) -> Unit,
    onQuickEnterRoom: (Room) -> Unit = onEnterRoom) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val preferencesVm: io.github.springthief1123.lovelyspace.ui.rooms.RoomPreferenceViewModel = viewModel(
        factory = viewModelFactory { initializer { io.github.springthief1123.lovelyspace.ui.rooms.RoomPreferenceViewModel(app.roomPreferences) } })
    val preferences by preferencesVm.state.collectAsStateWithLifecycle()
    val pins by app.pins.pins.collectAsStateWithLifecycle()
    val pinsLoaded by app.pins.loaded.collectAsStateWithLifecycle()
    val observations by app.roomLists.observations.collectAsStateWithLifecycle()
    val pacing by app.pacing.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val workingKeys = remember { mutableStateMapOf<String, Boolean>() }
    val pendingAlerts = remember { mutableStateMapOf<String, PinAlert>() }
    val working = workingKeys.isNotEmpty()
    // 永続化とピン一覧の再計算を待たず、操作したカードだけをすぐに隠す。保存失敗なら戻す。
    var pendingUnpins by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(pins) { pendingUnpins = pendingUnpins.intersect(pins.mapTo(HashSet()) { it.key }) }
    val visiblePins = pins.filterNot { it.key in pendingUnpins }
    var error by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf<Room?>(null) }
    var memoPin by rememberSaveable { mutableStateOf<String?>(null) }
    // 操作の失敗（error）を「再試行」したときに、失敗した操作そのものをやり直す。やり直せない失敗では null。
    var retry by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun fail(message: String, again: (() -> Unit)? = null) { error = message; retry = again }
    fun action(key: String, block: suspend () -> Unit) {
        if (key in workingKeys) return
        workingKeys[key] = true
        error = null; retry = null
        scope.launch {
            try { block() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: IllegalArgumentException) { fail(e.message ?: "設定を保存できませんでした。") }
            catch (e: Exception) { fail("設定を保存できませんでした。もう一度お試しください。", again = { action(key, block) }) }
            finally { workingKeys.remove(key) }
        }
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
            if (snackbar.showSnackbar("ピンを外しました", actionLabel = "元に戻す", withDismissAction = true) == SnackbarResult.ActionPerformed)
                preferencesVm.restoreFavorite(value, onSuccess = { pendingUnpins = pendingUnpins - "${value.host}/${value.roomId}" })
        }
    }
    val notificationsAllowed = io.github.springthief1123.lovelyspace.notify.rememberNotificationsAllowed(
        io.github.springthief1123.lovelyspace.notify.NotificationKind.WAITLIST)
    val context = LocalContext.current
    // 最後に確かめた時刻は、ピンのあるジャンルの一覧を最後に読んだ時刻（どの画面・確認が読んだものでもよい）。
    val pinGenres = visiblePins.mapTo(HashSet()) { it.room.genreKey }
    val checkedAt = observations.values.filter { it.query.genre.key in pinGenres }.maxOfOrNull { it.confirmedAt }
    val sections = listOf(
        "空いています" to visiblePins.filter { it.state == PinState.OPEN },
        "立て直しかも" to visiblePins.filter { it.state != PinState.OPEN && it.similar != null },
        "満室" to visiblePins.filter { it.state == PinState.FULL && it.similar == null },
        "未確認" to visiblePins.filter { it.state == PinState.UNSEEN && it.similar == null },
        "立て直し待ち" to visiblePins.filter { it.state == PinState.CLOSED && it.similar == null },
    ).filter { it.second.isNotEmpty() }
    QuietPage {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = LovelySpacing.screenHorizontal, end = LovelySpacing.screenHorizontal,
        top = lovelyMainContentTopPadding(), bottom = lovelyMainContentBottomInset() + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "heading") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) { QuietHeading("ピン") }
                if (visiblePins.isNotEmpty()) Text(pinCheckedText(checkedAt, pacing.waitlistMs), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        error?.let { message -> item(key = "error") {
            QuietNotice(message, error = true, onDismiss = { error = null; retry = null },
                actionLabel = retry?.let { "再試行" }, actionEnabled = !working,
                onAction = retry?.let { run -> { error = null; retry = null; run() } })
        } }
        // ピンはどれも空いたら知らせるので、通知が出せないなら空いても知らせられないことを伝える。
        if (!notificationsAllowed && visiblePins.isNotEmpty()) item(key = "notice") {
            QuietNotice(pinNoticeText(false), error = true, actionLabel = "通知の設定を開く",
                onAction = { context.startActivity(io.github.springthief1123.lovelyspace.notify.appNotificationSettingsIntent(context)) })
        }
        if (!pinsLoaded && preferences.loading) item(key = "loading") { CircularProgressIndicator() }
        if (pinsLoaded && visiblePins.isEmpty()) item(key = "empty") {
            RadarEmpty("ピンした部屋はまだありません。見つけるで部屋カードを右へスワイプするか、長押しのメニューから「ピン」を選ぶと加わります。", "部屋を探す", onFindRooms)
        }
        sections.forEach { (title, list) ->
            item(key = "section/$title") { QuietSectionHeader(title, "${list.size}件") }
            items(list, key = { "pin/${it.key}" }) { pin ->
                PinCard(pin.copy(record = pin.record.copy(alert = pendingAlerts[pin.key] ?: pin.record.alert)),
                    enabled = pin.key !in workingKeys && preferences.canEdit(pin.room), autoEntrySaving = pin.key in pendingAlerts,
                    onAutoEntry = { on ->
                        val alert = if (on) PinAlert.AUTO else PinAlert.NOTIFY
                        action(pin.key) {
                            pendingAlerts[pin.key] = alert
                            try { app.pins.setAlert(pin.key, alert) }
                            finally { pendingAlerts.remove(pin.key) }
                        }
                    },
                    onEnter = { onQuickEnterRoom(pin.room) },
                    onPeek = { onPeekRoom(pin.room) },
                    onDetails = { details = pin.room },
                    onMemo = { memoPin = pin.key; error = null },
                    onUnpin = {
                        pendingUnpins = pendingUnpins + pin.key
                        preferencesVm.clearFavorite(pin.preference, onFailure = { pendingUnpins = pendingUnpins - pin.key })
                    },
                    onAdopt = { room -> action(pin.key) { app.pins.adopt(pin, room) } },
                    onDismissSimilar = { room -> action(pin.key) { app.pins.dismiss(pin.key, roomIdentity(room)) } },
                    onEnterSimilar = { room -> if (room.action == RoomAction.ENTER) onEnterRoom(room) else if (room.action == RoomAction.PEEK) onPeekRoom(room) else details = room })
            }
        }
    }
    QuietSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = lovelyMainSnackbarBottomInset()))
    }
    pins.firstOrNull { it.key == memoPin }?.let { pin ->
        PinMemoEditor(pin, working, error, onDismiss = { memoPin = null },
            onSave = { memo -> action(pin.key) { app.pins.setMemo(pin.key, memo); memoPin = null } })
    }
    details?.let { room -> RoomDetailsSheet(room, preferences.isFavorite(room), preferences.canEdit(room),
        onDismiss = { details = null }, onFavorite = { preferencesVm.toggleFavorite(room) },
        onHide = { preferencesVm.hide(room); details = null }, onEnter = onEnterRoom, onPeek = onPeekRoom,
        // 閉じたと分かっているピンは、保存した姿のままでは入れない（ID が別の部屋に使われていることもある）。
        allowEntry = pins.none { it.key == roomIdentity(room) && it.state == PinState.CLOSED }) }
}

/** 見出しの横の 1 行。最後に確かめた時刻と、満室のピンを確かめる間隔（設定の「空き枠の確認」）。 */
internal fun pinCheckedText(at: Long?, intervalMs: Long): String {
    val every = if (intervalMs % 1_000L == 0L) "${intervalMs / 1_000L}秒ごと" else "${intervalMs / 1_000.0}秒ごと"
    return (at?.let { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.JAPAN).format(java.util.Date(it)) + " 確認・" } ?: "") + every
}

/** ピンの様子の名前。 */
internal fun pinStateLabel(state: PinState): String = when (state) {
    PinState.OPEN -> "空いています"
    PinState.FULL -> "満室"
    PinState.CLOSED -> "閉じた"
    PinState.UNSEEN -> "未確認"
}

/** ピンの様子の文言。空いていれば一覧と同じ状態（待機中・公開待機）で出す。 */
internal fun pinStateChipText(pin: PinView): String =
    if (pin.state == PinState.OPEN) statusLabel(pin.room.status) else pinStateLabel(pin.state)

/** ピンの様子のチップの文言と色。 */
@Composable
private fun pinStateChip(pin: PinView): Pair<String, androidx.compose.ui.graphics.Color> = pinStateChipText(pin) to when (pin.state) {
    PinState.OPEN -> roomStatusColor(pin.room.status)
    PinState.FULL -> LocalLovelyColors.current.full
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * ピンの 1 件。閉じた部屋に似た部屋が建っていれば、カードの中に候補（[SimilarRoomBlock]）を出す。見た目は見つける画面の部屋カード（[RoomCardPanel]・[RoomCardHeading]）にそろえる。
 * 下の段に自動入室の切り替え（[AutoEntryChip]。ピンはどれも空いたら知らせる）と、空いていれば見つける画面と同じ「入室する」チップ（確認なしで入室）を置く。
 * 押すと部屋の詳細、長押しで 詳細・メモ・ピンを外す のメニュー、右へスワイプするとピンを外す（見つける画面でピンするのと同じ向き。2026-11-06 Yuya）。
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PinCard(pin: PinView, enabled: Boolean, onAutoEntry: (Boolean) -> Unit,
    onEnter: () -> Unit, onPeek: () -> Unit, onDetails: () -> Unit, onMemo: () -> Unit, onUnpin: () -> Unit,
    onAdopt: (Room) -> Unit, onDismissSimilar: (Room) -> Unit, onEnterSimilar: (Room) -> Unit, autoEntrySaving: Boolean = false) {
    val room = pin.room
    val record = pin.record
    // 一覧に載っていない（閉じた・まだ確かめていない）ピンの状態や経過時間は古いので、押して動ける表示は出さない。
    val current = pin.listed && pin.state != PinState.CLOSED
    var menuOpen by remember(pin.key) { mutableStateOf(false) }
    var pressAt by remember(pin.key) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val menuItems = listOf(
        RoomMenuItem("部屋の詳細", Icons.Outlined.Info, onClick = onDetails),
        RoomMenuItem("メモ", Icons.Outlined.EditNote, enabled = enabled, onClick = onMemo),
        RoomMenuItem("ピンを外す", Icons.Filled.PushPin, enabled = enabled, separated = true, onClick = onUnpin),
    )
    val a11yActions = menuItems.filter { it.enabled }.map { item -> CustomAccessibilityAction(item.label) { item.onClick(); true } }
    RoomCardSwipeBox(key = pin.key, isFavorite = true, isHidden = false, onFavoriteClick = onUnpin, onHideClick = null, actionsEnabled = enabled) { swipe ->
    RoomCardPanel(full = pin.state != PinState.OPEN) {
        Box {
        Column(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { awaitEachGesture { pressAt = awaitFirstDown(requireUnconsumed = false).position } }
                .combinedClickable(onClickLabel = "部屋の詳細", onClick = { swipe.tap(onDetails) },
                    onLongClickLabel = "ピンの操作", onLongClick = { menuOpen = true })
                .semantics { customActions = a11yActions }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // （会話中）は一覧でいま満室と確かめた部屋だけ。閉じた・未確認の部屋では、ピンしたときの満室の姿のままでも付けない。
            RoomCardHeading(room, placeholder = "名前の見えない部屋", elapsed = room.elapsed?.takeIf { current },
                talking = pin.state == PinState.FULL)
            if (room.message.isNotBlank()) Text(room.message, style = MaterialTheme.typography.bodyMedium,
                color = if (pin.state == PinState.OPEN) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = LocalRoomMessageMaxLines.current, overflow = TextOverflow.Ellipsis)
            if (record.memo.isNotBlank()) Text("メモ：${record.memo}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val (label, color) = pinStateChip(pin)
                MetaChip(label, color, filled = true)
                Genres[room.genreKey]?.let { MetaChip(it.label, MaterialTheme.colorScheme.onSurfaceVariant, filled = false) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AutoEntryChip(record.alert, enabled, onAutoEntry, saving = autoEntrySaving)
                Spacer(Modifier.weight(1f))
                // 見つける画面のカードと同じ「入室する」チップ。押せる範囲は 48dp を取る。
                if (current) cardActionLabel(room.action)?.let { label ->
                    val enter = room.action == RoomAction.ENTER
                    ActionChip(label, emphasized = enter, onClick = { swipe.tap(if (enter) onEnter else onPeek) },
                        onClickLabel = if (enter) "確認せずに入室する" else "覗く", modifier = Modifier.minimumInteractiveComponentSize())
                }
            }
            pin.similar?.let { candidate -> SimilarRoomBlock(candidate, enabled, onAdopt, onDismissSimilar, onEnterSimilar) }
            if (pin.state == PinState.CLOSED && pin.similar == null) Text(
                (record.stateAt?.let { "${formatObservationTime(it)}に閉じた・" } ?: "") + "同じ名前・待機メッセージで建ったら自動で移します",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RoomActionMenu(
            visible = menuOpen,
            touch = pressAt,
            title = pin.name,
            subtitle = listOfNotNull(
                pinStateChipText(pin),
                when (room.gender) { Gender.FEMALE -> "女性"; Gender.MALE -> "男性"; Gender.UNKNOWN -> null },
                room.age?.let { "${it}歳" },
            ).joinToString(" · "),
            items = menuItems,
            onDismiss = { menuOpen = false },
        )
        }
    }
    }
}

/** 0〜23 時の空いた回数の棒。読み上げでは多い時間帯を文で伝える（見出しにある）ので、図は飛ばす。 */
@Composable
internal fun VacancyHistogram(hours: List<Int>) {
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
@OptIn(ExperimentalMaterial3Api::class)
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
 * 閉じたピンに似た新しい部屋。待機メッセージを少し書き換えた立て直しかもしれないので、同じ人と答えたときだけピンを移す
 * （似ているだけでは自動で入らない。AGENTS.md の規則 3）。
 */
@Composable
private fun SimilarRoomBlock(candidate: SimilarRoom, enabled: Boolean, onAdopt: (Room) -> Unit, onDismiss: (Room) -> Unit, onOpen: (Room) -> Unit) {
    val room = candidate.room
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = LovelyShapes.control, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RoomCardHeading(room, placeholder = "名前の見えない部屋", elapsed = room.elapsed)
            Text("${statusLabel(room.status)}・待機メッセージが${(candidate.similarity * 100).roundToInt()}%同じ部屋が建ちました", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (room.message.isNotBlank()) Text(room.message, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onDismiss(room) }, enabled = enabled) { Text("別の人") }
                cardActionLabel(room.action)?.let { label -> TextButton(onClick = { onOpen(room) }) { Text(label) } }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onAdopt(room) }, enabled = enabled) { Text("同じ人・ピンを移す") }
            }
        }
    }
}

/** 一覧が空のときの案内。操作は1つだけにする。 */
@Composable
private fun RadarEmpty(text: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        actionLabel?.let { OutlinedButton(onClick = onAction) { Text(it) } }
    }
}

