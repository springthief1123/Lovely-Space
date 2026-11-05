package io.github.springthief1123.lovelyspace.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class RoomPageWindowTest {
    private fun room(id: Long, elapsed: Duration? = null) = Room(id, "zenkoku", RoomStatus.WAITING, RoomAction.ENTER, elapsed, "合成$id", Gender.MALE, 25, null, "合成募集文")
    private fun page(number: Int, rooms: List<Room>, last: Int = 3) = RoomListPage("zenkoku", rooms, null, null, number, last, emptyMap(), null)

    /** 一覧は立てた順（ID の大きい順）。[n] が小さいほど新しい部屋。ID は ID_MARGIN より離す。 */
    private fun r(n: Int) = room(900_000_000L - n * 100L)
    private fun numbers(window: RoomPageWindow) = window.rooms.map { ((900_000_000L - it.id) / 100).toInt() }

    @Test fun refreshReplacesOnePagePreservesOthersAndUsesLatestStatusAcrossPageMoves() {
        val pushed = r(2)
        val full = pushed.copy(status = RoomStatus.FULL, action = RoomAction.NONE, name = null)
        // 新しい部屋 0 が開き、2 が 2 ページ目へ押し出された。2 ページ目を読むまでは消えた部屋として、立てた順の位置に残す。
        val window = RoomPageWindow().observe(page(1, listOf(r(1), pushed)))
            .observe(page(2, listOf(r(3), r(4))))
            .observe(page(1, listOf(r(0), r(1))))
        assertEquals(listOf(0, 1, 2, 3, 4), numbers(window))
        // 2 ページ目で見つかれば、そのページの内容（満室になった）で出す。
        val found = window.observe(page(2, listOf(full, r(3))))
        assertEquals(listOf(0, 1, 2, 3, 4), numbers(found))
        assertEquals(RoomAction.NONE, found.rooms[2].action)
        // 2 ページ目から消えた 4 は、3 ページ目へ押し出されたかもしれないので残す。
        assertEquals(setOf(roomIdentity(r(4))), found.carried.keys)
    }
    @Test fun roomMissingFromTheIdRangeOfAReadPageIsDroppedAtOnce() {
        // 一覧は ID の大きい順。部屋の ID は本物に近い大きさにし、ページの端から ID_MARGIN より内側に置く。
        val base = 900_000_000L
        fun r(offset: Long) = room(base + offset)
        val window = RoomPageWindow().observe(page(1, listOf(r(700), r(500), r(300), r(100))))
            .observe(page(2, listOf(r(-100), r(-300))))
        // 同じ人が部屋を立て直した: 500 が閉じて、新しい部屋が先頭に出た。500 は 1 ページ目の範囲の中なので、次のページを待たずに外す。
        val rebuilt = window.observe(page(1, listOf(r(900), r(700), r(300), r(100))))
        assertEquals(listOf(900L, 700L, 300L, 100L, -100L, -300L), rebuilt.rooms.map { it.id - base })
        assertTrue(rebuilt.carried.isEmpty())
        // ページの端の部屋（同じ秒に立った部屋と順が前後しうる）は、すぐには外さず残す。
        val edge = window.observe(page(1, listOf(r(900), r(700), r(500), r(100))))
        assertTrue(edge.rooms.any { it.id == base + 300 })
        // 閉じた部屋の ID を使い回した部屋が並んでも、その部屋は並んだ位置で数える（sortKeys）ので、500 は 1 ページ目の範囲の中で閉じた。
        val reused = window.observe(page(1, listOf(r(900), r(-5_000), r(700), r(300), r(100))))
        assertFalse(reused.rooms.any { it.id == base + 500 })
        assertTrue(reused.rooms.any { it.id == base - 100 })
        // ページの最後に並んだ使い回しの ID は、ページの端として範囲に入れない。
        val reusedLast = window.observe(page(1, listOf(r(900), r(700), r(500), r(300), r(-5_000))))
        assertTrue(reusedLast.rooms.any { it.id == base + 100 })
        assertTrue(reusedLast.rooms.any { it.id == base - 100 })
        // 前に読んだ別のページに残っている部屋も、新しく読んだページの範囲の中で見つからなければ外す。
        val older = page(2, listOf(r(600), r(500), r(400), r(300), r(200)))
        val head = page(1, listOf(r(1100), r(1000), r(600), r(300), r(200), r(100)))
        val stale = RoomPageWindow().observe(page(1, listOf(r(1000), r(900), r(800), r(700)))).observe(older).observe(head)
        assertFalse(stale.rooms.any { it.id == base + 400 })
        // 後から読んだページのほうが新しいので、それより前の観測で取り直したページの部屋は消さない。
        val newer = RoomPageWindow().observe(older, 10).observe(head, 5)
        assertTrue(newer.rooms.any { it.id == base + 400 })
    }
    @Test fun rebuiltRoomHidesTheOldCopyOnAPageNotYetReread() {
        fun host(id: Long) = room(id).copy(name = "合成の部屋主", message = "合成の待機メッセージです")
        val old = host(500)
        val before = RoomPageWindow().observe(page(1, listOf(room(1000), room(900), room(800)), last = 2))
            .observe(page(2, listOf(old, room(400)), last = 2))
        // 同じ人が立て直した: 新しい部屋は 1 ページ目に出る。古い部屋の載る 2 ページ目はまだ読み直していないが、本家ではもう無いので出さない。
        val rebuilt = before.observe(page(1, listOf(host(1100), room(1000), room(900), room(800)), last = 2))
        assertEquals(listOf(1100L, 1000L, 900L, 800L, 400L), rebuilt.rooms.map { it.id })
        assertEquals(setOf(roomIdentity(old)), rebuilt.superseded)
        // 画面の一覧から外すだけで、閉じたとはまだ決めない（ピンは古い部屋のまま、ページを読み直して確かめる）。
        assertTrue(rebuilt.listedRooms.any { it.id == 500L })
        assertFalse(rebuilt.isClosed(old))
        assertEquals(PinState.OPEN, rebuilt.pinState(old).first)
        // 2 ページ目を読み直して古い部屋がまだあれば、同じ人が 2 部屋立てているので本家と同じく両方出す。
        val both = rebuilt.observe(page(2, listOf(old, room(400)), last = 2))
        assertEquals(listOf(1100L, 1000L, 900L, 800L, 500L, 400L), both.rooms.map { it.id })
        // 読み直して無ければ、出さないまま。前のページも読んで、その間に無ければ閉じた部屋として外れる。
        val vanished = rebuilt.observe(page(2, listOf(room(400)), last = 2))
        assertFalse(vanished.rooms.any { it.id == 500L })
        val closed = vanished.observe(page(1, listOf(host(1100), room(1000), room(900), room(800)), last = 2))
        assertFalse(closed.listedRooms.any { it.id == 500L })
        assertTrue(closed.superseded.isEmpty())
        // 同じページを同じときに読んで両方載っていれば、本家にも両方あるので外さない。
        val sameRead = RoomPageWindow().observe(page(1, listOf(host(1100), room(1000), old), last = 1))
        assertEquals(3, sameRead.rooms.size)
        // 名前が違えば立て直しではない。
        val other = before.observe(page(1, listOf(host(1100).copy(name = "別の合成"), room(1000), room(900), room(800)), last = 2))
        assertTrue(other.rooms.any { it.id == 500L })
        // 取り直したページから消えて残している古い部屋も、立て直しの部屋が載っていれば出さない。
        val edge = RoomPageWindow().observe(page(1, listOf(room(1000), old), last = 2))
            .observe(page(2, listOf(room(400)), last = 2))
            .observe(page(1, listOf(host(1100), room(1000)), last = 2))
        assertTrue(edge.carried.containsKey(roomIdentity(old)))
        assertEquals(listOf(1100L, 1000L, 400L), edge.rooms.map { it.id })
        // 両方が同じときに載っていた後、古い部屋だけが新着に押されてページから消えても、立て直しではないので出し続ける。
        val pushed = RoomPageWindow().observe(page(1, listOf(host(1100), room(1000), old), last = 2))
            .observe(page(2, listOf(room(400)), last = 2))
            .observe(page(1, listOf(room(1200), host(1100), room(1000)), last = 2))
        assertTrue(pushed.carried.containsKey(roomIdentity(old)))
        assertTrue(pushed.rooms.any { it.id == 500L })
    }
    @Test fun eachRoomKnowsThePageItWasLastSeenOn() {
        // 1 は 1 ページ目から 2 ページ目へ移った。2 は 1 ページ目から消えたので、消えたページのまま残る。
        val window = RoomPageWindow().observe(page(1, listOf(room(1), room(2))))
            .observe(page(2, listOf(room(3), room(1))))
            .observe(page(1, listOf(room(4))))
        val pages = window.roomPages
        assertEquals(mapOf(1L to 2, 2L to 1, 3L to 2, 4L to 1), window.rooms.associate { it.id to pages.getValue(roomIdentity(it)) })
    }
    @Test fun roomsThatMoveToAnotherPageBetweenReadsStayInTheList() {
        // 前のページの部屋が閉じて 3 が 1 ページ目へ繰り上がった。1 ページ目を読んだ後に 2 ページ目を読むと、3 はどちらにも無い。
        val window = RoomPageWindow().observe(page(1, listOf(r(1), r(2))))
            .observe(page(2, listOf(r(3), r(4))))
            .observe(page(2, listOf(r(4), r(5))))
        assertEquals(listOf(1, 2, 3, 4, 5), numbers(window))
        // 次の周で 1 ページ目に見つかれば、そこに並ぶ。
        val next = window.observe(page(1, listOf(r(2), r(3))))
        assertEquals(listOf(2, 3, 4, 5), numbers(next).filter { it != 1 })
        assertTrue(next.carried.keys.none { it.endsWith("/${r(3).id}") })
        // 1 ページだけの一覧では、消えた部屋は閉じた部屋なので残さない。
        val single = RoomPageWindow().observe(page(1, listOf(r(1), r(2)), last = 1)).observe(page(1, listOf(r(1)), last = 1))
        assertEquals(listOf(1), numbers(single))
    }
    @Test fun roomPushedSeveralPagesWhileOlderPagesWereNotReadStaysInTheList() {
        // 2 が 1 ページ目から押し出された後、2 ページ目を読む前に 3 ページ目まで押し出された（後ろのページは周に 1 つしか読まない）。
        // 2 ページ目に無かったことだけでは閉じたと決めず、立てた順の位置に出し続ける。
        val window = RoomPageWindow().observe(page(1, listOf(r(1), r(2))))
            .observe(page(2, listOf(r(3), r(4))))
            .observe(page(3, listOf(r(5), r(6))))
            // 新しい部屋 -1 が開いた。
            .observe(page(1, listOf(r(-1), r(1))))
            // さらに -2・-3 が開き、2 ページ目を読んだときには 2 は 3 ページ目にある。
            .observe(page(2, listOf(r(-1), r(1))))
            .observe(page(1, listOf(r(-3), r(-2))))
        assertTrue(numbers(window).contains(2))
        assertEquals(numbers(window).sorted(), numbers(window))
        // 3 ページ目で見つかる。
        val found = window.observe(page(3, listOf(r(2), r(3))))
        assertTrue(found.carried.keys.none { it.endsWith("/${r(2).id}") })
        assertEquals(numbers(found).sorted(), numbers(found))
    }
    @Test fun carriedRoomIsDroppedOnceThePagesReadAfterItVanishedSpanItsPlace() {
        // 2 ページ目の最後の 7 が消えた。ページの端なので、3 ページ目へ押し出されたかもしれず、そのページだけでは閉じたと決めない。
        fun p(number: Int, vararg n: Int) = page(number, n.map(::r), last = 4)
        val window = RoomPageWindow().observe(p(1, 1, 2, 3)).observe(p(2, 4, 5, 6, 7)).observe(p(3, 8, 9, 10)).observe(p(4, 11, 12))
            .observe(p(2, 4, 5, 6))
        assertTrue(numbers(window).contains(7))
        // 続けて 3 ページ目を読み、7 の位置が 2・3 ページ目の間に入るのにどちらにも無いので閉じた。
        assertFalse(numbers(window.observe(p(3, 8, 9, 10))).contains(7))
        // 3 ページ目へ押し出されていれば残る。
        assertTrue(numbers(window.observe(p(3, 7, 8, 9))).contains(7))
        // 最後のページより古い部屋は、最後のページを読めば閉じた。
        val tail = RoomPageWindow().observe(page(1, listOf(r(1), r(2)), last = 2))
            .observe(page(2, listOf(r(3), r(4)), last = 2))
            .observe(page(2, listOf(r(3)), last = 2))
        assertFalse(numbers(tail).contains(4))
    }
    @Test fun roomWithAReusedIdIsOrderedWhereItIsListed() {
        // 閉じた部屋の ID を使い回した部屋（小さな ID）は、ID ではなく本家に並んだ位置で数える。
        val reused = room(100L)
        assertEquals(listOf(r(1).id, r(1).id, r(2).id), sortKeys(listOf(r(1), reused, r(2))))
        assertEquals(listOf(r(1).id, r(1).id, r(1).id), sortKeys(listOf(reused, room(200L), r(1))))
        // ページの最後の使い回しの部屋は、次のページの最初の部屋と比べて見分ける。
        assertEquals(listOf(r(1).id, r(2).id, r(2).id), sortKeys(listOf(r(1), r(2), reused), after = r(3).id))
        // ページの最後にあった使い回しの部屋が、読んでいない次のページへ押し出されても、古い部屋として閉じたことにしない。
        val pushed = RoomPageWindow().observe(page(1, listOf(r(1), r(2), reused)))
            .observe(page(2, listOf(r(3), r(4), r(5))))
            .observe(page(3, listOf(r(6), r(7), r(8))))
            .observe(page(1, listOf(r(-1), r(1), r(2))))
            .observe(page(3, listOf(r(6), r(7), r(8))))
        assertTrue(pushed.rooms.any { it.id == reused.id })
        // 消えて残している部屋も、使い回しの部屋の後ろへは行かない。
        val window = RoomPageWindow().observe(page(1, listOf(r(1), reused, r(2), r(3))))
            .observe(page(2, listOf(r(4), r(5))))
            .observe(page(1, listOf(r(0), r(1), reused, r(2))))
        assertEquals(listOf(r(0), r(1), reused, r(2), r(3), r(4), r(5)).map { it.id }, window.rooms.map { it.id })
    }
    @Test fun duplicateOnOlderPageCannotOverrideNewerProfileAndReducedPageCountDropsOldPages() {
        val original = room(1)
        val newer = original.copy(name = "変更した合成")
        val window = RoomPageWindow().observe(page(2, listOf(original, room(2))))
            .observe(page(1, listOf(newer)))
        assertEquals("変更した合成", window.rooms.first().name)
        assertEquals(2, window.rooms.size)
        val shrunk = window.observe(page(1, listOf(newer), last = 1))
        assertEquals(listOf(newer), shrunk.rooms)
        assertEquals(setOf(1), shrunk.pages.keys)
    }
    @Test fun olderCachedPageCannotOverrideTheLatestActionOrReExpandThePageCount() {
        val old = room(1)
        val latest = old.copy(status = RoomStatus.FULL, action = RoomAction.PEEK, name = null)
        val window = RoomPageWindow().observe(page(1, listOf(latest), last = 2), observationRevision = 10)
            .observe(page(2, listOf(old, room(2)), last = 3), observationRevision = 2)
        assertEquals(latest, window.rooms.first())
        assertEquals(2, window.lastPage)
        val shrunk = window.observe(page(1, listOf(latest), last = 1), observationRevision = 11)
            .observe(page(2, listOf(old), last = 3), observationRevision = 2)
        assertEquals(listOf(latest), shrunk.rooms)
        assertEquals(1, shrunk.lastPage)
        assertEquals(setOf(1), shrunk.pages.keys)
    }
    @Test fun delayedOlderObservationOfTheSamePageDoesNotReplaceFreshContents() {
        val latest = room(1).copy(message = "新しい合成")
        val window = RoomPageWindow().observe(page(1, listOf(latest)), observationRevision = 10)
        assertSame(window, window.observe(page(1, listOf(room(1))), observationRevision = 2))
        assertEquals(listOf(latest), window.rooms)
    }
    private fun window(vararg pages: List<Room>) =
        pages.foldIndexed(RoomPageWindow()) { i, w, rooms -> w.observe(page(i + 1, rooms, last = pages.size)) }

    @Test fun recentPagesEndAtTheLastPageWithARoomOpenedWithinAnHour() {
        val full = room(9, 5.minutes).copy(status = RoomStatus.FULL)
        // 満室の経過時間は会話の時間なので、直近に立った部屋としては数えない。
        val w = window(listOf(room(1, 3.minutes)), listOf(room(2, 59.minutes), room(3, 61.minutes)), listOf(full, room(4, 120.minutes)), listOf(room(5, 300.minutes)))
        assertEquals(2, w.recentPages())
        // 直近の部屋が無くても 1 ページ目は読む。まだ読んでいないページは含める。
        assertEquals(1, window(listOf(room(1, 90.minutes)), listOf(room(2, 120.minutes))).recentPages())
        assertEquals(3, RoomPageWindow().observe(page(1, listOf(room(1, 90.minutes)), last = 3)).recentPages())
        assertEquals(2, window(listOf(room(1, 90.minutes)), listOf(room(2)), listOf(room(3, 120.minutes))).recentPages())
    }
    @Test fun scheduleRereadsRecentPagesEveryLapAndVisitsOneOlderPagePerLap() {
        var now = 0L
        val schedule = RoomPageSchedule(clock = { now }) { 4_000 }
        val rooms = listOf(listOf(room(1, 1.minutes)), listOf(room(2, 50.minutes)), listOf(room(3, 70.minutes)), listOf(room(4, 90.minutes)), listOf(room(5, 120.minutes)))
        var w = RoomPageWindow()
        val read = mutableListOf<Int>()
        repeat(14) {
            now += 3_000
            val p = schedule.next(w)
            w = w.observe(page(p, rooms[p - 1], last = 5))
            schedule.completed(p, w)
            read += p
        }
        // 最初の周は全ページ。その後は直近 1 時間の 1・2 ページ目を毎周読み、後ろの 3〜5 ページ目を 1 つずつ回る。
        assertEquals(listOf(1, 2, 3, 4, 5, 1, 2, 3, 1, 2, 4, 1, 2, 5), read)
    }
    @Test fun roomPushedPastTheRecentPagesIsLookedForOnTheNextPageBeforeTheRotation() {
        val rooms = listOf(listOf(room(1, 1.minutes), room(2, 2.minutes)), listOf(room(3, 70.minutes)), listOf(room(4, 90.minutes)), listOf(room(5, 120.minutes)))
        var w = window(*rooms.toTypedArray())
        var cursor = PageCursor(swept = true).after(4, w)
        assertEquals(1, cursor.next)
        // 新しい部屋 9 が開き、2 が 2 ページ目へ押し出された。
        w = w.observe(page(1, listOf(room(9, 0.minutes), room(1, 1.minutes)), last = 4))
        cursor = cursor.after(1, w)
        assertEquals(2, cursor.next)
        w = w.observe(page(2, listOf(room(2, 2.minutes), room(3, 70.minutes)), last = 4))
        assertTrue(w.rooms.any { it.id == 2L })
        // 2 ページ目にも直近の部屋が載ったので、直近の範囲が広がった。
        assertEquals(2, w.recentPages())
        cursor = cursor.after(2, w)
        assertEquals(3, cursor.next)
        // 1 ページ目しか直近の範囲が無いときは、押し出された部屋を探す次のページが、順に回るページより先。
        val narrow = window(listOf(room(1, 1.minutes), room(2, 2.minutes)), listOf(room(3, 70.minutes)), listOf(room(4, 90.minutes)))
            .observe(page(1, listOf(room(9, 0.minutes), room(1, 1.minutes)), last = 3))
        val boundary = PageCursor(cold = 2, swept = true).after(1, narrow)
        assertEquals(PageCursor(next = 2, cold = 2, boundary = true, swept = true), boundary)
        // 続く周では順に回る位置（3 ページ目）を進め、押し出しの確認を続けては行わない。
        val back = boundary.after(2, narrow)
        assertEquals(1, back.next); assertEquals(2, back.cold)
        assertEquals(3, back.after(1, narrow).next)
    }
    @Test fun newCursorReadsEveryPageOnceEvenWhenTheSharedListIsAlreadyRead() {
        // ほかの条件が全ページを読み済みの一覧でも、新しく有効にした条件の最初の周は全ページを読む。
        val w = window(listOf(room(1, 1.minutes)), listOf(room(2, 90.minutes)), listOf(room(3, 120.minutes)))
        val read = mutableListOf<Int>()
        var cursor = PageCursor()
        repeat(6) { val p = cursor.page(w.lastPage); read += p; cursor = cursor.after(p, w) }
        assertEquals(listOf(1, 2, 3, 1, 2, 1), read)
    }
    @Test fun scheduleStartsTheNextLapAfterTheSettingInterval() {
        var now = 0L
        var lap = 4_000L
        val schedule = RoomPageSchedule(clock = { now }) { lap }
        val w = window(listOf(room(1, 1.minutes)), listOf(room(2, 1.minutes)), listOf(room(3, 1.minutes)))
        assertEquals(1, schedule.next(w)); assertEquals(0, schedule.waitMs(w)); schedule.completed(1, w)
        now = 3_000
        assertEquals(2, schedule.next(w)); assertEquals(0, schedule.waitMs(w)); schedule.completed(2, w)
        now = 6_000
        assertEquals(3, schedule.next(w))
        assertEquals(3, schedule.next(w)) // 失敗したページは完了を通知せず同じ位置で再試行。
        schedule.completed(3, w)
        // 全ページを読んだら 1 ページ目に戻る。前の周の始まりから 4 秒経っているので待たない。
        assertEquals(1, schedule.next(w)); assertEquals(0, schedule.waitMs(w))
        // 設定の間隔が長ければ、新しい周の始まりだけを待つ。
        lap = 10_000
        assertEquals(4_000, schedule.waitMs(w))
        // ページ数が減って次のページが無くなったら 1 ページ目へ。
        schedule.completed(1, w)
        assertEquals(1, schedule.next(RoomPageWindow()))
    }
    @Test fun singlePageListIsReadAgainAtTheSettingInterval() {
        var now = 0L
        val schedule = RoomPageSchedule(clock = { now }) { 4_000 }
        val w = window(listOf(room(1)))
        assertEquals(1, schedule.next(w)); schedule.completed(1, w)
        now = 3_000
        assertEquals(1, schedule.next(w)); assertEquals(1_000, schedule.waitMs(w))
        now = 4_000
        assertEquals(0, schedule.waitMs(w))
    }
    @Test fun rebuiltRoomIsFoundOnlyOnceTheOldRoomIsKnownToBeClosed() {
        val base = 900_000_000L
        fun r(offset: Long, name: String = "合成$offset", message: String = "合成募集文$offset") =
            Room(base + offset, "zenkoku", RoomStatus.WAITING, RoomAction.ENTER, null, name, Gender.FEMALE, 25, null, message)
        val old = r(500)
        val window = RoomPageWindow().observe(page(1, listOf(r(700), old, r(300), r(100))))
            .observe(page(2, listOf(r(-100), r(-300))))
        // まだ一覧にある部屋は立て直していない。
        val rebuilt = r(900, name = old.name!!, message = old.message)
        assertNull(window.rebuildOf(old))
        // 500 が閉じ、同じ名前・募集文の 900 が先頭に出た。
        val after = window.observe(page(1, listOf(rebuilt, r(700), r(300), r(100))))
        assertEquals(rebuilt, after.rebuildOf(old))
        // ほかの登録が追っている部屋は選ばない。
        assertNull(after.rebuildOf(old, claimed = setOf(roomIdentity(rebuilt))))
        // 同じ名前・募集文の候補が 2 つあれば、どちらとも決めない。
        assertNull(window.observe(page(1, listOf(rebuilt, r(800, name = old.name!!, message = old.message), r(700), r(300), r(100)))).rebuildOf(old))
        // 読んだページの範囲の外（まだ読んでいない後ろのページ）にあるかもしれない部屋は追わない。
        val unread = r(-2000)
        assertNull(after.rebuildOf(unread))
    }
}
