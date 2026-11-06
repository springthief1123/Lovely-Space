package io.github.springthief1123.lovelyspace.core

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 制限応答を受けたホストの自動通信だけを休止する。期限の判定は単調時計で行う。 */
internal class HostAccessPause(
    private val clock: () -> Long,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val deadlines = mutableMapOf<String, Long>()

    @Synchronized fun observe(host: String, status: Int, retryAfter: String?) {
        if (status !in setOf(403, 429, 503)) return
        val value = retryAfter?.trim()
        val seconds = value?.toLongOrNull()?.takeIf { it >= 0 }
        val dateDelay = value?.let {
            runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - wallClock() }.getOrNull()
        }
        val delay = if (seconds != null) seconds.coerceAtMost(Long.MAX_VALUE / 1_000) * 1_000
            else dateDelay?.coerceAtLeast(0) ?: RoomPageSchedule.ERROR_INTERVAL_MS
        val at = clock()
        val until = if (at > Long.MAX_VALUE - delay) Long.MAX_VALUE else at + delay
        val key = host.lowercase()
        deadlines[key] = maxOf(deadlines[key] ?: Long.MIN_VALUE, until)
    }

    @Synchronized fun remaining(host: String): Long {
        val key = host.lowercase()
        val until = deadlines[key] ?: return 0
        val at = clock()
        if (until <= at) { deadlines.remove(key); return 0 }
        return if (at < 0 && until > Long.MAX_VALUE + at) Long.MAX_VALUE else until - at
    }

    fun check(host: String) {
        if (remaining(host) > 0) throw AutomaticAccessPausedException()
    }
}

/** ホストやURL・認証情報を画面やログへ残さない。手動操作は休止の対象外。 */
class AutomaticAccessPausedException : IOException("本家からの制限応答により、自動更新を一時停止しています")
