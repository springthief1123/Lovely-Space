package io.github.springthief1123.lovelyspace.notify

import io.github.springthief1123.lovelyspace.settings.NotificationPreview
import org.json.JSONObject

/**
 * 端末通知の種類。種類ごとに通知チャンネルを分け、利用者が端末の設定で個別にオン・オフできるようにする。
 * [channelId] は一度出すと端末に残るので変えない（重要度はアプリから上げられないので、上げるときだけ新しい ID にし、古いチャンネルは消す）。
 */
// [delivered] はアプリがもう出している通知か。false は通知の種類だけ先に用意してあるもの（入室者あり #43）。
enum class NotificationKind(val channelId: String, val label: String, val description: String, val logged: Boolean = true, val delivered: Boolean = true) {
    // 新しい部屋は秒単位で埋まるので、画面の上に出る重要度に上げた（2026-10-21。以前の ID は "radar_match"）。
    RADAR_MATCH("radar_match_v2", "条件の新着", "「新着を知らせる」条件に合う部屋が見つかったとき"),
    NAME_MATCH("name_match", "名前で待つ", "名前で待つ人の部屋が新しく建ったとき"),
    ROOM_ENTRY("room_entry", "入室者あり", "自分の部屋に相手が入ったとき", delivered = false),
    WAITLIST("waitlist", "ピン", "ピンした部屋が空いたとき・似た部屋が建ったとき"),
    /** 背景で巡回・待機している間の表示。状態を示すだけなので、お知らせの履歴には残さない。 */
    ONGOING("ongoing", "常駐", "背景で巡回・待機している間の表示", logged = false),
}

/** 通知をタップしたときに開く画面。 */
sealed interface NotificationTarget {
    /**
     * 部屋の入室画面。[verified] は登録時と同じ部屋だと照合できた（順番待ちで待機メッセージが同じ）こと。
     * 照合できた部屋だけ、通知から開いたときに入室まで進める。
     */
    data class Room(val host: String, val genreKey: String, val roomId: Long, val verified: Boolean = false) : NotificationTarget
    /** 進行中の部屋の会話画面（無ければ一覧）。 */
    data object ActiveChat : NotificationTarget
    data object Radar : NotificationTarget
    data object Rooms : NotificationTarget
}

internal fun NotificationTarget.toJson(): JSONObject = when (this) {
    is NotificationTarget.Room -> JSONObject().put("type", "room").put("host", host).put("genre", genreKey).put("roomId", roomId).put("verified", verified)
    NotificationTarget.ActiveChat -> JSONObject().put("type", "chat")
    NotificationTarget.Radar -> JSONObject().put("type", "radar")
    NotificationTarget.Rooms -> JSONObject().put("type", "rooms")
}

internal fun notificationTarget(o: JSONObject): NotificationTarget = when (val type = o.getString("type")) {
    "room" -> NotificationTarget.Room(o.getString("host"), o.getString("genre"), o.getLong("roomId"), o.optBoolean("verified"))
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
