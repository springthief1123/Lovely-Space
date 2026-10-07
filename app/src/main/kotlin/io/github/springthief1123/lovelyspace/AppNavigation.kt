package io.github.springthief1123.lovelyspace

import android.net.Uri
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import io.github.springthief1123.lovelyspace.notify.AppNotification
import io.github.springthief1123.lovelyspace.notify.NotificationKind
import io.github.springthief1123.lovelyspace.notify.NotificationTarget

internal object Routes {
    const val ROOMS = "rooms"
    const val RADAR = "radar"
    const val SEARCH = "search"
    const val FAVORITES = "favorites"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
    const val SETTINGS_DISPLAY = "settings/display"
    const val SETTINGS_ROOMS = "settings/rooms"
    const val SETTINGS_HIDDEN = "settings/hidden"
    const val SETTINGS_NOTIFICATIONS = "settings/notifications"
    const val ENTRY = "entry/{host}/{genre}/{roomId}?origin={origin}"
    /** pwdはrouteに載せず、ActiveRoomsの一時IDだけを渡す。 */
    const val CHAT = "chat/{session}?origin={origin}"
    const val CREATE = "create/{genre}"
    const val PUBLIC = "public/{host}/{genre}/{roomId}"

    fun entry(host: String, genre: String, roomId: Long, origin: String = ROOMS) =
        "entry/${Uri.encode(host)}/${Uri.encode(genre)}/$roomId?origin=${mainOrigin(origin)}"
    fun create(genre: String) = "create/${Uri.encode(genre)}"
    fun public(host: String, genre: String, roomId: Long) = "public/${Uri.encode(host)}/${Uri.encode(genre)}/$roomId"
    fun chat(sessionId: String, origin: String) = "chat/${Uri.encode(sessionId)}?origin=${mainOrigin(origin)}"
    fun mainOrigin(value: String?): String = when (value) { RADAR -> RADAR; SEARCH -> SEARCH; FAVORITES -> FAVORITES; PROFILE -> PROFILE; else -> ROOMS }
}

/** 入室フォームだけを閉じ、元の一覧のViewModel・取得済みページを残す。 */
internal fun NavController.navigateToChat(sessionId: String, origin: String = Routes.ROOMS) {
    val target = Routes.mainOrigin(origin)
    navigate(Routes.chat(sessionId, target)) {
        popUpTo(target)
        launchSingleTop = true
    }
}

internal fun NavController.returnFromChat(origin: String) {
    if (!popBackStack(Routes.mainOrigin(origin), inclusive = false)) {
        popBackStack(Routes.ROOMS, inclusive = false)
    }
}

/** 下のタブと同じ移り方で主画面へ移る（各タブの状態を残す）。 */
internal fun NavController.navigateMain(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * 通知・お知らせから該当の画面を開く。部屋は元のタブの上に入室画面を重ね、戻ると元のタブに戻る。
 * [resumeChat] は進行中の部屋の一時 ID を返す（無ければ null）。
 */
internal fun NavController.openNotification(notification: AppNotification, resumeChat: () -> String?) {
    when (val target = notification.target) {
        is NotificationTarget.Room -> {
            val origin = if (notification.kind == NotificationKind.RADAR_MATCH) Routes.RADAR else Routes.ROOMS
            showMain(origin)
            navigate(Routes.entry(target.host, target.genreKey, target.roomId, origin)) { launchSingleTop = true }
        }
        NotificationTarget.ActiveChat -> resumeChat()?.let { navigateToChat(it, Routes.ROOMS) } ?: showMain(Routes.ROOMS)
        NotificationTarget.Radar -> showMain(Routes.RADAR)
        NotificationTarget.Rooms -> showMain(Routes.ROOMS)
    }
}

/**
 * 通知から主画面を出す。一覧（開始画面）は常に履歴の底にあるので、その上を閉じて戻る。
 * 一覧へ [navigateMain] で移ると、他のタブにいたときにそのタブが出たままになる（ChatNavigationTest で確認）。
 */
private fun NavController.showMain(route: String) {
    if (route != Routes.ROOMS) return navigateMain(route)
    if (currentDestination?.route != Routes.ROOMS) popBackStack(Routes.ROOMS, inclusive = false)
}
