package io.github.springthief1123.lovelyspace.notify

import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.core.RoomStatus
import java.util.UUID

/**
 * 設定画面の「テスト通知を送る」で出す通知。見出し・本文・お知らせのカードは実際の通知（`PinEvent.toNotification`・
 * `toMatchNotification`）と同じ形にし、部屋主の名前を「テスト」にして見分ける。本家の実データは使わない。
 * 押しても本家に通信しないよう、開く先は部屋ではなく一覧・レーダーにする（そのためカードに「入室する」は出ない）。
 */
object NotificationSamples {
    private const val NAME = "テスト"
    private const val MESSAGE = "（テスト用の待機メッセージ）"

    fun of(kind: NotificationKind, now: Long = System.currentTimeMillis()): AppNotification {
        val id = UUID.randomUUID().toString()
        val room = Room(0, Genres.all.first().key, RoomStatus.WAITING, RoomAction.ENTER, null, NAME, Gender.FEMALE, 25, null, MESSAGE)
        val genre = Genres.all.first().label
        return when (kind) {
            NotificationKind.RADAR_MATCH -> AppNotification(id, kind, "テストの条件：新しい一致", "${genre}で1件",
                NotificationTarget.Radar, now, message = "$NAME\n$MESSAGE", room = room)
            NotificationKind.NAME_MATCH -> AppNotification(id, kind, "名前で待つ「$NAME」：新しい部屋", "${genre}で1件",
                NotificationTarget.Radar, now, message = "$NAME\n$MESSAGE", room = room)
            NotificationKind.ROOM_ENTRY -> AppNotification(id, kind, "テスト：相手が入室しました",
                "進行中の部屋に戻って会話を始められます", NotificationTarget.ActiveChat, now)
            NotificationKind.WAITLIST -> AppNotification(id, kind, "ピンした部屋が空きました", genre,
                NotificationTarget.Rooms, now, message = "$NAME\n$MESSAGE", room = room,
                namedTitle = "${room.ownerLabel()}の部屋が空きました")
            NotificationKind.ONGOING -> AppNotification(id, kind, "テスト：背景で確認しています",
                "常駐通知の表示例です", NotificationTarget.Radar, now)
        }
    }
}
