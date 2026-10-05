package io.github.springthief1123.lovelyspace

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.settings.ThemeMode
import io.github.springthief1123.lovelyspace.ui.chat.ChatScreen
import io.github.springthief1123.lovelyspace.ui.create.CreateRoomScreen
import io.github.springthief1123.lovelyspace.ui.entry.EntryScreen
import io.github.springthief1123.lovelyspace.ui.main.FavoritesScreen
import io.github.springthief1123.lovelyspace.ui.main.ProfileScreen
import io.github.springthief1123.lovelyspace.ui.main.SearchScreen
import io.github.springthief1123.lovelyspace.ui.rooms.RoomListScreen
import io.github.springthief1123.lovelyspace.ui.settings.SettingsScreen
import io.github.springthief1123.lovelyspace.ui.shell.LovelyAppShell
import io.github.springthief1123.lovelyspace.ui.web.PublicRoomScreen

private object Routes {
    const val ROOMS = "rooms"
    const val SEARCH = "search"
    const val FAVORITES = "favorites"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
    const val ENTRY = "entry/{host}/{genre}/{roomId}"
    /** pwd は route に載せず、[ActiveRooms] の一時 ID だけを渡す。 */
    const val CHAT = "chat/{session}"
    const val CREATE = "create/{genre}"
    const val PUBLIC = "public/{host}/{genre}/{roomId}"

    fun entry(host: String, genre: String, roomId: Long) = "entry/${Uri.encode(host)}/${Uri.encode(genre)}/$roomId"
    fun create(genre: String) = "create/${Uri.encode(genre)}"
    fun public(host: String, genre: String, roomId: Long) = "public/${Uri.encode(host)}/${Uri.encode(genre)}/$roomId"
    fun chat(sessionId: String) = "chat/$sessionId"
}

@Composable
fun AppNavHost(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val mainRoutes = setOf(Routes.ROOMS, Routes.SEARCH, Routes.FAVORITES, Routes.PROFILE)
    var roomsRefreshKey by rememberSaveable { mutableIntStateOf(0) }
    var createGenreKey by rememberSaveable { mutableStateOf(Genres.default.key) }

    val roomArgs = listOf(
        navArgument("host") { type = NavType.StringType },
        navArgument("genre") { type = NavType.StringType },
        navArgument("roomId") { type = NavType.LongType },
    )

    LovelyAppShell(
        currentRoute = currentRoute,
        showChrome = currentRoute != null && currentRoute in mainRoutes,
        themeMode = themeMode,
        onThemeModeChange = onThemeModeChange,
        onDestinationSelected = { destination ->
            nav.navigate(destination.route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        },
        onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
        showCreateFab = currentRoute == Routes.ROOMS,
        onCreateRoom = { nav.navigate(Routes.create(createGenreKey)) { launchSingleTop = true } },
    ) {
        NavHost(navController = nav, startDestination = Routes.ROOMS) {
            composable(Routes.ROOMS) {
                RoomListScreen(
                    refreshKey = roomsRefreshKey,
                    onEnterRoom = { room ->
                        val host = Genres[room.genreKey]?.host ?: return@RoomListScreen
                        nav.navigate(Routes.entry(host, room.genreKey, room.id)) { launchSingleTop = true }
                    },
                    onPeekRoom = { room ->
                        val host = Genres[room.genreKey]?.host ?: return@RoomListScreen
                        nav.navigate(Routes.public(host, room.genreKey, room.id)) { launchSingleTop = true }
                    },
                    onGenreChanged = { createGenreKey = it.key },
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(
                    onEnterRoom = { room ->
                        Genres[room.genreKey]?.host?.let { host -> nav.navigate(Routes.entry(host, room.genreKey, room.id)) { launchSingleTop = true } }
                    },
                    onPeekRoom = { room ->
                        Genres[room.genreKey]?.host?.let { host -> nav.navigate(Routes.public(host, room.genreKey, room.id)) { launchSingleTop = true } }
                    },
                )
            }
            composable(Routes.FAVORITES) { FavoritesScreen() }
            composable(Routes.PROFILE) { ProfileScreen() }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    onBack = { nav.popBackStack() },
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
                        nav.navigate(Routes.chat(app.activeRooms.register(room))) {
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
                        nav.navigate(Routes.chat(app.activeRooms.register(room))) {
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
                    LaunchedEffect(Unit) { exit() }
                } else {
                    ChatScreen(room = room, onExit = exit)
                }
            }
        }
    }
}
