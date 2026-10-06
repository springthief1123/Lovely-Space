package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.RoomQuery

enum class RadarCheckStatus { PENDING, CHECKING, CONFIRMED, FAILED, SKIPPED }

/** 1回の手動巡回。取得ページを共有しても、実際の通信単位で数える。起動中だけ保持する。 */
data class RadarPageCheck(
    val query: RoomQuery,
    val status: RadarCheckStatus = RadarCheckStatus.PENDING,
    val confirmedAt: Long? = null,
    val matches: Int = 0,
    val message: String? = null,
)
data class RadarScanReport(
    val startedAt: Long,
    val pages: List<RadarPageCheck>,
    val finishedAt: Long? = null,
    val newEvents: Int = 0,
    val interrupted: Boolean = false,
) {
    val confirmed: Int get() = pages.count { it.status == RadarCheckStatus.CONFIRMED }
    val failed: Int get() = pages.count { it.status == RadarCheckStatus.FAILED }
    val skipped: Int get() = pages.count { it.status == RadarCheckStatus.SKIPPED }
    val completed: Int get() = confirmed + failed + skipped
    /** 各取得ページ内では重複を除く。異なるページの同じ部屋は別々の確認として数える。 */
    val matches: Int get() = pages.sumOf { it.matches }
}
