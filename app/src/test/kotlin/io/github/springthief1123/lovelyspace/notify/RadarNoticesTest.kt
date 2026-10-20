package io.github.springthief1123.lovelyspace.notify

import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.core.RoomStatus
import io.github.springthief1123.lovelyspace.data.RadarEvent
import io.github.springthief1123.lovelyspace.data.RadarEventKind
import io.github.springthief1123.lovelyspace.data.RadarEventOrigin
import io.github.springthief1123.lovelyspace.data.RadarOriginType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// 保存（org.json）を使うので Robolectric で動かす。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class RadarNoticesTest {
    // 合成データ。本家の実データは使わない。
    private val room = Room(42, "zenkoku", RoomStatus.WAITING, RoomAction.ENTER, null, "合成の名前", Gender.FEMALE, 25, null, "合成の待機メッセージ")
    private val plan = RadarEventOrigin(RadarOriginType.PLAN, "plan", "合成の条件")

    private class Source : PendingRadarNotices {
        val events = mutableListOf<RadarEvent>()
        override fun pendingNotices() = events.filter { it.pendingNotice }
        override suspend fun markNoticed(ids: Set<String>) {
            events.replaceAll { if (it.id in ids) it.copy(pendingNotice = false) else it }
        }
        override fun events(ids: Set<String>) = events.filter { it.id in ids }
    }

    private class Memory : RadarNoticePersistence {
        var value: String? = null
        override fun load() = value
        override fun save(value: String) { this.value = value }
    }

    private data class Posted(val notification: AppNotification, val silent: Boolean, val alertAgain: Boolean)

    private fun event(id: String, at: Long, alert: Boolean = true, origin: RadarEventOrigin = plan, rooms: List<Room> = listOf(room)) =
        RadarEvent(at, "合成", id = id, rooms = rooms, page = 1, kind = RadarEventKind.SEARCH_MATCH, origin = origin,
            pendingNotice = true, alert = alert)

    private fun setUp(): Triple<Source, NotificationInbox, MutableList<Posted>> = Triple(Source(), NotificationInbox(), mutableListOf())

    @Test fun matchesOfOneConditionAreMergedAndRingAtMostEveryThirtySeconds() = runTest {
        var now = 10_000L
        val (source, inbox, posted) = setUp()
        val notices = RadarNotices(source, inbox, post = { n, silent, again -> inbox.add(n); posted += Posted(n, silent, again) }, now = { now })
        source.events += event("a", now)
        notices.deliver()
        assertFalse(posted.single().silent)
        assertTrue(source.pendingNotices().isEmpty())

        now += 5_000
        source.events += event("b", now, rooms = listOf(room.copy(id = 43)))
        notices.deliver()
        // 同じお知らせを差し替え、30 秒経つまでは鳴らさない。
        assertEquals(posted[0].notification.id, posted[1].notification.id)
        assertTrue(posted[1].silent)
        assertEquals("全国で2件", posted[1].notification.text)
        assertEquals(1, inbox.entries.value.size)

        now += RadarNotices.ALERT_GAP_MS
        source.events += event("c", now, rooms = listOf(room.copy(id = 44)))
        notices.deliver()
        assertFalse(posted[2].silent)
        assertTrue(posted[2].alertAgain)
    }

    @Test fun readOrOldNotificationsStartANewOne() = runTest {
        var now = 10_000L
        val (source, inbox, posted) = setUp()
        val notices = RadarNotices(source, inbox, post = { n, silent, again -> inbox.add(n); posted += Posted(n, silent, again) }, now = { now })
        source.events += event("a", now)
        notices.deliver()
        inbox.markAllRead()
        source.events += event("b", now + 1)
        notices.deliver()
        assertEquals(2, inbox.entries.value.size)
        now += RadarNotices.BURST_MS
        source.events += event("c", now)
        notices.deliver()
        assertEquals(3, inbox.entries.value.size)
    }

    @Test fun quietConditionsAndChatsOnlyReachTheBell() = runTest {
        val now = 10_000L
        val (source, inbox, posted) = setUp()
        val notices = RadarNotices(source, inbox, post = { n, silent, again -> inbox.add(n); posted += Posted(n, silent, again) }, now = { now })
        source.events += event("a", now, alert = false)
        notices.deliver()
        notices.quiet = true
        source.events += event("b", now, origin = plan.copy(id = "other"))
        notices.deliver()
        assertTrue(posted.isEmpty())
        assertEquals(2, inbox.entries.value.size)
        assertTrue(source.pendingNotices().isEmpty())
    }

    @Test fun aConditionMatchThatIsOnlyARebuildDoesNotRing() = runTest {
        val now = 10_000L
        val (source, inbox, posted) = setUp()
        val notices = RadarNotices(source, inbox, post = { n, silent, again -> inbox.add(n); posted += Posted(n, silent, again) }, now = { now })
        source.events += event("a", now).copy(rebuilds = setOf(io.github.springthief1123.lovelyspace.core.roomIdentity(room)))
        notices.deliver()
        assertTrue(posted.single().silent)
        assertTrue("立て直し" in posted.single().notification.text)
    }

    @Test fun mergingAndTheSoundGapSurviveARestart() = runTest {
        var now = 10_000L
        val (source, inbox, posted) = setUp()
        val memory = Memory()
        fun notices() = RadarNotices(source, inbox, post = { n, silent, again -> inbox.add(n); posted += Posted(n, silent, again) }, now = { now }, persistence = memory)
        source.events += event("a", now)
        notices().deliver()
        now += 5_000
        source.events += event("b", now, rooms = listOf(room.copy(id = 43)))
        // プロセスが作り直されても、同じお知らせに足し、30 秒たつまでは鳴らさない。
        notices().deliver()
        assertEquals(posted[0].notification.id, posted[1].notification.id)
        assertTrue(posted[1].silent)
        assertEquals("全国で2件", posted[1].notification.text)
    }

    @Test fun theOriginCanBeReadBackFromTheId() = runTest {
        val now = 10_000L
        val (source, inbox, _) = setUp()
        val notices = RadarNotices(source, inbox, post = { n, _, _ -> inbox.add(n) }, now = { now })
        source.events += event("a", 1_234L, alert = false)
        notices.deliver()
        assertEquals(plan.key to 1_234L, RadarNotices.originOf(inbox.entries.value.single().id))
        assertNull(RadarNotices.originOf("pin-x-1"))
    }
}
