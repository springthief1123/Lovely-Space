package io.github.springthief1123.lovelyspace

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.springthief1123.lovelyspace.ui.chat.ResumeChatBar
import io.github.springthief1123.lovelyspace.ui.chat.ChatActivity
import io.github.springthief1123.lovelyspace.ui.chat.ChatController
import kotlinx.coroutines.flow.MutableStateFlow
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.ui.chat.ChatScreen
import io.github.springthief1123.lovelyspace.ui.create.CreateRoomScreen
import io.github.springthief1123.lovelyspace.ui.entry.EntryScreen
import io.github.springthief1123.lovelyspace.ui.main.FavoritesScreen
import io.github.springthief1123.lovelyspace.ui.main.ProfileScreen
import io.github.springthief1123.lovelyspace.ui.main.SearchScreen
import io.github.springthief1123.lovelyspace.ui.main.RadarScreen
import io.github.springthief1123.lovelyspace.ui.main.SearchPresetSaver
import io.github.springthief1123.lovelyspace.data.SearchPreset
import io.github.springthief1123.lovelyspace.ui.settings.DisplaySettingsScreen
import io.github.springthief1123.lovelyspace.ui.settings.HiddenRoomsScreen
import io.github.springthief1123.lovelyspace.ui.settings.NotificationSettingsScreen
import io.github.springthief1123.lovelyspace.ui.settings.RefreshSettingsScreen
import io.github.springthief1123.lovelyspace.ui.settings.RoomListSettingsScreen
import io.github.springthief1123.lovelyspace.ui.settings.SettingsScreen
import io.github.springthief1123.lovelyspace.ui.shell.LovelyAppShell
import io.github.springthief1123.lovelyspace.ui.web.PublicRoomScreen

@Composable
fun AppNavHost() {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val mainRoutes = setOf(Routes.ROOMS, Routes.SEARCH, Routes.RADAR, Routes.FAVORITES, Routes.PROFILE)
    var roomsRefreshKey by rememberSaveable { mutableIntStateOf(0) }
    var createGenreKey by rememberSaveable { mutableStateOf(Genres.default.key) }

    var pendingPreset by rememberSaveable(stateSaver = SearchPresetSaver) { mutableStateOf<SearchPreset?>(null) }

    val roomArgs = listOf(
        navArgument("host") { type = NavType.StringType },
        navArgument("genre") { type = NavType.StringType },
        navArgument("roomId") { type = NavType.LongType },
    )

    val originArg = navArgument("origin") { type = NavType.StringType; defaultValue = Routes.ROOMS }

    val resumable by app.activeRooms.resumable.collectAsStateWithLifecycle()
    // 一覧を見ている間は「会話に戻る」を常に出す。× は帯を小さくするだけで、会話への入口は残す。記録は退室・部屋の終了で消える。
    val resumeCollapsed by app.activeRooms.collapsed.collectAsStateWithLifecycle()
    val resumeRoom = resumable?.takeIf { currentRoute in mainRoutes }
    // 接続があれば（会話画面を開いてから一覧に来たとき）、新着の数と待機中かを帯に出す。
    val resumeConnection = resumeRoom?.let { app.activeRooms.existingConnection(it.sessionId) as? ChatController }
    val resumeActivity by (resumeConnection?.activity ?: remember { MutableStateFlow(ChatActivity()) }).collectAsStateWithLifecycle()

    // 通知・お知らせが開かれたら該当の画面へ移る。
    val openedNotification by app.notificationInbox.opened.collectAsStateWithLifecycle()
    LaunchedEffect(openedNotification) {
        val notification = openedNotification ?: return@LaunchedEffect
        app.notificationInbox.consumeOpened()
        nav.openNotification(notification) { app.activeRooms.resume() }
    }

    LovelyAppShell(
        currentRoute = currentRoute,
        showChrome = currentRoute != null && currentRoute in mainRoutes,
        resumeBar = if (resumeRoom == null) null else { {
            ResumeChatBar(resumeRoom.room,
                activity = resumeActivity.takeIf { resumeConnection != null },
                collapsed = resumeCollapsed == resumeRoom.sessionId,
                onResume = { app.activeRooms.resume()?.let { nav.navigateToChat(it, currentRoute ?: Routes.ROOMS) } },
                onCollapse = { app.activeRooms.collapse(resumeRoom.sessionId) })
        } },
        onDestinationSelected = { destination -> nav.navigateMain(destination.route) },
        onOpenNotificationSettings = { nav.navigate(Routes.SETTINGS_NOTIFICATIONS) { launchSingleTop = true } },
        showCreateFab = currentRoute == Routes.ROOMS,
        onCreateRoom = { nav.navigate(Routes.create(createGenreKey, currentRoute ?: Routes.ROOMS)) { launchSingleTop = true } },
    ) {
        NavHost(
            navController = nav,
            startDestination = Routes.ROOMS,
            enterTransition = {
                fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 14 }
            },
            exitTransition = { fadeOut(tween(140)) },
            popEnterTransition = {
                fadeIn(tween(180)) + slideInHorizontally(tween(220)) { -it / 18 }
            },
            popExitTransition = {
                fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { it / 16 }
            },
        ) {
            composable(Routes.ROOMS) {
                SearchScreen(
                    refreshKey = roomsRefreshKey,
                    preset = pendingPreset, onPresetConsumed = { pendingPreset = null },
                    onEnterRoom = { room ->
                        val host = Genres[room.genreKey]?.host ?: return@SearchScreen
                        nav.navigate(Routes.entry(host, room.genreKey, room.id)) { launchSingleTop = true }
                    },
                    onPeekRoom = { room ->
                        val host = Genres[room.genreKey]?.host ?: return@SearchScreen
                        nav.navigate(Routes.public(host, room.genreKey, room.id)) { launchSingleTop = true }
                    },
                    onGenreChanged = { createGenreKey = it.key },
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(
                    onEnterRoom = { room ->
                        Genres[room.genreKey]?.host?.let { host ->
                            nav.navigate(Routes.entry(host, room.genreKey, room.id, Routes.SEARCH)) { launchSingleTop = true }
                        }
                    },
                    onPeekRoom = { room ->
                        Genres[room.genreKey]?.host?.let { host ->
                            nav.navigate(Routes.public(host, room.genreKey, room.id)) { launchSingleTop = true }
                        }
                    },
                )
            }
            composable(Routes.RADAR) {
                RadarScreen(onFindRooms = { nav.navigate(Routes.ROOMS) { launchSingleTop = true } },
                    onEnterRoom = { room -> Genres[room.genreKey]?.host?.let { host ->
                        nav.navigate(Routes.entry(host, room.genreKey, room.id, Routes.RADAR)) { launchSingleTop = true }
                    } },
                    onPeekRoom = { room -> Genres[room.genreKey]?.host?.let { host ->
                        nav.navigate(Routes.public(host, room.genreKey, room.id)) { launchSingleTop = true }
                    } })
            }
            composable(Routes.FAVORITES) {
                FavoritesScreen(
                    onApplyPreset = { pendingPreset = it; nav.navigate(Routes.ROOMS) { popUpTo(Routes.ROOMS); launchSingleTop = true } },
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
            composable(Routes.PROFILE) { ProfileScreen(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                onCreateRoom = { nav.navigate(Routes.create(createGenreKey, Routes.PROFILE)) { launchSingleTop = true } },
            ) }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenDisplay = { nav.navigate(Routes.SETTINGS_DISPLAY) { launchSingleTop = true } },
                    onOpenRoomList = { nav.navigate(Routes.SETTINGS_ROOMS) { launchSingleTop = true } },
                    onOpenRefresh = { nav.navigate(Routes.SETTINGS_REFRESH) { launchSingleTop = true } },
                    onOpenHiddenRooms = { nav.navigate(Routes.SETTINGS_HIDDEN) { launchSingleTop = true } },
                    onOpenNotifications = { nav.navigate(Routes.SETTINGS_NOTIFICATIONS) { launchSingleTop = true } },
                    onOpenLock = { nav.navigate(Routes.SETTINGS_LOCK) { launchSingleTop = true } },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.SETTINGS_DISPLAY) {
                DisplaySettingsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS_ROOMS) {
                RoomListSettingsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS_REFRESH) {
                RefreshSettingsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS_HIDDEN) {
                HiddenRoomsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS_LOCK) {
                io.github.springthief1123.lovelyspace.lock.AppLockSettingsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS_NOTIFICATIONS) {
                NotificationSettingsScreen(onBack = { nav.popBackStack() })
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
            composable(Routes.CREATE, arguments = listOf(navArgument("genre") { type = NavType.StringType }, originArg)) { entry ->
                val genre = Genres[entry.arguments!!.getString("genre")!!] ?: Genres.default
                val origin = Routes.mainOrigin(entry.arguments!!.getString("origin"))
                CreateRoomScreen(
                    genre = genre,
                    onBack = { nav.popBackStack() },
                    onCreated = { room ->
                        // 部屋を作り始めた画面（一覧・マイルーム）の上に会話を重ね、会話の後はそこへ戻る。
                        nav.navigateToChat(app.activeRooms.register(room), origin)
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
                // 退室で記録を消した後の再描画で、画面を閉じる処理を二度呼ばないよう、開いたときの部屋と接続を覚えておく。
                val room = remember(sessionId) { app.activeRooms[sessionId] }
                val origin = Routes.mainOrigin(entry.arguments!!.getString("origin"))
                val exit = {
                    app.activeRooms.remove(sessionId)
                    roomsRefreshKey++
                    nav.returnFromChat(origin)
                    Unit
                }
                val chat = remember(sessionId) { room?.let { app.activeRooms.connection(sessionId) { ChatController(app.client, it) } } }
                if (room == null || chat == null) {
                    LaunchedEffect(Unit) { exit() }
                } else {
                    // ← は部屋に残ったまま一覧へ。取得は ActiveRooms の接続が続ける。
                    ChatScreen(room = room, vm = chat, onBrowse = { nav.returnFromChat(origin) }, onExit = exit,
                        onEnded = { app.activeRooms.ended(sessionId) })
                }
            }
        }
    }
}
