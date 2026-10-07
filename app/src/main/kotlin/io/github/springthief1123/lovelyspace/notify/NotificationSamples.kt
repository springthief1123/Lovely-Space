package io.github.springthief1123.lovelyspace.notify

import java.util.UUID

/** 設定画面の「テスト通知を送る」で出す通知。本家の実データは使わない。 */
object NotificationSamples {
    fun of(kind: NotificationKind, now: Long = System.currentTimeMillis()): AppNotification {
        val id = UUID.randomUUID().toString()
        return when (kind) {
            NotificationKind.RADAR_MATCH -> AppNotification(id, kind, "テスト：条件に合う部屋があります",
                "全国の一覧で 1 件見つかりました", NotificationTarget.Radar, now, message = "（テスト用の待機メッセージ）")
            NotificationKind.ROOM_ENTRY -> AppNotification(id, kind, "テスト：相手が入室しました",
                "進行中の部屋に戻って会話を始められます", NotificationTarget.ActiveChat, now)
            NotificationKind.WAITLIST -> AppNotification(id, kind, "テスト：順番待ちの部屋に空きが出ました",
                "一覧から入室できます", NotificationTarget.Rooms, now, message = "（テスト用の待機メッセージ）")
            NotificationKind.ONGOING -> AppNotification(id, kind, "テスト：背景で確認しています",
                "常駐通知の表示例です", NotificationTarget.Radar, now)
        }
    }
}
