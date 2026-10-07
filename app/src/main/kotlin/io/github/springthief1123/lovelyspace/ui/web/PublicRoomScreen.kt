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

/** 公開ログをアプリのチャット表示で読む。閲覧用なので発言欄・入退室操作は持たない。 */
@Composable
fun PublicRoomScreen(host: String, genreKey: String, roomId: Long, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val vm: PublicRoomViewModel = viewModel(factory = viewModelFactory {
        initializer { PublicRoomViewModel { app.client.openPublicRoom(host, genreKey, roomId) } }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    ForegroundPolling(state.automatic, vm, vm::stopRefresh) { vm.monitor() }
    Scaffold(topBar = { QuietTopBar("公開ルーム", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("閲覧のみ", style = MaterialTheme.typography.labelLarge)
                Switch(state.automatic, vm::automatic)
            }
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
            } else if (state.opened) ChatLog(state.lines, Modifier.weight(1f))
        }
    }
}
