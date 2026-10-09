package io.github.springthief1123.lovelyspace.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.notify.NotificationKind
import io.github.springthief1123.lovelyspace.notify.NotificationTarget
import io.github.springthief1123.lovelyspace.notify.toOpenedNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WaitlistRepositoryTest {
    // 合成データ。本家の実データは使わない。
    private val full = Room(42, "zenkoku", RoomStatus.FULL, RoomAction.NONE, null, null, Gender.UNKNOWN, null, null, "合成の募集文")
    private val zenkoku = Genres["zenkoku"]!!

    private class Lists : ObservedRoomListSource {
        override val observations = MutableStateFlow<Map<RoomQuery, ObservedRoomPage>>(emptyMap())
        var pages: Map<Int, List<Room>> = emptyMap()
        var lastPage = 1
        var revision = 0L
        val calls = mutableListOf<RoomQuery>()
        var fail = false
        override fun observation(query: RoomQuery) = observations.value[query]
        override suspend fun fetch(query: RoomQuery, force: Boolean): RoomListPage {
            calls += query
            if (fail) throw java.io.IOException("合成の通信失敗")
            val page = RoomListPage(query.genre.key, pages[query.page].orEmpty(), null, null, query.page, lastPage, emptyMap(), null)
            revision++
            observations.value += query to ObservedRoomPage(query, page, revision * 1000, revision)
            return page
        }
    }

    private class Memory : WaitlistPersistence {
        var saved: List<WaitlistEntry> = emptyList()
        override fun load() = saved
        override fun save(entries: List<WaitlistEntry>) { saved = entries }
    }

    @Test fun aFullRoomThatStartsWaitingIsReportedOnceAndOpensItsEntryScreen() = runTest {
        val lists = Lists().apply { pages = mapOf(1 to listOf(full)) }
        val opened = mutableListOf<WaitlistEntry>()
        val waitlist = WaitlistRepository(Memory(), lists, { opened += it }, backgroundScope) { 0L }
        waitlist.register(full, RoomQuery(zenkoku))
        waitlist.check(3)
        assertTrue(opened.isEmpty())
        val open = full.copy(status = RoomStatus.WAITING, action = RoomAction.ENTER, name = "合成の名前", gender = Gender.FEMALE)
        lists.pages = mapOf(1 to listOf(open))
        waitlist.check(3)
        assertEquals(listOf(WaitlistStatus.OPENED), opened.map { it.status })
        assertFalse(waitlist.hasActive())
        // 空いた後は取得しない。
        waitlist.check(3)
        assertEquals(2, lists.calls.size)
        val n = opened.single().toOpenedNotification()!!
        assertEquals(NotificationKind.WAITLIST, n.kind)
        assertEquals(NotificationTarget.Room(zenkoku.host, "zenkoku", 42), n.target)
        assertTrue("同じです" in n.text)
    }

    @Test fun whileTheAppIsOpenWaitingRoomsAreCheckedAtTheChosenIntervalAndFailuresBackOff() = runTest {
        val lists = Lists().apply { pages = mapOf(1 to listOf(full)) }
        val waitlist = WaitlistRepository(Memory(), lists, {}, backgroundScope) { 0L }
        waitlist.register(full, RoomQuery(zenkoku))
        backgroundScope.launch { waitlist.monitor { 2_000L } }
        runCurrent()
        assertEquals(1, lists.calls.size)
        advanceTimeBy(2_001)
        assertEquals(2, lists.calls.size)
        // 取得に失敗したら、設定の間隔より長く空けてから試す。
        lists.fail = true
        advanceTimeBy(2_000)
        assertEquals(3, lists.calls.size)
        advanceTimeBy(2_001)
        assertEquals(3, lists.calls.size)
        advanceTimeBy(RoomPageSchedule.ERROR_INTERVAL_MS)
        assertEquals(4, lists.calls.size)
    }

    @Test fun aRoomMissingFromItsPageIsNotTreatedAsClosedAndTheNextPageIsChecked() = runTest {
        val lists = Lists().apply { lastPage = 2; pages = mapOf(1 to emptyList(), 2 to listOf(full)) }
        val waitlist = WaitlistRepository(Memory(), lists, {}, backgroundScope) { 0L }
        waitlist.register(full, RoomQuery(zenkoku))
        waitlist.check(1)
        assertEquals(WaitlistStatus.WATCHING, waitlist.entries.value.single().status)
        assertEquals(1, waitlist.entries.value.single().missed)
        waitlist.check(1)
        assertEquals(listOf(1, 2), lists.calls.map { it.page })
        assertEquals(0, waitlist.entries.value.single().missed)
        assertEquals(2, waitlist.entries.value.single().query.page)
        // 取得に失敗しても登録はそのまま。
        lists.fail = true
        waitlist.check(1)
        assertEquals(WaitlistStatus.WATCHING, waitlist.entries.value.single().status)
    }

    @Test fun aReusedIdStopsWaitingWithoutNotifying() = runTest {
        val lists = Lists()
        val opened = mutableListOf<WaitlistEntry>()
        val known = full.copy(age = 30)
        val waitlist = WaitlistRepository(Memory(), lists, { opened += it }, backgroundScope) { 0L }
        waitlist.register(known)
        lists.pages = mapOf(1 to listOf(known.copy(status = RoomStatus.WAITING, action = RoomAction.ENTER, name = "別の合成", age = 41)))
        waitlist.check(3)
        assertEquals(WaitlistStatus.STOPPED, waitlist.entries.value.single().status)
        assertTrue(opened.isEmpty())
    }

    @Test fun limitsExpiryAndOnlyFullRooms() = runTest {
        var clock = 0L
        val lists = Lists()
        val waitlist = WaitlistRepository(Memory(), lists, {}, backgroundScope) { clock }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { waitlist.register(full.copy(status = RoomStatus.WAITING)) }
        }
        (1..WaitlistRepository.MAX_ACTIVE).forEach { waitlist.register(full.copy(id = it.toLong())) }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { waitlist.register(full.copy(id = 99)) } }
        // 同じ部屋の登録し直しは上限に数えない。
        waitlist.register(full.copy(id = 1))
        clock = WaitlistRepository.DEFAULT_HOURS * 60 * 60 * 1000L
        waitlist.check(3)
        assertTrue(lists.calls.isEmpty())
        assertTrue(waitlist.entries.value.all { it.status == WaitlistStatus.EXPIRED })
        waitlist.remove(roomIdentity(full.copy(id = 1)))
        assertEquals(WaitlistRepository.MAX_ACTIVE - 1, waitlist.entries.value.size)
    }

    @Test fun aRemovedEntryCanBeRestoredInPlaceWithItsDeadline() = runTest {
        var clock = 0L
        val memory = Memory()
        val waitlist = WaitlistRepository(memory, Lists(), {}, backgroundScope) { clock }
        (1L..3L).forEach { clock = it; waitlist.register(full.copy(id = it)) }
        val before = waitlist.entries.value
        // 続けて 2 件取り消し、取り消した順に戻しても元の並びに戻る。
        val (newest, middle) = before[0] to before[1]
        waitlist.remove(middle.key)
        waitlist.remove(newest.key)
        clock = 1000
        waitlist.restore(middle)
        waitlist.restore(newest)
        // 元の位置・元の期限で戻り、保存先にも書かれる。
        assertEquals(before, waitlist.entries.value)
        assertEquals(before, memory.saved)
        // 同じ部屋がすでに登録し直されていれば、新しい登録を残す。
        waitlist.remove(middle.key)
        waitlist.register(middle.room)
        val renewed = waitlist.entries.value
        waitlist.restore(middle)
        assertEquals(renewed, waitlist.entries.value)
        // 上限に達していれば、待っている登録は戻さない。
        waitlist.remove(middle.key)
        (10L..12L).forEach { waitlist.register(full.copy(id = it)) }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { waitlist.restore(middle) } }
        // 戻すまでに期限を過ぎていれば、期限切れとして戻す。
        waitlist.remove(newest.key)
        clock = newest.expiresAt
        waitlist.restore(newest)
        val expired = waitlist.entries.value.single { it.key == newest.key }
        assertEquals(WaitlistStatus.EXPIRED, expired.status)
        assertEquals(clock, expired.changedAt)
    }

    @Test fun storeRoundTripsEntries() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("waitlist-test", Context.MODE_PRIVATE)
        val entry = WaitlistEntry(full, RoomQuery(zenkoku, page = 3), 1L, 2L, WaitlistStatus.OPENED, lastSeenAt = 5L, missed = 1,
            openedRoom = full.copy(status = RoomStatus.WAITING, name = "合成の名前"), changedAt = 6L, noticed = true)
        WaitlistStore(prefs).save(listOf(entry))
        assertEquals(listOf(entry), WaitlistStore(prefs).load())
        prefs.edit().putString("entries", "壊れた記録").commit()
        assertTrue(WaitlistStore(prefs).load().isEmpty())
    }

    @Test fun anOpeningSavedBeforeItsNotificationWasShownIsNotifiedOnTheNextCheck() = runTest {
        val lists = Lists().apply { pages = mapOf(1 to listOf(full.copy(status = RoomStatus.WAITING, action = RoomAction.ENTER, name = "合成の名前"))) }
        val memory = Memory()
        val first = WaitlistRepository(memory, lists, { throw java.io.IOException("合成の通知失敗") }, backgroundScope) { 0L }
        first.register(full)
        first.check(3)
        assertFalse(memory.saved.single().noticed)
        // 通知を出し終えるまでは、周期実行を続ける必要がある。
        assertFalse(first.hasActive())
        assertTrue(first.hasPendingWork())
        val opened = mutableListOf<WaitlistEntry>()
        val next = WaitlistRepository(memory, Lists(), { opened += it }, backgroundScope) { 0L }
        next.check(3)
        assertEquals(listOf(WaitlistStatus.OPENED), opened.map { it.status })
        assertTrue(memory.saved.single().noticed)
        next.check(3)
        assertEquals(1, opened.size)
    }

    @Test fun withoutASourcePageWaitingStartsFromThePageWhereTheRoomWasSeen() = runTest {
        val lists = Lists().apply { lastPage = 20; pages = mapOf(14 to listOf(full)) }
        lists.fetch(RoomQuery(zenkoku, page = 14), force = false)
        val waitlist = WaitlistRepository(Memory(), lists, {}, backgroundScope) { 0L }
        waitlist.register(full)
        assertEquals(14, waitlist.entries.value.single().query.page)
        // 呼び出し元のページにその部屋が見えていなければ、見えたページを優先する。
        waitlist.register(full, RoomQuery(zenkoku, page = 2))
        assertEquals(14, waitlist.entries.value.single().query.page)
        // どこにも見えていなければ 1 ページ目から。
        val other = full.copy(id = 7)
        waitlist.register(other)
        assertEquals(1, waitlist.entries.value.first { it.key == roomIdentity(other) }.query.page)
    }

    @Test fun aRoomThatMovedToAnEarlierPageIsFoundByLookingAroundWhereItWasSeen() = runTest {
        val lists = Lists().apply { lastPage = 18; pages = mapOf(14 to listOf(full)) }
        val waitlist = WaitlistRepository(Memory(), lists, {}, backgroundScope) { 0L }
        waitlist.register(full, RoomQuery(zenkoku, page = 14))
        waitlist.check(1)
        assertEquals(14, waitlist.entries.value.single().anchorPage)
        // 前の部屋が閉じて 13 ページ目へずれた。
        lists.pages = mapOf(13 to listOf(full))
        waitlist.check(1)
        waitlist.check(1)
        assertEquals(listOf(14, 14, 13), lists.calls.map { it.page })
        assertEquals(13, waitlist.entries.value.single().anchorPage)
        assertEquals(0, waitlist.entries.value.single().missed)
    }

    @Test fun searchPagesSpreadOutFromTheAnchorAndCoverEveryPage() {
        assertEquals(listOf(5, 4, 6, 3, 7, 2, 1, 5), (0..7).map { searchPage(5, it, 7) })
        assertEquals(listOf(1, 2, 3, 1), (0..3).map { searchPage(1, it, 3) })
        // ページ数が減って記録したページが範囲外になっても、範囲内から探す。
        assertEquals(2, searchPage(9, 0, 2))
        assertEquals(1, searchPage(3, 5, 1))
    }
}
