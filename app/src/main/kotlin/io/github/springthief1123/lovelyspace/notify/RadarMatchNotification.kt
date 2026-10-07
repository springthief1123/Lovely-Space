package io.github.springthief1123.lovelyspace.notify

import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.data.RadarEvent
import io.github.springthief1123.lovelyspace.data.RadarEventKind

/**
 * 背景の巡回で記録した「新しい一致」を端末通知にする。一致以外の履歴は通知しない。
 * 1 部屋だけで入室できるなら入室画面を、それ以外はレーダーを開く。
 * 部屋の名前・待機メッセージは他の利用者の情報なので、ロック画面・本文の設定に従う [AppNotification.message] に入れる。
 */
fun RadarEvent.toMatchNotification(): AppNotification? {
    if (kind != RadarEventKind.SEARCH_MATCH || rooms.isEmpty()) return null
    val single = rooms.singleOrNull()
    val host = single?.let { Genres[it.genreKey]?.host }
    val target = if (single != null && host != null && single.action == RoomAction.ENTER)
        NotificationTarget.Room(host, single.genreKey, single.id) else NotificationTarget.Radar
    val names = rooms.take(MAX_NAMES).joinToString("、") { it.name ?: "名前なし" } +
        if (rooms.size > MAX_NAMES) " ほか" else ""
    return AppNotification(
        id = "radar-$id",
        kind = NotificationKind.RADAR_MATCH,
        title = "${origin?.label ?: "巡回"}：新しい一致",
        text = "${Genres[rooms.first().genreKey]?.label ?: rooms.first().genreKey}の${page ?: 1}ページで${rooms.size}件",
        target = target,
        at = at,
        message = if (single != null) listOf(names, single.message).filter { it.isNotBlank() }.joinToString("\n") else names,
    )
}

private const val MAX_NAMES = 3
