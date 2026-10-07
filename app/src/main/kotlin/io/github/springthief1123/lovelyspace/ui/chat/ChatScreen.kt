@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.springthief1123.lovelyspace.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.core.chat.ChatRoomRef
import io.github.springthief1123.lovelyspace.core.messageWidth
import io.github.springthief1123.lovelyspace.ui.components.QuietMenuItem
import io.github.springthief1123.lovelyspace.ui.components.QuietOverflowMenu
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("H:mm")
private val ReadingSaver = mapSaver(
    save = { state: ChatReadingState -> mapOf("latest" to state.latestId, "atLatest" to state.atLatest, "unread" to state.unreadIds.toLongArray()) },
    restore = { ChatReadingState(it["latest"] as Long?, it["atLatest"] as Boolean, (it["unread"] as LongArray).toSet()) },
)

@Composable
fun ChatScreen(room: ChatRoomRef, onExit: () -> Unit, onEnded: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val vm: ChatViewModel = viewModel(factory = viewModelFactory { initializer { ChatViewModel(app.client, room) } })
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var confirmOwnerAction by rememberSaveable { mutableStateOf<OwnerAction?>(null) }
    var confirmBanRevision by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingMessage by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.left) {
        if (state.left) onExit()
    }
    // 部屋が終わった・開けなかったら「会話に戻る」の対象から外す（画面は理由を見せるために残す）。
    // 通信エラーは再試行できるので外さない。
    val ended = state.endMessage != null || state.roomUnavailable
    LaunchedEffect(ended) {
        if (ended) onEnded()
    }

    // 「相手を退室」の確認中に入退室が起きたら、別の相手へ古い確認を適用しない。
    LaunchedEffect(state.participantRevision, confirmOwnerAction, confirmBanRevision) {
        if (
            confirmOwnerAction == OwnerAction.BAN_GUEST &&
            confirmBanRevision != null &&
            confirmBanRevision != state.participantRevision
        ) {
            confirmOwnerAction = null
            confirmBanRevision = null
        }
    }

    // 終了した部屋や開けなかった部屋はそのまま戻る。会話中は確認してから退室する。
    val canLeaveSilently = state.endMessage != null || state.loadError != null
    BackHandler {
        if (canLeaveSilently) vm.leave() else confirmLeave = true
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(if (state.isOwner) "部屋を閉じますか？" else "退室しますか？") },
            text = {
                Text(if (state.isOwner) "閉じると部屋がなくなり、一覧からも消えます。" else "退室すると、この部屋には戻れません。")
            },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; vm.leave() }) {
                    Text(if (state.isOwner) "閉じる" else "退室する")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text("続ける") }
            },
        )
    }

    confirmOwnerAction?.let { action ->
        OwnerActionConfirmDialog(
            action = action,
            partnerName = state.partnerName,
            onConfirm = {
                val stillSameParticipant = action != OwnerAction.BAN_GUEST ||
                    (confirmBanRevision == state.participantRevision && state.canBanGuest)
                confirmOwnerAction = null
                confirmBanRevision = null
                if (stillSameParticipant) {
                    when (action) {
                        OwnerAction.BAN_GUEST -> vm.banGuest()
                        OwnerAction.CLEAR_LOG -> vm.clearLog()
                        OwnerAction.MAKE_PRIVATE -> vm.setPublic(false)
                        OwnerAction.MAKE_PUBLIC -> vm.setPublic(true)
                        OwnerAction.CHANGE_MESSAGE -> Unit
                    }
                }
            },
            onDismiss = {
                confirmOwnerAction = null
                confirmBanRevision = null
            },
        )
    }
    if (editingMessage) {
        WaitingMessageDialog(
            current = state.waitingMessage.orEmpty(),
            onSave = { editingMessage = false; vm.changeWaitingMessage(it) },
            onDismiss = { editingMessage = false },
        )
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.partnerName ?: state.title.ifEmpty { "チャット" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (state.partnerName != null && state.title.isNotEmpty()) {
                            Text(state.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = { if (canLeaveSilently) vm.leave() else confirmLeave = true }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = if (canLeaveSilently) "一覧に戻る" else if (state.isOwner) "部屋を閉じる" else "退室")
                    }
                },
                actions = {
                    if (state.showsOwnerActions) {
                        val idle = state.canRunOwnerAction
                        QuietOverflowMenu(
                            items = listOfNotNull(
                                QuietMenuItem("待機メッセージを変更", enabled = idle) { editingMessage = true },
                                if (state.canChangePublic) {
                                    if (state.isPublic) QuietMenuItem("非公開にする", enabled = idle) {
                                        confirmBanRevision = null
                                        confirmOwnerAction = OwnerAction.MAKE_PRIVATE
                                    } else QuietMenuItem("公開にする", enabled = idle) {
                                        confirmBanRevision = null
                                        confirmOwnerAction = OwnerAction.MAKE_PUBLIC
                                    }
                                } else null,
                                QuietMenuItem("発言をクリア", enabled = idle, destructive = true) {
                                    confirmBanRevision = null
                                    confirmOwnerAction = OwnerAction.CLEAR_LOG
                                },
                                if (state.canBanGuest) {
                                    QuietMenuItem("相手を退室させる", enabled = idle, destructive = true) {
                                        confirmBanRevision = state.participantRevision
                                        confirmOwnerAction = OwnerAction.BAN_GUEST
                                    }
                                } else null,
                            ),
                            contentDescription = "部屋の操作",
                        )
                    }
                    if (!canLeaveSilently && !state.isLoading) {
                        TextButton(onClick = { confirmLeave = true }, enabled = !state.isLeaving) {
                            Text(if (state.isOwner) "閉じる" else "退室")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (state.loadError == null && !state.isLoading) {
                ChatInput(state, vm, onExit = vm::leave)
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                when (state.connection) {
                    Connection.RECONNECTING -> Banner("接続が切れたため、つなぎ直しています") {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Connection.FAILED -> Banner("接続が切れました") {
                        OutlinedButton(onClick = vm::startUpdates) { Text("つなぎ直す") }
                    }
                    Connection.CONNECTED -> Unit
                }
                state.leaveError?.let {
                    Banner(it) {
                        OutlinedButton(onClick = vm::leave, enabled = !state.isLeaving) { Text(if (state.isOwner) "もう一度閉じる" else "もう一度退室する") }
                    }
                }
                state.ownerAction?.let { action ->
                    Banner(action.progress) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                }
                state.ownerNotice?.let {
                    Banner(it) {
                        TextButton(onClick = vm::dismissOwnerNotice) { Text("閉じる") }
                    }
                }
                if (state.isWaitingForPartner) {
                    Banner("相手の入室を待っています。この画面を開いている間、入室を確認し続けます")
                }
                if (state.information.isNotBlank()) {
                    Banner(state.information)
                }
            }
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.loadError != null -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(state.loadError.orEmpty(), textAlign = TextAlign.Center)
                    // 部屋の画面でなかったときは「会話に戻る」から外しているので、再試行させずに戻るだけにする。
                    if (state.roomUnavailable) {
                        OutlinedButton(onClick = vm::leave) { Text("戻る") }
                    } else {
                        OutlinedButton(onClick = vm::open) { Text("もう一度読み込む") }
                    }
                }
                else -> ChatLog(state.lines, Modifier.weight(1f))
            }
        }
    }
}

private val OwnerAction.progress: String
    get() = when (this) {
        OwnerAction.BAN_GUEST -> "相手を退室させています"
        OwnerAction.CLEAR_LOG -> "発言をクリアしています"
        OwnerAction.CHANGE_MESSAGE -> "待機メッセージを変更しています"
        OwnerAction.MAKE_PRIVATE -> "非公開に変更しています"
        OwnerAction.MAKE_PUBLIC -> "公開に変更しています"
    }

@Composable
private fun OwnerActionConfirmDialog(action: OwnerAction, partnerName: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val (title, text, confirm) = when (action) {
        OwnerAction.BAN_GUEST -> Triple(
            "${partnerName?.let { "${it}さん" } ?: "相手"}を退室させますか？",
            "相手をこの部屋から退室させます。",
            "退室させる",
        )
        OwnerAction.CLEAR_LOG -> Triple("発言をクリアしますか？", "これまでの発言が、自分と相手の両方の画面から消えます。元には戻せません。", "クリア")
        OwnerAction.MAKE_PRIVATE -> Triple("非公開にしますか？", "会話をほかの人が閲覧できなくなります。非公開にするには、参加者全員の年齢確認が必要です。", "非公開にする")
        OwnerAction.MAKE_PUBLIC -> Triple("公開にしますか？", "会話をほかの人が閲覧できるようになります。", "公開にする")
        OwnerAction.CHANGE_MESSAGE -> return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

@Composable
private fun WaitingMessageDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var message by rememberSaveable { mutableStateOf(current) }
    val width = messageWidth(message.trim())
    val max = ChatViewModel.WAITING_MESSAGE_MAX_WIDTH
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("待機メッセージを変更") },
        text = {
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                minLines = 3,
                maxLines = 8,
                isError = width > max,
                supportingText = { Text("$width / $max（全角は 2 文字として数えます）") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(message) }, enabled = message.isNotBlank() && width <= max && message.trim() != current) {
                Text("変更")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

@Composable
private fun Banner(text: String, extra: @Composable () -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            extra()
        }
    }
}

@Composable
internal fun ChatLog(lines: List<UiLine>, modifier: Modifier) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var reading by rememberSaveable(stateSaver = ReadingSaver) { mutableStateOf(ChatReadingState()) }
    val currentLines by rememberUpdatedState(lines)
    // 新着で既存の項目のindexが動いたことを、利用者のスクロールと取り違えない。
    LaunchedEffect(listState) {
        snapshotFlow { Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, currentLines.firstOrNull()?.id) }
            .distinctUntilChanged().collect { (index, offset, newest) ->
                if (newest == reading.latestId) reading = reading.onViewport(index, offset)
            }
    }
    LaunchedEffect(lines) {
        reading = reading.onLines(lines)
        if (reading.atLatest && lines.isNotEmpty()) listState.scrollToItem(0)
    }
    Box(modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize().testTag("chat-log"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(lines, key = { it.id }) { ui ->
                if (ui.line.isNotice) Notice(ui) else Bubble(ui)
            }
        }
        if (!reading.atLatest && lines.isNotEmpty()) {
            FilledTonalButton(
                onClick = { scope.launch { listState.scrollToItem(0); reading = reading.onViewport(0, 0) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(if (reading.unreadIds.isEmpty()) "最新へ戻る ↓" else "新着 ${reading.unreadIds.size}件 · 最新へ ↓")
            }
        }
        if (lines.isEmpty()) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("メッセージ", style = MaterialTheme.typography.titleMedium)
                Text("メッセージが届くとここに表示されます", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Notice(ui: UiLine) {
    Text(
        text = ui.line.text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
    )
}

@Composable
private fun Bubble(ui: UiLine) {
    val line = ui.line
    val uriHandler = LocalUriHandler.current
    val colors = MaterialTheme.colorScheme
    val container = if (ui.isMine) colors.primaryContainer else colors.surface
    val content = if (ui.isMine) colors.onPrimaryContainer else colors.onSurface
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bubbleWidth = (maxWidth * 0.84f).coerceAtMost(560.dp)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (ui.isMine) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!ui.isMine) {
                Text(line.speaker.orEmpty(), style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
            }
            Surface(color = container, contentColor = content,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp,
                    bottomStart = if (ui.isMine) 20.dp else 6.dp, bottomEnd = if (ui.isMine) 6.dp else 20.dp),
                modifier = Modifier.widthIn(max = bubbleWidth)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    SelectionContainer {
                        if (line.text.isNotEmpty()) Text(line.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    line.imageUrls.forEach { url ->
                        TextButton(onClick = { uriHandler.openUri(url) }) { Text("画像を開く") }
                    }
                }
            }
            line.time?.let {
                Text(it.format(TIME), style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable
private fun ChatInput(state: ChatUiState, vm: ChatViewModel, onExit: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider()
            if (state.endMessage != null) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(state.endMessage, textAlign = TextAlign.Center)
                    Button(onClick = onExit) { Text("一覧に戻る") }
                }
                return@Column
            }
            state.failedMessage?.let { message ->
                Column(Modifier.fillMaxWidth().heightIn(max = 190.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.sendError.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text("送信結果を確認できませんでした。履歴を確認してから、再送する文章を編集してください。",
                        style = MaterialTheme.typography.bodySmall)
                    Text(message, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    // 幅が狭い端末や文字拡大でも操作を切らない。
                    TextButton(onClick = vm::restoreFailedMessage) { Text("下書きに戻して編集する") }
                    TextButton(onClick = vm::discardFailedMessage) { Text("この送信文を破棄する") }
                }
            }
            if (state.isSending || state.isLeaving) {
                Text(if (state.isLeaving) "退室しています…" else "送信しています…",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = vm::setInput,
                    placeholder = { Text("メッセージを書く") },
                    enabled = !state.isLeaving,
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(onClick = vm::send, enabled = state.canSend, modifier = Modifier.size(48.dp)) {
                    if (state.isSending) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "送信")
                    }
                }
            }
        }
    }
}
