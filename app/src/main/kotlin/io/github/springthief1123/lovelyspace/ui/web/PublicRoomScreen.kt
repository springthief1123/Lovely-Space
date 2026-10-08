package io.github.springthief1123.lovelyspace.ui.web

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
            Text("閲覧のみ（発言・入室はしません）", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
            // スイッチは画面を開いている間の自動の読み込み。オフなら「更新」を押したときだけ読み直す。
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("自動で新着を読み込む", style = MaterialTheme.typography.labelLarge)
                    Text(if (state.automatic) "開いている間、20 秒ごとに読み直します" else "「更新」を押したときだけ読み直します",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 初回の読み込み中にオフにした場合も、手動で読めるようにする。
                if (!state.automatic) TextButton(onClick = vm::refresh, enabled = !state.loading) { Text("更新") }
                Switch(state.automatic, vm::automatic, modifier = Modifier.semantics { contentDescription = "自動で新着を読み込む" })
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
