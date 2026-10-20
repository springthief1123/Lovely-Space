package io.github.springthief1123.lovelyspace.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * ページを更新単位で置き換え、ページ移動した同じIDは新しい観測を優先する。
 *
 * 取り直したページから消えた部屋は、閉じたとは限らない。前のページの部屋が閉じれば前のページへ、新しい部屋が開けば
 * 後ろのページへずれるので、読む順番によっては一覧のどこにも無い時間ができる。そこで消えた部屋は [carried] に残し、
 * 別のページで見つかるか、消えた後に次のページも読んだうえで同じページをもう一度読んでも無いと分かるまで一覧に出し続ける
 * （ページが 1 つだけの一覧ではずれないので残さない）。
 *
 * 一覧は部屋を立てた順（部屋の ID の大きい順）に並ぶ。読んだページの最初と最後の部屋の ID の間にあるはずの部屋は、
 * ずれても前後のページへは行かないので、そのページに無ければ閉じた部屋として、残していた部屋（[carried]）からも、
 * それより前に読んだ別のページからもすぐ外す。同じ秒に立った部屋は ID の順が前後することがあるので、
 * ページの端から [ID_MARGIN] 以内の部屋は外さず、上の残し方に任せる。ID を使い回した部屋で順が崩れたページでは範囲を使わない。
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
        val seen = page.rooms.mapTo(HashSet(), ::roomIdentity)
        val closed = closedRange(page.rooms)
        // このページの範囲にあるのに載っていない部屋は閉じた。このページより前に読んだページからも外す。
        fun gone(room: Room) = closed != null && room.id in closed && roomIdentity(room) !in seen
        val nextPages = (pages.mapValues { (number, rooms) ->
            if ((revisions[number] ?: Long.MIN_VALUE) < nextRevision) rooms.filterNot(::gone) else rooms
        } + (page.page to page.rooms)).filterKeys { it <= limit }
        val present = nextPages.values.flatten().map(::roomIdentity).toSet()
        val vanished = if (limit > 1) pages[page.page].orEmpty().filter { roomIdentity(it) !in present } else emptyList()
        return copy(
            pages = nextPages,
            revisions = (revisions + (page.page to nextRevision)).filterKeys { it <= limit },
            lastPage = limit,
            revision = maxOf(revision, nextRevision),
            carried = carried.filter { (id, c) ->
                // 後ろへずれた部屋は次のページにあるので、消えた後に次のページを読むまでは閉じたと決めない。
                val nextRead = c.page >= limit || (revisions[c.page + 1] ?: Long.MIN_VALUE) > c.revision
                id !in present && c.page <= limit && !(c.page == page.page && nextRead) && !(c.revision < nextRevision && gone(c.room))
            } + vanished.filterNot(::gone).associate { roomIdentity(it) to CarriedRoom(page.page, it, nextRevision) },
        )
    }

    /**
     * [rooms]（1 ページ分）に載っているはずの部屋の ID の範囲。ページの端の [ID_MARGIN] は含めない。部屋が少なく範囲が無ければ null。
     * 部屋の ID は閉じた後に別の部屋で使い回されることがある（docs/site-protocol.md）。使い回した小さな ID の部屋が並ぶと
     * 範囲が何ページ分にも広がるので、ページの中で ID の大きい順から [ID_MARGIN] を超えて外れる部屋があれば範囲を使わない。
     * ページの最後に並んだ部屋は順の崩れが見えないので、いちばん大きい ID と小さい ID の部屋は範囲の端に使わない。
     */
    private fun closedRange(rooms: List<Room>): LongRange? {
        if (rooms.size < 3) return null
        if (rooms.zipWithNext().any { (above, below) -> below.id > above.id + ID_MARGIN }) return null
        val ids = rooms.map { it.id }.sorted()
        val low = ids[1] + ID_MARGIN
        val high = ids[ids.size - 2] - ID_MARGIN
        return if (low <= high) low..high else null
    }

    /**
     * [old] の部屋が閉じて立て直された（[isRebuildOf]）ときの新しい部屋。分からなければ null。
     *
     * [old] がこの一覧に無く（消えたばかりで [carried] に残っている間も含めて、まだあるものとみなす）、読んだページの ID の範囲（[closedRange]）に
     * [old] の ID が入っていて閉じたと分かるときだけ探す。読んでいない後ろのページにあるだけかもしれない部屋は追わない。
     * 候補がちょうど 1 つのときだけ返す。2 つ以上なら、別の人の部屋を選ばないよう追わない。[claimed]（ほかの登録が追っている部屋）は候補にしない。
     */
    fun rebuildOf(old: Room, claimed: Set<String> = emptySet()): Room? {
        if (!isClosed(old)) return null
        return rooms.filter { roomIdentity(it) !in claimed && isRebuildOf(old, it) }.distinctBy(::roomIdentity).singleOrNull()
    }

    /**
     * [room] が閉じたと分かるか。この一覧に無く（消えたばかりで [carried] に残っている間は、まだあるものとみなす）、
     * 読んだページの ID の範囲（[closedRange]）に [room] の ID が入っているとき。
     * 最後のページまで読んでいて、[room] の ID が最後のページのいちばん古い部屋より [ID_MARGIN] 以上小さいときも閉じている
     * （日をまたいだ前の部屋など。部屋は最長 8 時間で閉じ、一覧は立てた順に並ぶ）。
     */
    fun isClosed(room: Room): Boolean {
        val key = roomIdentity(room)
        if (rooms.any { roomIdentity(it) == key }) return false
        if (pages.values.any { closedRange(it)?.contains(room.id) == true }) return true
        val oldest = pages[lastPage]?.takeIf { it.size >= 3 }?.minOf { it.id } ?: return false
        return room.id < oldest - ID_MARGIN
    }

    companion object {
        /**
         * 一覧は立てた時刻（秒）の順で、同じ秒に立った部屋同士は ID の順が前後する（2026-10-04 の採取で ID の差は 3 以内）。
         * 本家全体で 1 秒に立つ部屋は数件なので、ページの端からこれだけ離れた ID の部屋だけを、そのページで閉じたと判断する。
         */
        const val ID_MARGIN = 60L
    }

    /**
     * 1 ページ目から数えて、直近 [age] 以内に立った待機中の部屋が載っている最後のページ。一覧は新着順なので、そこまでが新しい部屋。
     * 満室の経過時間は会話の時間なので数えない。経過時間を読み取れない部屋と、まだ読んでいないページは、新しいかどうか分からないので含める
     * （最初の周は全ページを読む）。
     */
    fun recentPages(age: Duration = RECENT_ROOM_AGE): Int =
        (lastPage.coerceAtLeast(1) downTo 1).firstOrNull { p ->
            pages[p]?.any { it.status != RoomStatus.FULL && it.elapsed.let { e -> e == null || e <= age } } ?: true
        } ?: 1

    /** 部屋（[roomIdentity]）ごとの、載っているページ。複数のページにあれば [rooms] と同じく新しく読んだページ、消えた部屋は消えたページ。 */
    val roomPages: Map<String, Int> get() {
        val result = mutableMapOf<String, Int>()
        pages.keys.sortedByDescending { revisions[it] }.forEach { page ->
            pages[page].orEmpty().forEach { result.putIfAbsent(roomIdentity(it), page) }
        }
        carried.forEach { (id, c) -> result.putIfAbsent(id, c.page) }
        return result
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

/** 取り直したページから消えた部屋。[page] は消えたページ、[room] は最後に見た内容、[revision] は消えたと分かった観測。 */
data class CarriedRoom(val page: Int, val room: Room, val revision: Long = 0)

/** 部屋は最長 8 時間で閉じるので、直近 1 時間に立った部屋（一覧の前のページ）を優先して読み直す。 */
val RECENT_ROOM_AGE: Duration = 1.hours

/**
 * 一覧の次に読むページ。直近 1 時間の部屋が載るページ（[RoomPageWindow.recentPages]）を 1 ページ目から順に読み、
 * 読み終えるごとに、それより後ろのページを 1 つだけ読んでから 1 ページ目へ戻る。後ろのページは周ごとに 1 つずつ順に回る（[cold] が最後に回ったページ）。
 * 新しい部屋が開いて新しい範囲の最後のページから次のページへ押し出された部屋がある周は、次のページを先に読む（[boundary]。続けては行わず、順に回る分を止めない）。
 * 直近の部屋が後ろのページまで並ぶ一覧や、まだ読んでいないページがある一覧では、全ページを順に読むのと同じになる。
 * 最初の周（[swept] になるまで）は、ほかの条件と共有する一覧が読み済みでも、この位置で全ページを順に読む。
 */
data class PageCursor(val next: Int = 1, val cold: Int = 0, val boundary: Boolean = false, val swept: Boolean = false) {
    /** 次に読むページ。ページ数が減って無くなったら 1 ページ目。 */
    fun page(lastPage: Int): Int = if (next > lastPage.coerceAtLeast(1)) 1 else next

    /** [page] を読み、[window] に反映した後の位置。 */
    fun after(page: Int, window: RoomPageWindow): PageCursor {
        val last = window.lastPage.coerceAtLeast(1)
        val recent = if (swept) window.recentPages().coerceAtMost(last) else last
        return when {
            !swept && page >= last -> copy(next = 1, cold = page, swept = true)
            // 後ろのページを 1 つ読んだので、新しい周へ。押し出された部屋を探しに読んだページでは、順に回る位置を進めない。
            page > recent -> copy(next = 1, cold = if (boundary) cold else page)
            page < recent -> copy(next = page + 1)
            recent >= last -> copy(next = 1)
            !boundary && window.carried.values.any { it.page == recent } -> copy(next = recent + 1, boundary = true)
            else -> copy(next = (cold + 1).takeIf { it in recent + 1..last } ?: (recent + 1), boundary = false)
        }
    }
}

/**
 * 一覧を新着順の前のページから読み直す（[PageCursor]）。最初の周は 1 ページ目から最後のページまで順に読む。
 * その後は直近 1 時間の部屋が載るページを周ごとに読み直し、新しい部屋と、新しい部屋の募集文・公開/非公開・満室の変化を早く拾う。
 * 古い部屋の並ぶ後ろのページは周ごとに 1 ページずつ回るので、遅れても全ページを読み直す。
 *
 * ページ同士の間は [ShaloveClient] の最小間隔。新しい周の 1 ページ目は、前の周を始めてから [lapInterval]（設定の間隔）が経つまで待つ。
 * 1 ページだけの一覧なら、1 ページ目を設定の間隔ごとに取り直すことになる。失敗したページは進めず、同じページから再開する。
 */
class RoomPageSchedule(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val lapInterval: () -> Long = { RefreshPacing.DEFAULT_SEARCH_HEAD_MS },
) {
    private var lapAt: Long? = null
    private var cursor = PageCursor()
    /** 次に読むページ。 */
    fun next(window: RoomPageWindow): Int = cursor.page(window.lastPage)
    /** 次のページを読むまでに待つ時間（ミリ秒）。新しい周の 1 ページ目だけ、前の周の始まりから [lapInterval] まで待つ。 */
    fun waitMs(window: RoomPageWindow): Long {
        if (next(window) != 1) return 0
        val at = lapAt ?: return 0
        return (lapInterval() - (clock() - at)).coerceAtLeast(0)
    }
    /** [page] を読み終えた。[window] は読んだページを反映した一覧。 */
    fun completed(page: Int, window: RoomPageWindow) {
        if (page == 1) lapAt = clock()
        cursor = cursor.after(page, window)
    }
    companion object {
        /** 取得に失敗したときに次を試すまでの間。失敗が続くときに本家へ取りに行き続けない。 */
        const val ERROR_INTERVAL_MS = 20_000L
    }
}
