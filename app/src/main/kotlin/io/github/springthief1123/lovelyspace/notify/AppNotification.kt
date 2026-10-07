package io.github.springthief1123.lovelyspace.notify

import io.github.springthief1123.lovelyspace.settings.NotificationPreview

/**
 * 端末通知の種類。種類ごとに通知チャンネルを分け、利用者が端末の設定で個別にオン・オフできるようにする。
 * [channelId] は一度出すと端末に残るので変えない。
 */
enum class NotificationKind(val channelId: String, val label: String, val description: String, val logged: Boolean = true) {
    RADAR_MATCH("radar_match", "巡回の一致", "レーダーの巡回で条件に合う部屋が見つかったとき"),
    ROOM_ENTRY("room_entry", "入室者あり", "自分の部屋に相手が入ったとき"),
    WAITLIST("waitlist", "順番待ち", "満室だった部屋に空きが出たとき"),
    /** 背景で巡回・待機している間の表示。状態を示すだけなので、お知らせの履歴には残さない。 */
    ONGOING("ongoing", "常駐", "背景で巡回・待機している間の表示", logged = false),
}

/** 通知をタップしたときに開く画面。 */
sealed interface NotificationTarget {
    /** 部屋の入室画面。 */
    data class Room(val host: String, val genreKey: String, val roomId: Long) : NotificationTarget
    /** 進行中の部屋の会話画面（無ければ一覧）。 */
    data object ActiveChat : NotificationTarget
    data object Radar : NotificationTarget
    data object Rooms : NotificationTarget
}

/**
 * 端末通知とお知らせ（通知ベル）の 1 件。
 * [message] は他の利用者の待機メッセージなど、利用者の設定によっては通知に出さない本文。
 */
data class AppNotification(
    val id: String,
    val kind: NotificationKind,
    val title: String,
    val text: String,
    val target: NotificationTarget,
    val at: Long,
    val message: String? = null,
    val read: Boolean = false,
) {
    /** 端末通知に出す本文。 */
    fun body(preview: NotificationPreview): String =
        if (preview == NotificationPreview.NO_MESSAGE || message.isNullOrBlank()) text else "$text\n$message"

    /** ロック画面に内容を出してよいか。 */
    fun publicOnLockScreen(preview: NotificationPreview): Boolean = preview == NotificationPreview.FULL
}
