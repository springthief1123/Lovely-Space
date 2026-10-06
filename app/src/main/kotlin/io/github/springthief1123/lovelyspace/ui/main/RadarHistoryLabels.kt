package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.data.*

internal fun RadarEventKind.historyLabel(): String = when (this) {
    RadarEventKind.SEARCH_MATCH -> "条件の新しい一致"
    RadarEventKind.CANDIDATE_MATCH -> "候補の新しい一致"
    RadarEventKind.ROOM_STATUS -> "部屋の状態変化"
    RadarEventKind.IDENTITY_WARNING -> "異なるプロフィール"
    RadarEventKind.PROFILE_UNCONFIRMED -> "プロフィール未確認"
    RadarEventKind.LEGACY -> "以前の履歴"
}
internal fun RadarEventOrigin.historyLabel(): String = "${when (type) {
    RadarOriginType.PLAN -> "巡回"
    RadarOriginType.CANDIDATE -> "候補"
    RadarOriginType.ROOM -> "部屋追跡"
}} · $label"

/** 同名の条件も内容を添え、内容まで同じ記録は安定した順の番号で見分ける。 */
internal fun historyOriginOptions(events: List<RadarEvent>): List<Pair<String, String>> {
    val origins = events.mapNotNull { it.origin }.distinctBy { it.key }.sortedBy { it.key }
    val labels = origins.associate { it.key to listOf(it.historyLabel(), it.description).filter { text -> text.isNotBlank() }.joinToString(" · ") }
    return origins.map { origin ->
        val label = labels.getValue(origin.key)
        val same = origins.filter { labels[it.key] == label }
        origin.key to if (same.size > 1) "$label（記録${same.indexOf(origin) + 1}）" else label
    }
}
