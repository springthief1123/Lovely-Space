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
import io.github.springthief1123.lovelyspace.ui.main.observedRoom
import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Room
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch

/** 公開ログをアプリのチャット表示で読む。閲覧用なので発言欄・入退室操作は持たない。 */
@Composable
fun PublicRoomScreen(host: String, genreKey: String, roomId: Long, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val vm: PublicRoomViewModel = viewModel(factory = viewModelFactory {
        initializer { PublicRoomViewModel { manual -> app.client.openPublicRoom(host, genreKey, roomId, manual) } }
    })
    // 画面の切り替えの動きが終わる（RESUMED になる）のを待たずに読み始める。
    LaunchedEffect(vm) { vm.open() }
    val state by vm.state.collectAsStateWithLifecycle()
    ForegroundPolling(state.automatic, vm, vm::stopRefresh) { vm.monitor { app.pacing.value.sanitized().publicRoomMs } }
    // 部屋主の名前・待機メッセージは一覧で見たもの（満室になる前に見えた名前を補う）。本家の公開ログが読めなくても出す。
    val observations by app.roomLists.observations.collectAsStateWithLifecycle()
    val room = observedRoom(observations.values, genreKey, roomId, app.roomLists.lastNamed(genreKey, roomId))
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
            room?.let { RoomSummary(it) }
            state.error?.let { message ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Text(message, color = if (state.waitingForLog) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
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

/** 一覧で見た部屋主の名前・性別・年齢・地域と待機メッセージ。 */
@Composable
private fun RoomSummary(room: Room) {
    val profile = listOfNotNull(room.name, when (room.gender) { Gender.FEMALE -> "女性"; Gender.MALE -> "男性"; Gender.UNKNOWN -> null },
        room.age?.let { "${it}歳" }, room.area).joinToString(" · ")
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        if (profile.isNotEmpty()) Text("部屋主: $profile", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (room.message.isNotBlank()) Text(room.message, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}
