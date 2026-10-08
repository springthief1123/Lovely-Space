package io.github.springthief1123.lovelyspace.ui.web

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.ui.chat.ChatLog
import io.github.springthief1123.lovelyspace.ui.components.ForegroundPolling
import io.github.springthief1123.lovelyspace.ui.components.QuietTopBar
import io.github.springthief1123.lovelyspace.ui.main.RoomMenuButton
import kotlinx.coroutines.launch

/** 公開ログをアプリのチャット表示で読む。閲覧用なので発言欄・入退室操作は持たない。 */
@Composable
fun PublicRoomScreen(host: String, genreKey: String, roomId: Long, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val vm: PublicRoomViewModel = viewModel(factory = viewModelFactory {
        initializer { PublicRoomViewModel { app.client.openPublicRoom(host, genreKey, roomId) } }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    ForegroundPolling(state.automatic, vm, vm::stopRefresh) { vm.monitor() }
    val snackbar = remember { SnackbarHostState() }
    val menuScope = rememberCoroutineScope()
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            QuietTopBar("公開ルーム", onBack = onBack, actions = {
                RoomMenuButton(genreKey, roomId, fallbackTitle = "公開ルーム",
                    onNotice = { message -> menuScope.launch { snackbar.showSnackbar(message, withDismissAction = true) } })
            })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            // 閲覧中は常に自動で読み直す。閉じられた部屋（非公開への変更など）では止まり、「再読み込み」だけを出す。
            Text("閲覧のみ（発言・入室はしません）。開いている間、新着を自動で読み込みます", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            state.error?.let { message ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = vm::refresh, enabled = !state.loading) { Text("再読み込み") }
                }
            }
            if (state.information.isNotBlank()) Text(state.information, Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodySmall)
            if (state.loading && !state.opened) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            } else if (state.opened) ChatLog(state.lines, Modifier.weight(1f), nameOnBothSides = true)
        }
    }
}
