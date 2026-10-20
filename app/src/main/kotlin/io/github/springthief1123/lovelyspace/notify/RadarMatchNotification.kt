package io.github.springthief1123.lovelyspace.notify

import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.core.roomIdentity
import io.github.springthief1123.lovelyspace.data.RadarEvent
import io.github.springthief1123.lovelyspace.data.RadarEventKind

/**
 * 条件・名前で待つの「新しい一致」をお知らせ・端末通知にする。一致以外の記録は通知しない。
 * 続けて見つけた同じ条件の一致（[this] の全件）は 1 件にまとめ、[id] の通知を差し替える（`RadarNotices`）。
 * 前に一致して閉じた部屋の立て直し（[RadarEvent.rebuilds]）は、新しい人の部屋と分けて数える。
 * 1 部屋だけで入室できるなら入室画面を、それ以外はレーダーを開く。
 * 部屋の名前・待機メッセージは他の利用者の情報なので、ロック画面・本文の設定に従う [AppNotification.message] に入れる。
 */
fun List<RadarEvent>.toMatchNotification(id: String): AppNotification? {
    val matches = filter { it.kind == RadarEventKind.SEARCH_MATCH || it.kind == RadarEventKind.CANDIDATE_MATCH }
    val first = matches.firstOrNull() ?: return null
    val rooms = matches.flatMap { it.rooms }.distinctBy(::roomIdentity)
    if (rooms.isEmpty()) return null
    val named = first.kind == RadarEventKind.CANDIDATE_MATCH
    val single = rooms.singleOrNull()
    val host = single?.let { Genres[it.genreKey]?.host }
    val target = if (single != null && host != null && single.action == RoomAction.ENTER)
        NotificationTarget.Room(host, single.genreKey, single.id) else NotificationTarget.Radar
    val names = rooms.take(MAX_NAMES).joinToString("、") { it.name ?: "名前なし" } +
        if (rooms.size > MAX_NAMES) " ほか" else ""
    val genre = Genres[rooms.first().genreKey]?.label ?: rooms.first().genreKey
    val rebuilt = rooms.count { room -> matches.any { roomIdentity(room) in it.rebuilds } }
    val where = matches.singleOrNull()?.let { "${genre}の${it.page ?: 1}ページで" } ?: "${genre}で"
    return AppNotification(
        id = id,
        kind = if (named) NotificationKind.NAME_MATCH else NotificationKind.RADAR_MATCH,
        title = if (named) "名前で待つ「${first.origin?.label.orEmpty()}」：新しい部屋" else "${first.origin?.label ?: "巡回"}：新しい一致",
        text = "$where${rooms.size}件" + when {
            rebuilt == 0 -> ""
            rebuilt == rooms.size -> "（どれも閉じた部屋の立て直し）"
            else -> "（うち立て直し${rebuilt}件）"
        },
        target = target,
        at = matches.maxOf { it.at },
        message = if (single != null) listOf(names, single.message).filter { it.isNotBlank() }.joinToString("\n") else names,
    )
}

/** 1 件の一致だけの通知（まとめずに出すとき）。 */
fun RadarEvent.toMatchNotification(): AppNotification? = listOf(this).toMatchNotification("radar-$id")

private const val MAX_NAMES = 3
