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

/**
 * 新着が出る 1 ページ目を [HEAD_INTERVAL_MS] ごとに優先し、その間に残りページを巡回する。失敗ページは進めない。
 * 通信は [ShaloveClient] が 3 秒以上空けるので、1 ページ目と残りのページをおおむね交互に取ることになる。
 *
 * ただし、まだ一度も読んでいないページ（[next] の `loaded` に無いページ）があるうちは、それを先に順に読み切る。
 * 検索の結果が全ページそろうまでを短くするためで、その間の 1 ページ目は [SWEEP_HEAD_INTERVAL_MS] ごとにする。
 */
class RoomPageSchedule(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var headAt: Long? = null
    private var nextPage = 2
    fun next(lastPage: Int, loaded: Set<Int> = emptySet()): Int {
        val head = headAt ?: return 1
        if (lastPage <= 1) return 1
        val elapsed = clock() - head
        val unread = (2..lastPage).firstOrNull { it !in loaded }
        if (unread != null && loaded.isNotEmpty()) return if (elapsed >= SWEEP_HEAD_INTERVAL_MS) 1 else unread
        return if (elapsed >= HEAD_INTERVAL_MS) 1 else nextPage.coerceAtMost(lastPage).coerceAtLeast(2)
    }
    fun completed(page: Int, lastPage: Int) {
        if (page == 1) headAt = clock()
        else nextPage = if (page < lastPage) page + 1 else 2
    }
    companion object {
        /** 1 ページ目を取り直す間隔。Chrome 拡張と同じ 4 秒（Yuya の決定、2026-10-09）。 */
        const val HEAD_INTERVAL_MS = 4_000L
        const val STEP_INTERVAL_MS = 3_000L
        /** まだ読んでいないページを読み切る間の、1 ページ目を取り直す間隔。 */
        const val SWEEP_HEAD_INTERVAL_MS = 20_000L
        /** 取得に失敗したときに次を試すまでの間。失敗が続くときに本家へ取りに行き続けない。 */
        const val ERROR_INTERVAL_MS = 20_000L
    }
}
