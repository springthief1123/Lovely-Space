package io.github.springthief1123.lovelyspace.notify

import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.data.WaitlistEntry

/**
 * 順番待ちの部屋に空きが出たときの通知。タップすると、既定のプロフィールで入力済みの入室画面を開く。
 * 満室の間は一覧に名前が出ないので、同じ部屋かどうかは（一覧に隠れている）募集文が登録時と同じかで知らせる。
 */
fun WaitlistEntry.toOpenedNotification(): AppNotification? {
    val opened = openedRoom ?: return null
    val host = Genres[opened.genreKey]?.host ?: return null
    val genre = Genres[opened.genreKey]?.label ?: opened.genreKey
    val sameMessage = room.message.isNotBlank() && room.message == opened.message
    return AppNotification(
        id = "waitlist-$key-${changedAt ?: registeredAt}",
        kind = NotificationKind.WAITLIST,
        title = "順番待ちの部屋に空きが出ました",
        text = "${genre}の部屋に入室できます。" +
            if (sameMessage) "募集文は登録時と同じです。" else "募集文が登録時と違うため、同じ部屋かを入室画面で確かめてください。",
        target = NotificationTarget.Room(host, opened.genreKey, opened.id),
        at = changedAt ?: System.currentTimeMillis(),
        message = listOfNotNull(opened.name, opened.message.takeIf { it.isNotBlank() }).joinToString("\n").ifBlank { null },
    )
}
