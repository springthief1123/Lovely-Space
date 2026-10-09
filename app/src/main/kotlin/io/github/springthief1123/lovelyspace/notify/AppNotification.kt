package io.github.springthief1123.lovelyspace.notify

import io.github.springthief1123.lovelyspace.settings.NotificationPreview
import org.json.JSONObject

/**
 * 端末通知の種類。種類ごとに通知チャンネルを分け、利用者が端末の設定で個別にオン・オフできるようにする。
 * [channelId] は一度出すと端末に残るので変えない。
 */
// [delivered] はアプリがもう出している通知か。false は通知の種類だけ先に用意してあるもの（入室者あり #43）。
enum class NotificationKind(val channelId: String, val label: String, val description: String, val logged: Boolean = true, val delivered: Boolean = true) {
    RADAR_MATCH("radar_match", "巡回の一致", "レーダーの巡回で条件に合う部屋が見つかったとき"),
    ROOM_ENTRY("room_entry", "入室者あり", "自分の部屋に相手が入ったとき", delivered = false),
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

internal fun NotificationTarget.toJson(): JSONObject = when (this) {
    is NotificationTarget.Room -> JSONObject().put("type", "room").put("host", host).put("genre", genreKey).put("roomId", roomId)
    NotificationTarget.ActiveChat -> JSONObject().put("type", "chat")
    NotificationTarget.Radar -> JSONObject().put("type", "radar")
    NotificationTarget.Rooms -> JSONObject().put("type", "rooms")
}

internal fun notificationTarget(o: JSONObject): NotificationTarget = when (val type = o.getString("type")) {
    "room" -> NotificationTarget.Room(o.getString("host"), o.getString("genre"), o.getLong("roomId"))
    "chat" -> NotificationTarget.ActiveChat
    "radar" -> NotificationTarget.Radar
    "rooms" -> NotificationTarget.Rooms
    else -> error("Unknown target: $type")
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
