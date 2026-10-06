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
