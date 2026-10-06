package io.github.springthief1123.lovelyspace.ui.chat

/** 最新にいる間だけ追従し、遡っている間は相手の発言の新着数を保持する。 */
internal data class ChatReadingState(
    val latestId: Long? = null,
    val atLatest: Boolean = true,
    val unreadIds: Set<Long> = emptySet(),
) {
    fun onLines(lines: List<UiLine>): ChatReadingState {
        val newest = lines.firstOrNull()?.id
        val previous = latestId
        // 初回表示とログ消去後は、新しいログの先頭から読み始める。
        if (previous == null || lines.none { it.id <= previous }) return ChatReadingState(latestId = newest)
        val currentIds = lines.map { it.id }.toSet()
        val added = lines.filter { it.id > previous && !it.isMine && !it.line.isNotice }.map { it.id }
        return copy(latestId = newest, unreadIds = if (atLatest) emptySet() else (unreadIds + added).intersect(currentIds))
    }

    fun onViewport(index: Int, offset: Int): ChatReadingState {
        val latest = index == 0 && offset == 0
        return copy(atLatest = latest, unreadIds = if (latest) emptySet() else unreadIds)
    }
}
