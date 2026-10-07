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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("H:mm")
private val ReadingSaver = mapSaver(
    save = { state: ChatReadingState -> mapOf("latest" to state.latestId, "atLatest" to state.atLatest, "unread" to state.unreadIds.toLongArray()) },
    restore = { ChatReadingState(it["latest"] as Long?, it["atLatest"] as Boolean, (it["unread"] as LongArray).toSet()) },
)

@Composable
fun ChatScreen(room: ChatRoomRef, onExit: () -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val vm: ChatViewModel = viewModel(factory = viewModelFactory { initializer { ChatViewModel(app.client, room) } })
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmLeave by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.left) {
        if (state.left) onExit()
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
                    OutlinedButton(onClick = vm::open) { Text("もう一度読み込む") }
                }
                else -> ChatLog(state.lines, Modifier.weight(1f))
            }
        }
    }
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
