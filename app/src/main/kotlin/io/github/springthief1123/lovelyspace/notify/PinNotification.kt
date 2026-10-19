package io.github.springthief1123.lovelyspace.notify

import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.data.PinEvent
import kotlin.math.roundToInt

/**
 * ピンの見張りで起きたことの通知。空いたら入室画面を、似た部屋が建ったらレーダー（同じ人か確かめる）を開く。
 * 空いた部屋は、待機メッセージがピンしたときと同じときだけ同じ部屋として入室まで進める（順番待ちと同じ）。
 * 部屋の名前・待機メッセージは他の利用者の情報なので、ロック画面・本文の設定に従う [AppNotification.message] に入れる。
 */
fun PinEvent.toNotification(at: Long = System.currentTimeMillis()): AppNotification? {
    val genre = Genres[pin.room.genreKey] ?: return null
    return when (this) {
        is PinEvent.Opened -> {
            val opened = pin.room
            val sameMessage = registered.message.isNotBlank() && registered.message == opened.message
            AppNotification(
                id = "pin-${pin.key}-$at",
                kind = NotificationKind.WAITLIST,
                title = "ピンした部屋が空きました",
                text = "${genre.label}の部屋に入室できます。" +
                    if (sameMessage) "待機メッセージはピンしたときと同じです。" else "待機メッセージがピンしたときと違うため、同じ部屋かを入室画面で確かめてください。",
                target = NotificationTarget.Room(genre.host, opened.genreKey, opened.id, verified = sameMessage),
                at = at,
                message = listOfNotNull(opened.name, opened.message.takeIf { it.isNotBlank() }).joinToString("\n").ifBlank { null },
            )
        }
        is PinEvent.Similar -> AppNotification(
            id = "pin-similar-${pin.key}-${similar.room.id}",
            kind = NotificationKind.WAITLIST,
            title = "ピンした部屋に似た部屋が建ちました",
            text = "${genre.label}で、閉じた「${pin.name}」に似た部屋（待機メッセージが${(similar.similarity * 100).roundToInt()}%一致）が見つかりました。同じ人かをレーダーで確かめてください。",
            target = NotificationTarget.Radar,
            at = at,
            message = listOfNotNull(similar.room.name, similar.room.message.takeIf { it.isNotBlank() }).joinToString("\n").ifBlank { null },
        )
    }
}
