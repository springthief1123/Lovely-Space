@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.springthief1123.lovelyspace.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.core.chat.ChatRoomRef
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("H:mm")

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
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.partnerName ?: state.title.ifEmpty { "チャット" }, style = MaterialTheme.typography.titleMedium)
                        if (state.partnerName != null && state.title.isNotEmpty()) {
                            Text(state.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (canLeaveSilently) vm.leave() else confirmLeave = true }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "退室")
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (state.connection) {
                Connection.RECONNECTING -> Banner("接続が切れたため、つなぎ直しています") {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Connection.FAILED -> Banner("接続が切れました") {
                    OutlinedButton(onClick = vm::startUpdates) { Text("つなぎ直す") }
                }
                Connection.CONNECTED -> Unit
            }
            if (state.isWaitingForPartner) {
                Banner("相手の入室を待っています。この画面を開いている間、入室を確認し続けます")
            }
            if (state.information.isNotBlank()) {
                Banner(state.information)
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
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            extra()
        }
    }
}

@Composable
private fun ChatLog(lines: List<UiLine>, modifier: Modifier) {
    val listState = rememberLazyListState()
    // 最新の近くを見ているときだけ、新着に合わせて下へ送る。遡って読んでいる最中は動かさない。
    LaunchedEffect(lines.firstOrNull()?.id) {
        if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(lines, key = { it.id }) { ui ->
            when {
                ui.line.isNotice -> Notice(ui)
                else -> Bubble(ui)
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
    val (container, content) = when {
        ui.isMine -> colors.primaryContainer to colors.onPrimaryContainer
        line.speakerIsFemale -> colors.tertiaryContainer to colors.onTertiaryContainer
        else -> colors.surfaceContainerHigh to colors.onSurface
    }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (ui.isMine) Alignment.End else Alignment.Start,
    ) {
        if (!ui.isMine) {
            Text(
                line.speaker.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
            )
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val time: @Composable () -> Unit = {
                line.time?.let { Text(it.format(TIME), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
            }
            if (ui.isMine) time()
            Surface(
                color = container,
                contentColor = content,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.widthIn(max = 280.dp),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (line.text.isNotEmpty()) Text(line.text, style = MaterialTheme.typography.bodyLarge)
                    line.imageUrls.forEach { url ->
                        TextButton(onClick = { uriHandler.openUri(url) }) { Text("画像を開く") }
                    }
                }
            }
            if (!ui.isMine) time()
        }
    }
}

@Composable
private fun ChatInput(state: ChatUiState, vm: ChatViewModel, onExit: () -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.navigationBarsPadding().imePadding()) {
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
            state.sendError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = vm::setInput,
                    placeholder = { Text("メッセージ") },
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = vm::send, enabled = state.canSend) {
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
