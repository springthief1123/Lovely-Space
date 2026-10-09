package io.github.springthief1123.lovelyspace.core

/**
 * ページを更新単位で置き換え、ページ移動した同じIDは新しい観測を優先する。
 *
 * 取り直したページから消えた部屋は、閉じたとは限らない。前のページの部屋が閉じれば前のページへ、新しい部屋が開けば
 * 後ろのページへずれるので、読む順番によっては一覧のどこにも無い時間ができる。そこで消えた部屋は [carried] に残し、
 * 別のページで見つかるか、同じページをもう一度読んでも無いと分かるまで一覧に出し続ける（ページが 1 つだけの一覧ではずれないので残さない）。
 */
data class RoomPageWindow(
    val pages: Map<Int, List<Room>> = emptyMap(),
    val revisions: Map<Int, Long> = emptyMap(),
    val lastPage: Int = 1,
    val revision: Long = 0,
    /** 取り直したページから消え、まだどのページでも見つかっていない部屋（部屋の ID → 消えたページと最後に見た内容）。 */
    val carried: Map<String, CarriedRoom> = emptyMap(),
) {
    fun observe(page: RoomListPage, observationRevision: Long? = null): RoomPageWindow {
        val nextRevision = observationRevision ?: revision + 1
        // キャッシュを再利用した呼び出しを新たな観測として数えない。
        if (observationRevision != null && nextRevision <= (revisions[page.page] ?: Long.MIN_VALUE)) return this
        val limit = if (nextRevision >= revision) page.lastPage.coerceAtLeast(1) else lastPage
        val nextPages = (pages + (page.page to page.rooms)).filterKeys { it <= limit }
        val present = nextPages.values.flatten().map(::roomIdentity).toSet()
        val vanished = if (limit > 1) pages[page.page].orEmpty().filter { roomIdentity(it) !in present } else emptyList()
        return copy(
            pages = nextPages,
            revisions = (revisions + (page.page to nextRevision)).filterKeys { it <= limit },
            lastPage = limit,
            revision = maxOf(revision, nextRevision),
            carried = carried.filter { (id, c) -> id !in present && c.page != page.page && c.page <= limit } +
                vanished.associate { roomIdentity(it) to CarriedRoom(page.page, it) },
        )
    }

    val rooms: List<Room> get() {
        val newest = linkedMapOf<String, Room>()
        pages.keys.sortedByDescending { revisions[it] }.forEach { page ->
            pages[page].orEmpty().forEach { newest.putIfAbsent(roomIdentity(it), it) }
        }
        carried.forEach { (id, c) -> newest.putIfAbsent(id, c.room) }
        return (pages.keys + carried.values.map { it.page }).toSortedSet()
            .flatMap { page -> pages[page].orEmpty() + carried.values.filter { it.page == page }.map { it.room } }
            .mapNotNull { newest.remove(roomIdentity(it)) }
    }
}

/** 取り直したページから消えた部屋。[page] は消えたページ、[room] は最後に見た内容。 */
data class CarriedRoom(val page: Int, val room: Room)

/**
 * 一覧を 1 ページ目から最後のページまで順に読み、読み終えたらまた 1 ページ目から読む（ブラウザでページを順にめくるのと同じ）。
 * どのページも 1 周ごとに読み直すので、新しい部屋だけでなく、既存の部屋の募集文・公開/非公開・満室の変化も 1 周のうちに反映される。
 * 上のページから順に読むので、新しい部屋が開いて後ろのページへずれた部屋も同じ周で拾える（前へずれた部屋は [RoomPageWindow.carried]）。
 *
 * ページ同士の間は [ShaloveClient] の最小間隔。新しい周の 1 ページ目は、前の周を始めてから [lapInterval]（設定の間隔）が経つまで待つ。
 * 1 ページだけの一覧なら、1 ページ目を設定の間隔ごとに取り直すことになる。失敗したページは進めず、同じページから再開する。
 */
class RoomPageSchedule(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val lapInterval: () -> Long = { RefreshPacing.DEFAULT_SEARCH_HEAD_MS },
) {
    private var lapAt: Long? = null
    private var nextPage = 1
    /** 次に読むページ。 */
    fun next(lastPage: Int): Int = if (nextPage > lastPage.coerceAtLeast(1)) 1 else nextPage
    /** 次のページを読むまでに待つ時間（ミリ秒）。新しい周の 1 ページ目だけ、前の周の始まりから [lapInterval] まで待つ。 */
    fun waitMs(lastPage: Int): Long {
        if (next(lastPage) != 1) return 0
        val at = lapAt ?: return 0
        return (lapInterval() - (clock() - at)).coerceAtLeast(0)
    }
    fun completed(page: Int, lastPage: Int) {
        if (page == 1) lapAt = clock()
        nextPage = if (page < lastPage) page + 1 else 1
    }
    companion object {
        /** 取得に失敗したときに次を試すまでの間。失敗が続くときに本家へ取りに行き続けない。 */
        const val ERROR_INTERVAL_MS = 20_000L
    }
}
