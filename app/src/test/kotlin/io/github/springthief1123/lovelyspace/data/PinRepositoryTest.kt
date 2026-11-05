package io.github.springthief1123.lovelyspace.data

import android.app.Application
import androidx.room.Room as RoomDb
import androidx.test.core.app.ApplicationProvider
import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.notify.toNotification
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PinRepositoryTest {
    // 合成データ。本家の実データは使わない。
    private val genre = Genres["zenkoku"]!!
    private fun room(id: Long, status: RoomStatus = RoomStatus.WAITING, name: String? = "合成$id", message: String = "合成の募集文$id、よろしくお願いします") =
        Room(id, genre.key, status, if (status == RoomStatus.FULL) RoomAction.NONE else RoomAction.ENTER, null, name, Gender.FEMALE, 25, null, message)

    private class Lists : ObservedRoomListSource {
        override val observations = MutableStateFlow<Map<RoomQuery, ObservedRoomPage>>(emptyMap())
        private var revision = 0L
        override suspend fun fetch(query: RoomQuery, force: Boolean): RoomListPage = error("通信しない")
        fun publish(genre: Genre, rooms: List<Room>) {
            revision++
            val query = RoomQuery(genre)
            observations.value = mapOf(query to ObservedRoomPage(query, RoomListPage(genre.key, rooms, null, null, 1, 1, emptyMap(), null), revision * 1000, revision))
        }
    }

    private class Memory : PinPersistence {
        var saved: Map<String, PinRecord> = emptyMap()
        var failSave = false
        override fun load() = saved
        override fun save(records: Map<String, PinRecord>) {
            if (failSave) throw java.io.IOException("合成の保存失敗")
            saved = records
        }
    }

    private lateinit var db: PresetDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var prefs: RoomPreferenceRepository
    private val lists = Lists()
    private val events = Channel<PinEvent>(Channel.UNLIMITED)
    private lateinit var pins: PinRepository

    @Before fun setUp() {
        db = RoomDb.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), PresetDatabase::class.java)
            .allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        prefs = RoomPreferenceRepository(db)
        pins = PinRepository(Memory(), prefs, lists, onEvent = { events.send(it) }, moved = prefs.moved, scope = scope)
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun <T> wait(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }
    private fun awaitPins(predicate: (List<PinView>) -> Boolean): List<PinView> = wait { pins.pins.first(predicate) }
    /** 少し待っても知らせが来ないこと。 */
    private fun assertNoEvent() = runBlocking { assertNull(withTimeoutOrNull(300) { events.receive() }) }

    @Test fun aPinWithoutANameBorrowsTheNameSeenInTheListAndRemembersIt() {
        val waiting = room(500)
        // 満室の一覧では名前・性別・年齢が出ない。
        val full = waiting.copy(status = RoomStatus.FULL, action = RoomAction.NONE, name = null, gender = Gender.UNKNOWN, age = null)
        val named = MutableStateFlow<Map<String, Room>>(emptyMap())
        pins = PinRepository(Memory(), prefs, lists, onEvent = { events.send(it) }, moved = prefs.moved, scope = scope,
            named = { named.value[roomIdentity(it)] }, namedRevision = named.map { it.size.toLong() })
        wait { pins.pin(full) }
        lists.publish(genre, listOf(room(600), full, room(400)))
        assertNull(awaitPins { it.singleOrNull()?.state == PinState.FULL }.single().room.name)
        // 待機中に一覧で見た姿が分かると、名前・性別を補い、保存の記録の照合用の値にも残す。
        named.value = mapOf(roomIdentity(waiting) to waiting)
        val pin = awaitPins { it.singleOrNull()?.room?.name != null }.single()
        assertEquals("合成500", pin.name)
        assertEquals(Gender.FEMALE, pin.room.gender)
        assertTrue(pin.listed)
        val saved = wait { prefs.preferences.first { list -> list.single().identityName != null } }.single()
        assertEquals("合成500", saved.identityName)
        assertEquals(Gender.FEMALE.name, saved.identityGender)
        assertEquals(25, saved.identityAge)
        // 部屋の番号が別の人に使い回されていれば（待機メッセージが違う）補わない。
        val reused = waiting.copy(id = 400, name = "別の人", message = "まったく別の募集文です、よろしく")
        assertNull(borrowNamed(room(400, RoomStatus.FULL, name = null), reused))
    }

    @Test fun aFullPinThatOpensIsNotifiedOnceAndItsVacancyIsRecorded() {
        val full = room(500, RoomStatus.FULL)
        wait { pins.pin(full, PinAlert.NOTIFY) }
        lists.publish(genre, listOf(room(600), full, room(400)))
        assertEquals(PinState.FULL, awaitPins { it.singleOrNull()?.state == PinState.FULL }.single().state)
        assertNoEvent()
        lists.publish(genre, listOf(room(600), room(500), room(400)))
        val opened = wait { events.receive() } as PinEvent.Opened
        assertEquals(500L, opened.pin.room.id)
        assertFalse(opened.autoEnter)
        assertEquals(1, awaitPins { it.single().record.vacancies.isNotEmpty() }.single().record.vacancies.size)
        // 同じ様子を読み直しても、もう一度は知らせない。
        lists.publish(genre, listOf(room(700), room(600), room(500), room(400)))
        assertNoEvent()
    }

    @Test fun aRebuiltPinMovesToTheNewRoomAndTellsThatItIsOpen() {
        val pinned = room(500, RoomStatus.FULL)
        wait { pins.pin(pinned, PinAlert.NOTIFY) }
        lists.publish(genre, listOf(room(600), pinned, room(400)))
        awaitPins { it.singleOrNull()?.state == PinState.FULL }
        // 読んだ ID の範囲に無くなったので閉じた。
        val others = (900L downTo 100L step 100).filterNot { it == 500L }.map { room(it) }
        lists.publish(genre, others)
        awaitPins { it.singleOrNull()?.state == PinState.CLOSED }
        // 同じ名前・募集文で立て直された部屋へ、保存の記録ごと移る。
        val rebuilt = pinned.copy(id = 950, status = RoomStatus.WAITING, action = RoomAction.ENTER)
        lists.publish(genre, listOf(rebuilt) + others)
        wait { prefs.followRebuilds(lists.observations.value.genreWindow(genre)) }
        val opened = wait { events.receive() } as PinEvent.Opened
        assertEquals(950L, opened.pin.room.id)
        val moved = awaitPins { it.singleOrNull()?.room?.id == 950L && it.single().state == PinState.OPEN }.single()
        assertEquals(1, moved.record.rebuilds)
        assertEquals(PinAlert.NOTIFY, moved.record.alert)
    }

    @Test fun aSimilarRoomIsSuggestedAndAdoptingItMovesThePinWithoutCountingAVacancy() {
        val pinned = room(500, name = "合成さん", message = "寝る前にゆるく話そう。通話はなしでお願いします")
        wait { pins.pin(pinned, PinAlert.NOTIFY) }
        lists.publish(genre, listOf(room(600), pinned, room(400)))
        awaitPins { it.singleOrNull()?.state == PinState.OPEN }
        val others = (900L downTo 100L step 100).filterNot { it == 500L }.map { room(it) }
        val rewritten = room(960, name = "合成さん", message = "今夜も寝る前にゆるく話そう。通話はなし")
        lists.publish(genre, listOf(rewritten) + others)
        val similar = wait { events.receive() } as PinEvent.Similar
        assertEquals(960L, similar.similar.room.id)
        // 通知の題（「<ピンの名前>に似た部屋」）に使うピンの部屋は、似た部屋に置き換えない。
        assertEquals(500L, similar.pin.room.id)
        val view = awaitPins { it.singleOrNull()?.similar != null }.single()
        wait { pins.adopt(view, rewritten) }
        val adopted = awaitPins { it.singleOrNull()?.room?.id == 960L }.single()
        assertEquals(PinState.OPEN, adopted.state)
        assertTrue(adopted.record.vacancies.isEmpty())
        assertNoEvent()
    }

    @Test fun answeringDifferentPersonStopsSuggestingThatRoom() {
        val pinned = room(500, name = "合成さん", message = "寝る前にゆるく話そう。通話はなしでお願いします")
        wait { pins.pin(pinned, PinAlert.OFF) }
        lists.publish(genre, listOf(room(600), pinned, room(400)))
        awaitPins { it.singleOrNull()?.state == PinState.OPEN }
        val others = (900L downTo 100L step 100).filterNot { it == 500L }.map { room(it) }
        val rewritten = room(960, name = "合成さん", message = "今夜も寝る前にゆるく話そう。通話はなし")
        lists.publish(genre, listOf(rewritten) + others)
        val view = awaitPins { it.singleOrNull()?.similar != null }.single()
        // 知らせないピンは、似た部屋もレーダーに出すだけ。
        assertNoEvent()
        wait { pins.dismiss(view.key, roomIdentity(rewritten)) }
        awaitPins { it.singleOrNull()?.let { p -> p.similar == null && p.state == PinState.CLOSED } == true }
    }


    @Test fun failedNotificationIsPersistedAndReplayedWithTheSameId() {
        val memory = Memory()
        var deliveryFails = true
        val successful = Channel<PinEvent>(Channel.UNLIMITED)
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        pins = PinRepository(memory, prefs, lists, onEvent = { event ->
            if (deliveryFails) throw java.io.IOException("合成の通知失敗")
            successful.send(event)
        }, moved = prefs.moved, scope = scope)
        val full = room(500, RoomStatus.FULL)
        wait { pins.pin(full, PinAlert.NOTIFY) }
        lists.publish(genre, listOf(room(600), full, room(400)))
        awaitPins { it.singleOrNull()?.state == PinState.FULL }
        lists.publish(genre, listOf(room(600), room(500), room(400)))

        val pending = wait { pins.records.first { it[roomIdentity(full)]?.pendingNotices?.isNotEmpty() == true } }
            .getValue(roomIdentity(full)).pendingNotices.single()
        assertEquals(PinNoticeType.OPENED, pending.type)
        assertTrue(memory.saved.getValue(roomIdentity(full)).pendingNotices.isNotEmpty())

        deliveryFails = false
        wait { pins.refresh() }
        val replay = wait { successful.receive() } as PinEvent.Opened
        assertTrue(replay.replayed)
        assertEquals(pending.id, replay.toNotification()!!.id)
        assertEquals(pending.at, replay.toNotification()!!.at)
        wait { pins.records.first { it[roomIdentity(full)]?.pendingNotices?.isEmpty() == true } }
        assertTrue(memory.saved.getValue(roomIdentity(full)).pendingNotices.isEmpty())
        assertNoEvent()
    }

    @Test fun anUnsentNotificationSurvivesRestartWithoutAutoEntering() {
        val memory = Memory()
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        pins = PinRepository(memory, prefs, lists, onEvent = { throw java.io.IOException("合成の通知失敗") },
            moved = prefs.moved, scope = scope)
        val full = room(500, RoomStatus.FULL)
        wait { pins.pin(full, PinAlert.AUTO) }
        lists.publish(genre, listOf(room(600), full, room(400)))
        awaitPins { it.singleOrNull()?.state == PinState.FULL }
        lists.publish(genre, listOf(room(600), room(500), room(400)))
        val original = wait { pins.records.first { it[roomIdentity(full)]?.pendingNotices?.isNotEmpty() == true } }
            .getValue(roomIdentity(full)).pendingNotices.single()

        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val received = Channel<PinEvent>(Channel.UNLIMITED)
        pins = PinRepository(memory, prefs, lists, onEvent = { received.send(it) }, moved = prefs.moved, scope = scope)
        val replay = wait { received.receive() } as PinEvent.Opened
        assertTrue(replay.replayed)
        assertEquals(original.id, replay.noticeId)
        assertEquals(original.at, replay.noticeAt)
        assertTrue(replay.autoEnter)
        wait { pins.records.first { it[roomIdentity(full)]?.pendingNotices?.isEmpty() == true } }
    }

    @Test fun aFailedSaveDoesNotEmitAnEventOrAdvanceTheRecordedState() {
        val memory = Memory()
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        pins = PinRepository(memory, prefs, lists, onEvent = { events.send(it) }, moved = prefs.moved, scope = scope)
        val full = room(500, RoomStatus.FULL)
        wait { pins.pin(full, PinAlert.NOTIFY) }
        lists.publish(genre, listOf(room(600), full, room(400)))
        awaitPins { it.singleOrNull()?.state == PinState.FULL }
        memory.failSave = true
        lists.publish(genre, listOf(room(600), room(500), room(400)))
        assertNoEvent()
        assertEquals(PinState.FULL, pins.record(roomIdentity(full)).state)

        memory.failSave = false
        lists.publish(genre, listOf(room(700), room(600), room(500), room(400)))
        val event = wait { events.receive() } as PinEvent.Opened
        assertEquals(500L, event.pin.room.id)
        assertEquals(1, memory.saved.getValue(roomIdentity(full)).vacancies.size)
        wait { pins.records.first { it[roomIdentity(full)]?.pendingNotices?.isEmpty() == true } }
    }

    @Test fun pinStoreRoundTripsPendingOpenAndSimilarNotifications() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val store = PinStore(context.getSharedPreferences("pin-outbox-test", android.content.Context.MODE_PRIVATE))
        val full = room(500, RoomStatus.FULL)
        val opened = room(500)
        val similar = room(960)
        val key = roomIdentity(full)
        val notices = listOf(
            PendingPinNotice("pin-$key-1000", PinNoticeType.OPENED, opened, 1_000L, registered = full, autoEnter = true),
            PendingPinNotice("pin-similar-$key-960", PinNoticeType.SIMILAR, similar, 2_000L, similarity = 0.8),
        )
        val records = mapOf(key to PinRecord(key, alert = PinAlert.NOTIFY, state = PinState.OPEN, pendingNotices = notices))
        store.save(records)
        assertEquals(records, store.load())
    }

    @Test fun turningOffNotificationsClearsUnsentPinEvents() {
        val memory = Memory()
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        pins = PinRepository(memory, prefs, lists, onEvent = { throw java.io.IOException("合成の通知失敗") },
            moved = prefs.moved, scope = scope)
        val full = room(500, RoomStatus.FULL)
        wait { pins.pin(full, PinAlert.NOTIFY) }
        lists.publish(genre, listOf(room(600), full, room(400)))
        awaitPins { it.singleOrNull()?.state == PinState.FULL }
        lists.publish(genre, listOf(room(600), room(500), room(400)))
        wait { pins.records.first { it[roomIdentity(full)]?.pendingNotices?.isNotEmpty() == true } }
        wait { pins.setAlert(roomIdentity(full), PinAlert.OFF) }
        assertTrue(memory.saved.getValue(roomIdentity(full)).pendingNotices.isEmpty())
        assertEquals(PinAlert.OFF, memory.saved.getValue(roomIdentity(full)).alert)
    }

    @Test fun onlyFiveRoomsCanBeNotifiedAtOnce() {
        (1L..PinRepository.MAX_NOTIFYING).forEach { id -> wait { pins.pin(room(id * 100, RoomStatus.FULL), PinAlert.NOTIFY) } }
        awaitPins { it.size == PinRepository.MAX_NOTIFYING }
        val extra = room(900, RoomStatus.FULL)
        assertThrows(IllegalArgumentException::class.java) { wait { pins.pin(extra, PinAlert.NOTIFY) } }
        // 知らせないピンはいくつでもでき、ほかを止めれば知らせられる。
        wait { pins.pin(extra) }
        awaitPins { it.size == PinRepository.MAX_NOTIFYING + 1 }
        wait { pins.setAlert(roomIdentity(room(100, RoomStatus.FULL)), PinAlert.OFF) }
        wait { pins.setAlert(roomIdentity(extra), PinAlert.NOTIFY) }
        assertEquals(PinAlert.NOTIFY, pins.record(roomIdentity(extra)).alert)
    }
}
