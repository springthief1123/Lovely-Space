package io.github.springthief1123.lovelyspace.core

/** ページを更新単位で置き換え、ページ移動した同じIDは新しい観測を優先する。 */
data class RoomPageWindow(
    val pages: Map<Int, List<Room>> = emptyMap(),
    val revisions: Map<Int, Long> = emptyMap(),
    val lastPage: Int = 1,
    val revision: Long = 0,
) {
    fun observe(page: RoomListPage, observationRevision: Long? = null): RoomPageWindow {
        val nextRevision = observationRevision ?: revision + 1
        // キャッシュを再利用した呼び出しを新たな観測として数えない。
        if (observationRevision != null && nextRevision <= (revisions[page.page] ?: Long.MIN_VALUE)) return this
        val limit = if (nextRevision >= revision) page.lastPage.coerceAtLeast(1) else lastPage
        return copy(
            pages = (pages + (page.page to page.rooms)).filterKeys { it <= limit },
            revisions = (revisions + (page.page to nextRevision)).filterKeys { it <= limit },
            lastPage = limit,
            revision = maxOf(revision, nextRevision),
        )
    }

    val rooms: List<Room> get() {
        val newest = linkedMapOf<String, Room>()
        pages.keys.sortedByDescending { revisions[it] }.forEach { page ->
            pages[page].orEmpty().forEach { newest.putIfAbsent(roomIdentity(it), it) }
        }
        return pages.toSortedMap().values.flatten().mapNotNull { newest.remove(roomIdentity(it)) }
    }
}

/** 最新ページを20秒ごとに優先し、その間に残りページを巡回する。失敗ページは進めない。 */
class RoomPageSchedule(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var headAt: Long? = null
    private var nextPage = 2
    fun next(lastPage: Int): Int = if (headAt == null || clock() - headAt!! >= HEAD_INTERVAL_MS || lastPage <= 1) 1
        else nextPage.coerceAtMost(lastPage).coerceAtLeast(2)
    fun completed(page: Int, lastPage: Int) {
        if (page == 1) headAt = clock()
        else nextPage = if (page < lastPage) page + 1 else 2
    }
    companion object {
        const val HEAD_INTERVAL_MS = 20_000L
        const val STEP_INTERVAL_MS = 3_000L
    }
}
