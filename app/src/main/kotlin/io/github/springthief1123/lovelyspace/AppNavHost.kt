package io.github.springthief1123.lovelyspace

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.settings.ThemeMode
import io.github.springthief1123.lovelyspace.ui.chat.ChatScreen
import io.github.springthief1123.lovelyspace.ui.entry.EntryScreen
import io.github.springthief1123.lovelyspace.ui.rooms.RoomListScreen

private object Routes {
    const val ROOMS = "rooms"
    const val ENTRY = "entry/{host}/{genre}/{roomId}"
    /** pwd は route に載せず、[ActiveRooms] の一時 ID だけを渡す。 */
    const val CHAT = "chat/{session}"

    fun entry(host: String, genre: String, roomId: Long) = "entry/${Uri.encode(host)}/${Uri.encode(genre)}/$roomId"

    fun chat(sessionId: String) = "chat/$sessionId"
}

@Composable
fun AppNavHost(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
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
                    nav.navigate(Routes.chat(app.activeRooms.register(room))) {
                        popUpTo(Routes.ROOMS)
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.CHAT, arguments = listOf(navArgument("session") { type = NavType.StringType })) { entry ->
            val sessionId = entry.arguments!!.getString("session")!!
            val room = app.activeRooms[sessionId]
            val exit = {
                app.activeRooms.remove(sessionId)
                roomsRefreshKey++
                nav.popBackStack(Routes.ROOMS, inclusive = false)
                Unit
            }
            if (room == null) {
                // プロセスが終了して部屋の情報が消えた。pwd は保存していないので一覧へ戻る。
                LaunchedEffect(Unit) { exit() }
            } else {
                ChatScreen(room = room, onExit = exit)
            }
        }
    }
}
