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
        fun publish(genre: Genre, rooms: List<Room>) = publishPage(genre, 1, 1, rooms)
        fun publishPage(genre: Genre, page: Int, lastPage: Int, rooms: List<Room>) {
            revision++
            val query = RoomQuery(genre, page = page)
            observations.value = observations.value + (query to ObservedRoomPage(query,
                RoomListPage(genre.key, rooms, null, null, page, lastPage, emptyMap(), null), revision * 1000, revision))
        }
    }

    /** 本番の一覧と同じく順序付きイベントを発行する。 */
    private class OrderedLists : ObservedRoomListSource {
        private val journal = RoomObservationJournal()
        override val observations = journal.observations
        override val changes = journal.changes
        override suspend fun fetch(query: RoomQuery, force: Boolean): RoomListPage = error("通信しない")
        suspend fun publish(genre: Genre, rooms: List<Room>, at: Long) {
            val query = RoomQuery(genre)
            journal.record(query, RoomListPage(genre.key, rooms, null, null, 1, 1, emptyMap(), null), at)
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

    @Test fun enteringAnAutoPinsRoomByHandTurnsItBackToNotify() {
        val waiting = room(500)
        val other = room(600)
        wait { pins.pin(waiting, PinAlert.AUTO) }
        wait { pins.pin(other, PinAlert.AUTO) }
        lists.publish(genre, listOf(other, waiting))
        awaitPins { it.size == 2 && it.all { pin -> pin.state == PinState.OPEN } }
        wait { pins.finishAutoEntry(genre.key, 500) }
        assertEquals(PinAlert.NOTIFY, pins.record(roomIdentity(waiting)).alert)
        assertEquals("別の部屋のピンはそのまま", PinAlert.AUTO, pins.record(roomIdentity(other)).alert)
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

    @Test fun rapidFullOpenFullObservationsStillNotifyTheIntermediateVacancy() {
        val ordered = OrderedLists()
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        pins = PinRepository(Memory(), prefs, ordered, onEvent = { events.send(it) },
            moved = prefs.moved, scope = scope)
        val full = room(500, RoomStatus.FULL)
        wait { pins.pin(full) }
        wait { ordered.publish(genre, listOf(room(600), full, room(400)), 1_000L) }
        awaitPins { it.singleOrNull()?.state == PinState.FULL }
        // 通知処理が追い付く前に戻っても、順序付きイベントに空きが残る。
        wait {
            ordered.publish(genre, listOf(room(600), room(500), room(400)), 2_000L)
            ordered.publish(genre, listOf(room(600), full, room(400)), 3_000L)
            pins.refresh()
        }
        assertEquals(500L, (wait { events.receive() } as PinEvent.Opened).pin.room.id)
        val view = awaitPins { it.singleOrNull()?.state == PinState.FULL && it.single().record.vacancies.size == 1 }.single()
        assertEquals(1, view.record.vacancies.size)
        assertNoEvent()
    }

    @Test fun aReusedIdWithUnknownOwnerDoesNotCreateAVacancyOrNotice() {
        val waiting = room(500)
        val unknown = waiting.copy(status = RoomStatus.FULL, action = RoomAction.NONE,
            name = null, gender = Gender.UNKNOWN, age = null)
        wait { pins.pin(unknown) }
        lists.publish(genre, listOf(room(600), unknown, room(400)))
        awaitPins { it.singleOrNull()?.state == PinState.FULL }
        val differentOwner = waiting.copy(name = "別の利用者", message = "別の人の待機メッセージ")
        lists.publish(genre, listOf(room(600), differentOwner, room(400)))
        awaitPins { it.singleOrNull()?.state == PinState.UNSEEN }
        assertTrue(pins.record(roomIdentity(unknown)).vacancies.isEmpty())
        assertNoEvent()
        // 保存用スナップショットも別人の募集文で書き換えない。
        wait { prefs.observe(listOf(differentOwner)) }
        assertEquals(unknown.message, wait { prefs.preferences.first().single().snapshotMessage })
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

    @Test fun aPendingMoveRestoresPinSettingsAfterProcessRestart() {
        val memory = Memory()
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        pins = PinRepository(memory, prefs, lists, onEvent = { events.send(it) }, moved = prefs.moved, scope = scope)
        val old = room(500, RoomStatus.FULL)
        wait { pins.pin(old, PinAlert.AUTO) }
        wait { pins.setMemo(roomIdentity(old), "立て直し前のメモ") }
        lists.publish(genre, listOf(room(600), old, room(400)))
        awaitPins { it.singleOrNull()?.state == PinState.FULL }

        // DB の移動だけが成功した直後にプロセス終了（ピン側に移動イベントが届かない）。
        scope.cancel()
        val rebuilt = old.copy(id = 950, status = RoomStatus.WAITING, action = RoomAction.ENTER)
        val others = (900L downTo 100L step 100).filterNot { it == 500L }.map { room(it) }
        lists.publish(genre, listOf(rebuilt) + others)
        wait { prefs.followRebuilds(lists.observations.value.genreWindow(genre)) }
        assertTrue(memory.saved.containsKey(roomIdentity(old)))

        // 再起動しても未処理イベントが再配信され、新しいピンに設定とメモを引き継ぐ。
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val restarted = RoomPreferenceRepository(db)
        pins = PinRepository(memory, restarted, lists, onEvent = { events.send(it) }, moved = restarted.moved, scope = scope)
        val moved = awaitPins { it.singleOrNull()?.key == roomIdentity(rebuilt) &&
            it.single().record.memo == "立て直し前のメモ" }.single()
        assertEquals(PinAlert.AUTO, moved.record.alert)
        assertEquals(1, moved.record.rebuilds)
        wait { db.presets().pinMoves().first { it.isEmpty() } }
    }

    @Test fun aManuallyAdoptedSimilarRoomAfterRestartDoesNotCreateFalseVacancyOrAutoEntry() {
        val memory = Memory()
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        pins = PinRepository(memory, prefs, lists, onEvent = { events.send(it) }, moved = prefs.moved, scope = scope)

        val original = room(500, RoomStatus.FULL, name = "合成さん",
            message = "寝る前にゆるく話そう。通話はなしでお願いします")
        wait { pins.pin(original, PinAlert.AUTO) }
        lists.publish(genre, listOf(room(600), original, room(400)))
        awaitPins { it.singleOrNull()?.state == PinState.FULL }

        val others = (900L downTo 100L step 100).filterNot { it == 500L }.map { room(it) }
        val similar = room(960, name = "合成さん", message = "今夜も寝る前にゆるく話そう。通話はなし")
        lists.publish(genre, listOf(similar) + others)
        val view = awaitPins { it.singleOrNull()?.state == PinState.CLOSED && it.single().similar != null }.single()
        assertEquals(960L, (wait { events.receive() } as PinEvent.Similar).similar.room.id)
        assertTrue(memory.saved.getValue(view.key).vacancies.isEmpty())

        // DB の保存先だけ更新したところでプロセス終了。PinRepository の移動購読は動かない。
        scope.cancel()
        wait { pins.adopt(view, similar) }
        val originalKey = roomIdentity(original)
        assertTrue(originalKey in memory.saved)
        val pending = wait { RoomPreferenceRepository(db).moved.first() }
        assertEquals(PinMoveKind.ADOPTED, pending.kind)

        // 再起動直後、移動先は既に空き。以前の CLOSED → OPEN を空きとして知らせてはならない。
        val restartedLists = Lists().also { it.publish(genre, listOf(similar) + others) }
        val restartedPrefs = RoomPreferenceRepository(db)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        pins = PinRepository(memory, restartedPrefs, restartedLists,
            onEvent = { events.send(it) }, moved = restartedPrefs.moved, scope = scope)
        val moved = awaitPins {
            it.singleOrNull()?.let { pin ->
                pin.key == roomIdentity(similar) && pin.state == PinState.OPEN && pin.record.rebuilds == 1
            } == true
        }.single()
        assertEquals(PinAlert.AUTO, moved.record.alert)
        assertTrue(moved.record.vacancies.isEmpty())
        assertNoEvent()
        wait { db.presets().pinMoves().first { it.isEmpty() } }

        // 後で本当に満室になり、再度空いた場合は通常どおり通知できる。
        restartedLists.publish(genre, listOf(similar.copy(status = RoomStatus.FULL, action = RoomAction.NONE)) + others)
        awaitPins { it.singleOrNull()?.state == PinState.FULL }
        restartedLists.publish(genre, listOf(similar) + others)
        assertEquals(960L, (wait { events.receive() } as PinEvent.Opened).pin.room.id)
        assertEquals(1, awaitPins { it.singleOrNull()?.record?.vacancies?.size == 1 }.single().record.vacancies.size)
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
        wait { pins.pin(pinned) }
        lists.publish(genre, listOf(room(600), pinned, room(400)))
        awaitPins { it.singleOrNull()?.state == PinState.OPEN }
        val others = (900L downTo 100L step 100).filterNot { it == 500L }.map { room(it) }
        val rewritten = room(960, name = "合成さん", message = "今夜も寝る前にゆるく話そう。通話はなし")
        lists.publish(genre, listOf(rewritten) + others)
        val view = awaitPins { it.singleOrNull()?.similar != null }.single()
        // ピンはどれも知らせるので、似た部屋も 1 回だけ知らせる（2026-11-06）。
        assertEquals(960L, (wait { events.receive() } as PinEvent.Similar).similar.room.id)
        wait { pins.dismiss(view.key, roomIdentity(rewritten)) }
        awaitPins { it.singleOrNull()?.let { p -> p.similar == null && p.state == PinState.CLOSED } == true }
    }


    @Test fun failedNotificationIsPersistedAndReplayedWithTheSameId() {
        val memory = Memory()
        var deliveryFails = true
        val successful = Channel<PinEvent>(Channel.UNLIMITED)
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val failedOnce = kotlinx.coroutines.CompletableDeferred<Unit>()
        pins = PinRepository(memory, prefs, lists, onEvent = { event ->
            if (deliveryFails) { failedOnce.complete(Unit); throw java.io.IOException("合成の通知失敗") }
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
        // 保存した直後はまだ最初の通知を試していないことがある。失敗を確かめてから通知を戻す。
        wait { failedOnce.await() }

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

    @Test fun pinStoreTurnsAnExpiredAutoEntryBackToNotify() {
        // 以前の「自動で入室」は 3 時間の期限（autoUntil）があり、過ぎたものは「空いたら知らせる」として表示していた。
        val context = ApplicationProvider.getApplicationContext<Application>()
        val prefs = context.getSharedPreferences("pin-auto-legacy-test", android.content.Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val raw = org.json.JSONArray()
            .put(org.json.JSONObject().put("key", "expired").put("alert", "AUTO").put("autoUntil", now - 1))
            .put(org.json.JSONObject().put("key", "running").put("alert", "AUTO").put("autoUntil", now + 3_600_000))
            .put(org.json.JSONObject().put("key", "new").put("alert", "AUTO"))
        prefs.edit().putString("records", raw.toString()).commit()
        val loaded = PinStore(prefs).load()
        assertEquals(PinAlert.NOTIFY, loaded.getValue("expired").alert)
        assertEquals(PinAlert.AUTO, loaded.getValue("running").alert)
        assertEquals(PinAlert.AUTO, loaded.getValue("new").alert)
    }

    @Test fun pinStoreTurnsLegacyOffIntoNotify() {
        // 以前の「知らせない」（OFF）のピンも、ピンはもれなく知らせるので「空いたら知らせる」として読む（2026-11-06）。
        val context = ApplicationProvider.getApplicationContext<Application>()
        val prefs = context.getSharedPreferences("pin-off-legacy-test", android.content.Context.MODE_PRIVATE)
        val raw = org.json.JSONArray()
            .put(org.json.JSONObject().put("key", "off").put("alert", "OFF"))
            .put(org.json.JSONObject().put("key", "missing"))
        prefs.edit().putString("records", raw.toString()).commit()
        val loaded = PinStore(prefs).load()
        assertEquals(PinAlert.NOTIFY, loaded.getValue("off").alert)
        assertEquals(PinAlert.NOTIFY, loaded.getValue("missing").alert)
    }

    @Test fun openPinsPageIsReadAgainLikeFullPins() {
        // 空いているピンのページも確認のたびに読み直し、閉じた部屋を「空いています」のまま残さない（2026-11-06）。
        val open = room(500)
        wait { pins.pin(open) }
        lists.publish(genre, listOf(room(600), open, room(400)))
        awaitPins { it.singleOrNull()?.state == PinState.OPEN }
        assertEquals(listOf(RoomQuery(genre, page = 1)), pins.openPages())
    }

    @Test fun restartingWithAnOpenPinKeepsItsPageAndCanContinueSearching() {
        val memory = Memory()
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        pins = PinRepository(memory, prefs, lists, onEvent = { events.send(it) }, moved = prefs.moved, scope = scope)
        val open = room(500)
        wait { pins.pin(open) }
        lists.publishPage(genre, 3, 5, listOf(open))
        awaitPins { it.singleOrNull()?.state == PinState.OPEN }
        assertEquals(3, pins.record(roomIdentity(open)).lastKnownPage)
        assertEquals(5, pins.record(roomIdentity(open)).lastPageCount)

        // 再起動直後は一覧の観測が空でも保存していたページから見張る。
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val restartedLists = Lists()
        pins = PinRepository(memory, prefs, restartedLists, onEvent = { events.send(it) }, moved = prefs.moved, scope = scope)
        awaitPins { it.singleOrNull()?.state == PinState.UNSEEN }
        assertTrue(RoomQuery(genre, page = 3) in pins.openPages())
        // 元のページから消えた後は隣を確認し、未発見ならさらに探す。
        restartedLists.publishPage(genre, 3, 5, listOf(room(700)))
        awaitPins { it.singleOrNull()?.record?.scanMissed ?: 0 >= 1 }
        assertTrue(RoomQuery(genre, page = 2) in pins.openPages())
        restartedLists.publishPage(genre, 2, 5, listOf(room(800)))
        awaitPins { it.singleOrNull()?.record?.scanMissed ?: 0 >= 2 }
        assertTrue(RoomQuery(genre, page = 4) in pins.openPages())
    }

    @Test fun pinWithoutChoosingNotifiesWhenItOpens() {
        val full = room(500, RoomStatus.FULL)
        wait { pins.pin(full) }
        lists.publish(genre, listOf(room(600), full, room(400)))
        awaitPins { it.singleOrNull()?.state == PinState.FULL }
        lists.publish(genre, listOf(room(600), room(500), room(400)))
        val opened = wait { events.receive() } as PinEvent.Opened
        assertEquals(500L, opened.pin.room.id)
        assertEquals(false, opened.autoEnter)
    }

    @Test fun everyFullPinIsWatchedRegardlessOfHowItNotifies() {
        // 知らせるピンに上限はなく、満室のピンはすべて空きを確かめる対象にする（2026-11-06）。
        (1L..7L).forEach { id -> wait { pins.pin(room(id * 100, RoomStatus.FULL), PinAlert.NOTIFY) } }
        wait { pins.pin(room(900, RoomStatus.FULL)) }
        awaitPins { it.size == 8 }
        assertEquals(7, (1L..7L).count { pins.record(roomIdentity(room(it * 100, RoomStatus.FULL))).alert == PinAlert.NOTIFY })
        val watched = wait { pins.fastWatch.first { it.size == 8 } }
        assertTrue(watched.any { it.key == roomIdentity(room(900, RoomStatus.FULL)) })
    }
}
