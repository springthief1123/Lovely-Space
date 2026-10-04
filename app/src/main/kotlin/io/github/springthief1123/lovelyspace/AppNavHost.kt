package io.github.springthief1123.lovelyspace

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.chat.ChatRoomRef
import io.github.springthief1123.lovelyspace.settings.ThemeMode
import io.github.springthief1123.lovelyspace.ui.chat.ChatScreen
import io.github.springthief1123.lovelyspace.ui.create.CreateRoomScreen
import io.github.springthief1123.lovelyspace.ui.entry.EntryScreen
import io.github.springthief1123.lovelyspace.ui.rooms.RoomListScreen
import io.github.springthief1123.lovelyspace.ui.web.PublicRoomScreen

private object Routes {
    const val ROOMS = "rooms"
    const val ENTRY = "entry/{host}/{genre}/{roomId}"
    const val CHAT = "chat/{host}/{genre}/{roomId}/{pwd}"
    const val CREATE = "create/{genre}"
    const val PUBLIC = "public/{host}/{genre}/{roomId}"

    fun entry(host: String, genre: String, roomId: Long) = "entry/${Uri.encode(host)}/${Uri.encode(genre)}/$roomId"

    fun create(genre: String) = "create/${Uri.encode(genre)}"

    fun public(host: String, genre: String, roomId: Long) = "public/${Uri.encode(host)}/${Uri.encode(genre)}/$roomId"

    fun chat(room: ChatRoomRef) =
        "chat/${Uri.encode(room.host)}/${Uri.encode(room.genreKey)}/${room.roomId}/${Uri.encode(room.pwd)}"
}

@Composable
fun AppNavHost(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    val nav = rememberNavController()
    // チャットから戻ったら、入っていた部屋の状態が変わっているので一覧を取り直す。
    var roomsRefreshKey by rememberSaveable { mutableIntStateOf(0) }
    val roomArgs = listOf(
        navArgument("host") { type = NavType.StringType },
        navArgument("genre") { type = NavType.StringType },
        navArgument("roomId") { type = NavType.LongType },
    )
    NavHost(navController = nav, startDestination = Routes.ROOMS) {
        composable(Routes.ROOMS) {
            RoomListScreen(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                refreshKey = roomsRefreshKey,
                onEnterRoom = { room ->
                    val host = Genres[room.genreKey]?.host ?: return@RoomListScreen
                    nav.navigate(Routes.entry(host, room.genreKey, room.id)) { launchSingleTop = true }
                },
                onPeekRoom = { room ->
                    val host = Genres[room.genreKey]?.host ?: return@RoomListScreen
                    nav.navigate(Routes.public(host, room.genreKey, room.id)) { launchSingleTop = true }
                },
                onCreateRoom = { genre -> nav.navigate(Routes.create(genre.key)) { launchSingleTop = true } },
            )
        }
        composable(Routes.ENTRY, arguments = roomArgs) { entry ->
            val args = entry.arguments!!
            EntryScreen(
                host = args.getString("host")!!,
                genreKey = args.getString("genre")!!,
                roomId = args.getLong("roomId"),
                onBack = { nav.popBackStack() },
                onEntered = { room ->
                    // 入室前画面は戻り先に残さない（戻ると一覧へ）。
                    nav.navigate(Routes.chat(room)) {
                        popUpTo(Routes.ROOMS)
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.CREATE, arguments = listOf(navArgument("genre") { type = NavType.StringType })) { entry ->
            val genre = Genres[entry.arguments!!.getString("genre")!!] ?: Genres.default
            CreateRoomScreen(
                genre = genre,
                onBack = { nav.popBackStack() },
                onCreated = { room ->
                    nav.navigate(Routes.chat(room)) {
                        popUpTo(Routes.ROOMS)
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.PUBLIC, arguments = roomArgs) { entry ->
            val args = entry.arguments!!
            PublicRoomScreen(
                host = args.getString("host")!!,
                genreKey = args.getString("genre")!!,
                roomId = args.getLong("roomId"),
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.CHAT, arguments = roomArgs + navArgument("pwd") { type = NavType.StringType }) { entry ->
            val args = entry.arguments!!
            val room = ChatRoomRef(
                host = args.getString("host")!!,
                roomId = args.getLong("roomId"),
                pwd = args.getString("pwd")!!,
                genreKey = args.getString("genre")!!,
            )
            ChatScreen(
                room = room,
                onExit = {
                    roomsRefreshKey++
                    nav.popBackStack(Routes.ROOMS, inclusive = false)
                },
            )
        }
    }
}
