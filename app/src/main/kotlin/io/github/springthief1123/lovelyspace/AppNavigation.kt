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
    const val PROFILE = "profile"
    const val SITE_REENTRY = "site/reentry"
    const val SETTINGS = "settings"
    const val SETTINGS_MEMBER = "settings/member"
    const val SETTINGS_DISPLAY = "settings/display"
    const val SETTINGS_ROOMS = "settings/rooms"
    const val SETTINGS_REFRESH = "settings/refresh"
    const val SETTINGS_ENTRY = "settings/entry"
    const val SETTINGS_HIDDEN = "settings/hidden"
    const val SETTINGS_NG_WORDS = "settings/ngwords"
    const val SETTINGS_NOTIFICATIONS = "settings/notifications"
    const val SETTINGS_LOCK = "settings/lock"
    const val SETTINGS_DISGUISE = "settings/disguise"
    /** [auto] は入力済みのまま入室まで進める（順番待ちの通知・自動入室から開いたとき）。 */
    const val ENTRY = "entry/{host}/{genre}/{roomId}?origin={origin}&auto={auto}&public={public}"
    /** pwdはrouteに載せず、ActiveRoomsの一時IDだけを渡す。 */
    const val CHAT = "chat/{session}?origin={origin}"
    /** [origin] は部屋の作成を始めた主画面。会話の後はそこへ戻る。 */
    const val CREATE = "create/{genre}?origin={origin}"
    const val PUBLIC = "public/{host}/{genre}/{roomId}"

    /** [listedPublic] は一覧で公開の部屋だったか。開いたときに満室になっていたら、公開ルームとして覗く。 */
    fun entry(host: String, genre: String, roomId: Long, origin: String = ROOMS, auto: Boolean = false, listedPublic: Boolean = false) =
        "entry/${Uri.encode(host)}/${Uri.encode(genre)}/$roomId?origin=${mainOrigin(origin)}&auto=$auto&public=$listedPublic"
    fun create(genre: String, origin: String = ROOMS) = "create/${Uri.encode(genre)}?origin=${mainOrigin(origin)}"
    fun public(host: String, genre: String, roomId: Long) = "public/${Uri.encode(host)}/${Uri.encode(genre)}/$roomId"
    fun chat(sessionId: String, origin: String) = "chat/${Uri.encode(sessionId)}?origin=${mainOrigin(origin)}"
    fun mainOrigin(value: String?): String = when (value) { RADAR -> RADAR; SEARCH -> SEARCH; PROFILE -> PROFILE; else -> ROOMS }
}

/** 下のタブの並び（見つける・レーダー・マイルーム）での位置。検索は見つけるの位置。タブでなければ null。 */
internal fun mainTabIndex(route: String?): Int? = when (route) {
    Routes.ROOMS, Routes.SEARCH -> 0
    Routes.RADAR -> 1
    Routes.PROFILE -> 2
    else -> null
}

/** タブを移るときの向き。右のタブへは 1、左のタブへは -1、タブ同士の移動でなければ 0。 */
internal fun mainTabDirection(from: String?, to: String?): Int {
    val start = mainTabIndex(from) ?: return 0
    val end = mainTabIndex(to) ?: return 0
    return end.compareTo(start)
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
 * [enterFromWaitlist] は順番待ちの通知から開いたら入室まで進めるか（設定の「通知から開いたらすぐ入室する」）。
 * [enterNow] はお知らせの「入室する」から開いた（部屋なら確認を飛ばして入室する）。
 * [resumeChat] は進行中の部屋の一時 ID を返す（無ければ null）。
 */
internal fun NavController.openNotification(notification: AppNotification, enterFromWaitlist: Boolean = false, enterNow: Boolean = false,
    resumeChat: () -> String?) {
    when (val target = notification.target) {
        is NotificationTarget.Room -> {
            // 条件・名前で待つの一致はレーダーの機能なので、戻るとレーダーに戻す。
            val fromRadar = notification.kind == NotificationKind.RADAR_MATCH || notification.kind == NotificationKind.NAME_MATCH
            val origin = if (fromRadar) Routes.RADAR else Routes.ROOMS
            showMain(origin)
            // 順番待ちの空きは取り合いなので、設定が有効なら入力済みのまま入室まで進める。
            // 待機メッセージが登録時と違う（別の部屋かもしれない）ときは、入室画面で確かめてもらう。
            // お知らせの「入室する」は、見つける・レーダーの「入室する」と同じく確認を飛ばして入る（利用者が押した 1 回だけ）。
            // ただし待機メッセージが違う空き（「待機メッセージ変更」のお知らせ）は、「入室する」でも入室画面で確かめてもらう。
            val unverified = notification.kind == NotificationKind.WAITLIST && !target.verified
            val auto = !unverified && (enterNow || (enterFromWaitlist && notification.kind == NotificationKind.WAITLIST))
            navigate(Routes.entry(target.host, target.genreKey, target.roomId, origin, auto)) { launchSingleTop = true }
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
