package io.github.springthief1123.lovelyspace.ai

import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.data.ObservedRoomPage

/**
 * 今このプロセスにすでにある一覧だけを使う。HTTP・DB・ログ・通知への依存は持たない。
 * AIへ渡すのは、一覧で誰でも読める待機中の募集文のみ。名前や部屋識別子は含めない。
 */
internal object ExistingRoomSnapshot {
    const val MAX_DOCUMENTS = 100
    const val MAX_MESSAGE_LENGTH = 240
    const val MAX_QUERY_LENGTH = 120
    const val MAX_AGE_MS = 10 * 60 * 1_000L

    /** 別ジャンルや複数ページで同じ部屋を観測しても重複埋め込みを行わない。 */
    fun select(pages: Collection<ObservedRoomPage>, nowMs: Long): List<String> {
        val roomPages = pages.asSequence()
            .filter { page ->
                val age = nowMs - page.confirmedAt
                age in 0..MAX_AGE_MS
            }
            .sortedByDescending { it.confirmedAt }
            .flatMap { it.page.rooms.asSequence() }
        val seen = HashSet<Pair<String, Long>>()
        return roomPages
            .filter { it.isVisiblePublicMessage() }
            .filter { seen.add(it.genreKey to it.id) }
            .map { it.message.trim().take(MAX_MESSAGE_LENGTH) }
            .take(MAX_DOCUMENTS)
            .toList()
    }

    private fun Room.isVisiblePublicMessage(): Boolean = !isFull && message.isNotBlank()

    fun validateQuery(query: String): String {
        val normalized = query.trim()
        require(normalized.isNotBlank()) { "検索文を入力してください" }
        require(normalized.length <= MAX_QUERY_LENGTH) { "検索文は120文字までです" }
        return normalized
    }
}
