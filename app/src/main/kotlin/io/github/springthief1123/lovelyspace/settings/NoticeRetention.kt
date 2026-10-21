package io.github.springthief1123.lovelyspace.settings

import io.github.springthief1123.lovelyspace.notify.NotificationKind

/**
 * お知らせ（通知ベル）に残す時間。種類ごとに設定でき、過ぎたものはお知らせからも端末通知からも消す。
 * 空き枠・新着の部屋は数分で埋まる・閉じるので、既定では短くする（2026-10-21 Yuya）。
 */
enum class NoticeRetention(val label: String, val millis: Long?) {
    MINUTES_10("10分", 10 * 60_000L),
    MINUTES_30("30分", 30 * 60_000L),
    HOUR_1("1時間", 60 * 60_000L),
    HOURS_3("3時間", 3 * 60 * 60_000L),
    HOURS_12("12時間", 12 * 60 * 60_000L),
    DAY_1("1日", 24 * 60 * 60_000L),
    DAYS_7("7日", 7 * 24 * 60 * 60_000L),
    KEEP("消さない", null),
    ;

    companion object {
        /** 設定できる種類（お知らせに残る種類）。 */
        val kinds: List<NotificationKind> get() = NotificationKind.entries.filter { it.logged }

        fun default(kind: NotificationKind): NoticeRetention = when (kind) {
            NotificationKind.WAITLIST, NotificationKind.RADAR_MATCH -> HOUR_1
            NotificationKind.NAME_MATCH -> HOURS_3
            NotificationKind.ROOM_ENTRY, NotificationKind.ONGOING -> KEEP
        }

        val defaults: Map<NotificationKind, NoticeRetention> get() = kinds.associateWith(::default)
    }
}
