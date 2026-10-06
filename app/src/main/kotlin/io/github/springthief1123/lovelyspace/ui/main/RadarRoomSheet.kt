package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.RoomIdentityEvidence
import io.github.springthief1123.lovelyspace.core.roomIdentity
import io.github.springthief1123.lovelyspace.data.*
import io.github.springthief1123.lovelyspace.ui.rooms.RoomPreferenceViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun RadarRoomSheet(snapshot: RadarRoomSnapshot, onDismiss: () -> Unit, onFindRooms: () -> Unit,
    onEnterRoom: (Room) -> Unit, onPeekRoom: (Room) -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val vm: RoomPreferenceViewModel = viewModel(factory = viewModelFactory { initializer { RoomPreferenceViewModel(app.roomPreferences) } })
    val preferences by vm.state.collectAsStateWithLifecycle()
    val radar by app.radar.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var inspection by remember(snapshot) { mutableStateOf<RadarRoomInspection?>(null) }
    var checking by remember(snapshot) { mutableStateOf(false) }
    val room = inspection?.room ?: snapshot.room
    val blocked = snapshot.blocked || radar.targets.any { roomIdentity(it.room) == roomIdentity(snapshot.room) && it.evidence == RoomIdentityEvidence.REUSED } ||
        preferences.preferences.any { it.host == Genres[snapshot.room.genreKey]?.host && it.roomId == snapshot.room.id && it.stale }
    val allowed = inspection?.room != null && !blocked && !checking && !preferences.loading && preferences.error == null && radar.loaded
    RoomDetailsSheet(room, preferences.isFavorite(room), allowed && !preferences.loading && preferences.canEdit(room),
        onDismiss = onDismiss, onFavorite = { vm.toggleFavorite(room) }, onEnter = onEnterRoom, onPeek = onPeekRoom,
        allowEntry = allowed, sourceQuery = snapshot.sourceQuery ?: Genres[snapshot.room.genreKey]?.let { io.github.springthief1123.lovelyspace.core.RoomQuery(it, page = snapshot.page) }, verificationContent = {
            snapshot.at?.let { Text("記録 ${formatObservationTime(it)} · ${snapshot.page}ページ", style = MaterialTheme.typography.bodySmall) }
            Text(if (blocked) "ID再利用を確認した部屋です。「見つける」から選び直してください。" else inspection?.message ?: "履歴の部屋情報です。操作する前に最新の一覧を確認してください。", style = MaterialTheme.typography.bodySmall)
            preferences.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = vm::reload) { Text("保存設定を読み直す") }
            }
            radar.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!radar.loaded && radar.error != null) TextButton(onClick = app.radar::reload, enabled = !radar.running) { Text("追跡設定を読み直す") }
            if (!blocked && inspection?.reused != true) OutlinedButton(enabled = !checking && !preferences.loading && preferences.error == null && radar.loaded, onClick = {
                checking = true
                inspection = null
                scope.launch {
                    try { inspection = inspectRadarRoom(app.roomLists, snapshot) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { inspection = RadarRoomInspection(message = "一覧を確認できませんでした。もう一度お試しください。") }
                    finally { checking = false }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (checking) "確認中…" else "最新の一覧で確認") }
            TextButton(onClick = { onDismiss(); onFindRooms() }) { Text("「見つける」で確認する") }
        })
}
