package io.github.springthief1123.lovelyspace

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

    val originArg = navArgument("origin") { type = NavType.StringType; defaultValue = Routes.ROOMS }

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
                        Genres[room.genreKey]?.host?.let { host -> nav.navigate(Routes.entry(host, room.genreKey, room.id, Routes.SEARCH)) { launchSingleTop = true } }
                    },
                    onPeekRoom = { room ->
                        Genres[room.genreKey]?.host?.let { host -> nav.navigate(Routes.public(host, room.genreKey, room.id)) { launchSingleTop = true } }
                    },
                )
            }
            composable(Routes.FAVORITES) {
                FavoritesScreen(
                    onEnterRoom = { room ->
                        Genres[room.genreKey]?.host?.let { host ->
                            nav.navigate(Routes.entry(host, room.genreKey, room.id, Routes.FAVORITES)) { launchSingleTop = true }
                        }
                    },
                    onPeekRoom = { room ->
                        Genres[room.genreKey]?.host?.let { host ->
                            nav.navigate(Routes.public(host, room.genreKey, room.id)) { launchSingleTop = true }
                        }
                    },
                )
            }
            composable(Routes.PROFILE) { ProfileScreen() }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.ENTRY, arguments = roomArgs + originArg) { entry ->
                val args = entry.arguments!!
                EntryScreen(
                    host = args.getString("host")!!,
                    genreKey = args.getString("genre")!!,
                    roomId = args.getLong("roomId"),
                    onBack = { nav.popBackStack() },
                    onEntered = { room ->
                        nav.navigateToChat(app.activeRooms.register(room), Routes.mainOrigin(args.getString("origin")))
                    },
                )
            }
            composable(Routes.CREATE, arguments = listOf(navArgument("genre") { type = NavType.StringType })) { entry ->
                val genre = Genres[entry.arguments!!.getString("genre")!!] ?: Genres.default
                CreateRoomScreen(
                    genre = genre,
                    onBack = { nav.popBackStack() },
                    onCreated = { room ->
                        nav.navigateToChat(app.activeRooms.register(room))
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
            composable(Routes.CHAT, arguments = listOf(navArgument("session") { type = NavType.StringType }, originArg)) { entry ->
                val sessionId = entry.arguments!!.getString("session")!!
                val room = app.activeRooms[sessionId]
                val exit = {
                    app.activeRooms.remove(sessionId)
                    roomsRefreshKey++
                    nav.returnFromChat(Routes.mainOrigin(entry.arguments!!.getString("origin")))
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
