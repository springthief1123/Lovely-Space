package io.github.springthief1123.lovelyspace

import android.net.Uri
import androidx.navigation.NavController

internal object Routes {
    const val ROOMS = "rooms"
    const val SEARCH = "search"
    const val FAVORITES = "favorites"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
    const val SETTINGS_DISPLAY = "settings/display"
    const val SETTINGS_ROOMS = "settings/rooms"
    const val SETTINGS_HIDDEN = "settings/hidden"
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
    fun mainOrigin(value: String?): String = when (value) { SEARCH -> SEARCH; FAVORITES -> FAVORITES; else -> ROOMS }
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
