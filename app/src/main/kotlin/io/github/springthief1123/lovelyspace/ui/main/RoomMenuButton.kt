package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.roomIdentity
import io.github.springthief1123.lovelyspace.data.ObservedRoomPage
import io.github.springthief1123.lovelyspace.ui.rooms.RoomActionMenu
import io.github.springthief1123.lovelyspace.ui.rooms.RoomMenuItem
import io.github.springthief1123.lovelyspace.ui.rooms.RoomPreferenceViewModel

/**
 * 取得済みの一覧から、その部屋が見えた最新の姿を探す。通信はしない。
 * 満室になると名前が隠れるので、名前の見えている観測を優先する（入室前の待機中の姿など）。
 */
internal fun observedRoom(observations: Collection<ObservedRoomPage>, genreKey: String, roomId: Long): Room? {
    val key = "${Genres[genreKey]?.host ?: genreKey}/$roomId"
    val seen = observations.sortedByDescending { it.revision }
        .flatMap { observed -> observed.page.rooms.filter { roomIdentity(it) == key } }
    return seen.firstOrNull { it.name != null } ?: seen.firstOrNull()
}

/**
 * 部屋の中（トーク画面・公開ルーム）の右上に置くメニュー。長押しメニューと同じパネルで、
 * 部屋主の名前・待機メッセージと、保存・追跡・順番待ちを出す。部屋の情報は取得済みの一覧から探すので、一覧で見ていない部屋では選べない。
 */
@Composable
internal fun RoomMenuButton(genreKey: String, roomId: Long, fallbackTitle: String, onNotice: (String) -> Unit, waitingMessage: String? = null) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val observations by app.roomLists.observations.collectAsStateWithLifecycle()
    val room = observedRoom(observations.values, genreKey, roomId)
    val preferencesVm: RoomPreferenceViewModel = viewModel(
        key = "room-menu",
        factory = viewModelFactory { initializer { RoomPreferenceViewModel(app.roomPreferences) } },
    )
    val preferences by preferencesVm.state.collectAsStateWithLifecycle()
    val quickActions = rememberRoomQuickActions(onNotice)
    var open by remember { mutableStateOf(false) }

    val items = if (room == null) {
        listOf(
            RoomMenuItem("部屋を保存", Icons.Outlined.BookmarkBorder, enabled = false) {},
        )
    } else {
        val favorite = preferences.isFavorite(room)
        listOf(
            RoomMenuItem(if (favorite) "保存を解除" else "部屋を保存",
                if (favorite) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                enabled = !preferences.loading && preferences.canEdit(room)) {
                preferencesVm.toggleFavorite(room)
            },
        ) + quickActions(room, null)
    }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreHoriz, contentDescription = "部屋のメニュー") }
        RoomActionMenu(
            visible = open,
            // 「…」の下から左下へ広げる。
            touch = null,
            title = room?.name?.let { "部屋主: $it" } ?: fallbackTitle,
            // 部屋の画面に出ている待機メッセージがあればそれを、無ければ一覧で見たものを出す。
            message = waitingMessage?.takeIf { it.isNotBlank() } ?: room?.message,
            subtitle = if (room == null) "一覧で見かけた部屋だけ保存・追跡できます"
                // 状態は一覧で見たときのものなので出さない。
                else listOfNotNull(when (room.gender) { Gender.FEMALE -> "女性"; Gender.MALE -> "男性"; Gender.UNKNOWN -> null },
                    room.age?.let { "${it}歳" }, room.area).joinToString(" · "),
            items = items,
            onDismiss = { open = false },
        )
    }
}
