package io.github.springthief1123.lovelyspace

import android.app.Application
import androidx.room.Room
import io.github.springthief1123.lovelyspace.data.PresetDatabase
import io.github.springthief1123.lovelyspace.data.PresetRepository
import io.github.springthief1123.lovelyspace.data.NamedRoomFile
import io.github.springthief1123.lovelyspace.data.RoomListRepository
import io.github.springthief1123.lovelyspace.data.RoomPreferenceRepository
import io.github.springthief1123.lovelyspace.data.SearchPresetRepository
import io.github.springthief1123.lovelyspace.core.RefreshPacing
import io.github.springthief1123.lovelyspace.core.ShaloveClient
import io.github.springthief1123.lovelyspace.core.SharedSiteCookieJar
import io.github.springthief1123.lovelyspace.settings.SettingsRepository
import io.github.springthief1123.lovelyspace.settings.WebViewCookieStore
import io.github.springthief1123.lovelyspace.notify.AppNotifier
import io.github.springthief1123.lovelyspace.notify.NotificationInbox
import io.github.springthief1123.lovelyspace.notify.NotificationInboxStore
import io.github.springthief1123.lovelyspace.notify.RadarNotices
import io.github.springthief1123.lovelyspace.notify.toOpenedNotification
import io.github.springthief1123.lovelyspace.notify.toNotification
import io.github.springthief1123.lovelyspace.data.AutoEntryController
import io.github.springthief1123.lovelyspace.data.PinAlert
import io.github.springthief1123.lovelyspace.data.PinEvent
import io.github.springthief1123.lovelyspace.data.PinRepository
import io.github.springthief1123.lovelyspace.data.PinStore
import io.github.springthief1123.lovelyspace.data.PinView
import io.github.springthief1123.lovelyspace.data.WaitlistEntry
import io.github.springthief1123.lovelyspace.data.WaitlistRepository
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.roomIdentity
import io.github.springthief1123.lovelyspace.ui.chat.ActiveRooms
import io.github.springthief1123.lovelyspace.background.BackgroundSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class LovelySpaceApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        notifier.ensureChannels()
        // 通信の間隔の設定を、通信のたびに読める置き場へ写す。
        appScope.launch { settings.refreshPacing.collect { pacing.value = it } }
        // お知らせを残す時間の設定を写し、過ぎたお知らせを消す。
        appScope.launch { settings.noticeRetention.collect { notificationInbox.retention = it } }
        // レーダー・順番待ちが見ているジャンルの 1 ページ目を、新着の確認で読み直す（見つける画面は画面側で登録する）。
        appScope.launch { radar.hotGenres.distinctUntilChanged().collect { hotPages.want("radar", it) } }
        appScope.launch { waitlist.genres.distinctUntilChanged().collect { hotPages.want("waitlist", it) } }
        appScope.launch { pins.genres.distinctUntilChanged().collect { hotPages.want("pins", it) } }
        // 以前の順番待ち・追跡をピンへ移してから、満室のピンの空きを順番待ちの確認で早く追う。
        appScope.launch {
            try { migrateToPins() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
            combine(pins.fastWatch, waitlist.entries) { fast, entries -> fast to entries }.conflate().collect { (fast, entries) ->
                try { syncPinWatch(fast, entries) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
            }
        }
        // 前回の実行が空きの通知を出す前に止まっていれば、起動時に出し直す。
        appScope.launch { waitlist.deliverPending() }
        // 条件・名前で待つの新しい一致は、見つけたらすぐ（アプリを開いている間も）お知らせ・端末通知に出す。
        appScope.launch {
            radar.state.map { state -> state.events.filter { it.pendingNotice }.map { it.id } }.distinctUntilChanged().collect { pending ->
                if (pending.isNotEmpty()) try { radarNotices.deliver() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
            }
        }
        // 一覧を読むたびに、保存・非表示の部屋が立て直されていれば新しい部屋へ移す。
        appScope.launch { roomPreferences.followRebuilds(roomLists.observations) }
        // ピンが立て直しで新しい部屋へ移っても、自動で入室の回数（5 回まで）は引き継ぐ。
        appScope.launch {
            roomPreferences.moved.collect { (from, to) ->
                try { autoEntry.moveTarget(AutoEntryController.pinTarget(from), AutoEntryController.pinTarget(roomIdentity(to))) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
            }
        }
        // 背景で巡回する計画か、待っている（または通知を出し終えていない）順番待ちがあるときだけ周期実行を登録し、
        // どちらも無くなれば解除する。順番待ちがあるときは最短の 15 分ごとに確認する。
        appScope.launch {
            combine(radar.state, waitlist.entries) { state, _ ->
                // レーダーの読み込み中は判断を待つ。読み込めなかったときは、レーダーの計画は無いものとして順番待ちだけで決める。
                if (!state.loaded && state.error == null) return@combine null
                val plans = state.loaded && state.activeBackgroundPlans.isNotEmpty()
                val waiting = waitlist.hasPendingWork()
                (plans || waiting) to
                    if (waiting || !state.loaded) BackgroundSync.INTERVAL_MINUTES else state.backgroundIntervalMinutes.toLong()
            }.filterNotNull()
                .distinctUntilChanged()
                .collect { (enabled, minutes) -> BackgroundSync.update(this@LovelySpaceApp, enabled, minutes) }
        }
    }

    /** 満室のピン（知らせるもの）を、順番待ちの確認（ピン用の登録）で追う。ピンでなくなった・空いた登録は外す。 */
    private suspend fun syncPinWatch(fast: List<PinView>, entries: List<WaitlistEntry>) {
        val at = System.currentTimeMillis()
        val wanted = fast.take(WaitlistRepository.MAX_ACTIVE).associateBy { it.key }
        entries.filter { it.forPin && (it.key !in wanted || !it.active(at)) }.forEach { waitlist.remove(it.key) }
        wanted.values.filter { pin -> entries.none { it.key == pin.key && it.forPin && it.active(at) } }.forEach { pin ->
            val genre = io.github.springthief1123.lovelyspace.core.Genres[pin.room.genreKey] ?: return@forEach
            // 同時に待てる数を超えたら、その分は一覧の巡回と新着の確認だけで追う。
            runCatching { waitlist.register(pin.room, pin.page?.let { io.github.springthief1123.lovelyspace.core.RoomQuery(genre, page = it) }, forPin = true) }
        }
    }

    /**
     * 一度だけ、以前の順番待ち（待っているもの）とレーダーの追跡をピンへ移す。順番待ちは「空いたら知らせる」
     * （1 件ずつ自動入室をオンにしていたものは「自動で入室」）、追跡は知らせ方なしでメモを引き継ぐ。
     */
    private suspend fun migrateToPins() {
        val store = pinStore
        if (store.migrated) return
        val at = System.currentTimeMillis()
        waitlist.entries.value.filter { !it.forPin && it.active(at) }.forEach { entry ->
            pins.pin(entry.room, if (entry.autoEnter) PinAlert.AUTO else PinAlert.NOTIFY)
            // 登録は消さずにピン用へ変え、確認していたページ（後ろのページのこともある）を引き継ぐ。
            waitlist.markForPin(entry.key)
        }
        val state = radar.state.first { it.loaded || it.error != null }
        if (!state.loaded) return
        state.legacyTargets.forEach { target ->
            val room = target.room
            if (Genres[room.genreKey] == null) return@forEach
            pins.pin(room)
            if (target.note.isNotBlank()) pins.setMemo(roomIdentity(room), target.note)
        }
        if (state.legacyTargets.isNotEmpty()) radar.clearLegacyTargets()
        store.migrated = true
    }

    /** 通信の間隔の設定（設定画面で選ぶ）。読み込むまでは既定値。 */
    val pacing = MutableStateFlow(RefreshPacing())

    /** 本家への通信はアプリ全体でこの 1 つを共有し、アクセス間隔の制限を一元化する。 */
    val client: ShaloveClient by lazy {
        ShaloveClient(ShaloveClient.defaultHttpClient(SharedSiteCookieJar(WebViewCookieStore())), pacing = { pacing.value })
    }
    /** 一覧の取得結果の共有。名前が見えていた部屋の姿は端末に残し、満室・起動し直した後もピンなどで補う。 */
    val roomLists: RoomListRepository by lazy { RoomListRepository(client, NamedRoomFile(java.io.File(filesDir, NamedRoomFile.FILE))) }
    /** 新着の確認。見ているジャンルの 1 ページ目を、アプリが前面の間は設定の間隔ごとに読み直す（[MainActivity] が動かす）。 */
    val hotPages: io.github.springthief1123.lovelyspace.data.HotPagePoller by lazy {
        io.github.springthief1123.lovelyspace.data.HotPagePoller(roomLists, pacing = { pacing.value }, onFetched = { radar.absorbLatest(it) })
    }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    /** アプリロック。起動直後に画面を隠せるよう、設定は同期で読む。 */
    val appLock: io.github.springthief1123.lovelyspace.lock.AppLockController by lazy {
        io.github.springthief1123.lovelyspace.lock.AppLockController(
            io.github.springthief1123.lovelyspace.lock.AppLockStore(getSharedPreferences(io.github.springthief1123.lovelyspace.lock.AppLockStore.PREFS, MODE_PRIVATE)))
    }
    private val database: PresetDatabase by lazy {
        Room.databaseBuilder(this, PresetDatabase::class.java, "lovely-space.db")
            .addMigrations(PresetDatabase.MIGRATION_1_2, PresetDatabase.MIGRATION_2_3, PresetDatabase.MIGRATION_3_4, PresetDatabase.MIGRATION_4_5)
            .build()
    }
    val presets: PresetRepository by lazy { PresetRepository(database, settings) }
    val searchPresets: SearchPresetRepository by lazy { SearchPresetRepository(database) }
    val radar: io.github.springthief1123.lovelyspace.data.RadarRepository by lazy {
        io.github.springthief1123.lovelyspace.data.RadarRepository(database.presets(), roomLists, searchPresets, roomPreferences,
            ngWords = ngWords.readyFilter,
            // 自動入室をオンにした名前で追う人の新しい部屋（立て直しを含む）は、順番待ちと同じ歯止めで入室画面を開く。
            onNamedRoom = { rule, room -> autoEntry.offerNamed(rule, room) })
    }
    val roomPreferences: RoomPreferenceRepository by lazy { RoomPreferenceRepository(database) }
    /** NG ワードのセット。見つける・レーダーで、ON のセットに当たる部屋を最初から外す。 */
    val ngWords: io.github.springthief1123.lovelyspace.data.NgWordRepository by lazy { io.github.springthief1123.lovelyspace.data.NgWordRepository(database) }

    /** お知らせ（通知ベル）の履歴。端末通知と同じ内容を残す。 */
    val notificationInbox: NotificationInbox by lazy {
        NotificationInbox(NotificationInboxStore(getSharedPreferences(NotificationInboxStore.PREFS, MODE_PRIVATE)))
    }
    /** 順番待ちの自動入室。有効なら、空いた部屋の入室画面を画面側に開かせる。 */
    val autoEntry: io.github.springthief1123.lovelyspace.data.AutoEntryController by lazy {
        io.github.springthief1123.lovelyspace.data.AutoEntryController(
            startedAt = { settings.entrySettings.first().autoEntryStartedAt },
            persistence = io.github.springthief1123.lovelyspace.data.AutoEntryStore(
                getSharedPreferences(io.github.springthief1123.lovelyspace.data.AutoEntryStore.PREFS, MODE_PRIVATE)),
        )
    }
    /** 順番待ち。空きを見つけたら（有効なら自動で入室画面を開き）端末通知とお知らせに出す。 */
    val waitlist: io.github.springthief1123.lovelyspace.data.WaitlistRepository by lazy {
        io.github.springthief1123.lovelyspace.data.WaitlistRepository(
            io.github.springthief1123.lovelyspace.data.WaitlistStore(getSharedPreferences(io.github.springthief1123.lovelyspace.data.WaitlistStore.PREFS, MODE_PRIVATE)),
            roomLists,
            onOpened = { entry ->
                // ピン用の登録は、ピンの見張りが空きを知らせる。
                if (!entry.forPin) {
                    // 自動入室は通知より先に始める。通知の失敗で入室を遅らせない。
                    try { autoEntry.offer(entry) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
                    entry.toOpenedNotification()?.let { notifier.post(it) }
                }
            },
        )
    }
    private val pinStore: PinStore by lazy { PinStore(getSharedPreferences(PinStore.PREFS, MODE_PRIVATE)) }
    /** ピン。保存した部屋を見張り、空いた・似た部屋が建ったら（知らせ方に従って）知らせ、自動で入室画面を開く。 */
    val pins: PinRepository by lazy {
        PinRepository(pinStore, roomPreferences, roomLists,
            onEvent = { event ->
                // 自動入室は通知より先に始める。通知の失敗で入室を遅らせない。
                if (event is PinEvent.Opened) {
                    try { autoEntry.offerPin(event.pin.key, event.registered, event.pin.room, event.autoEnter) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
                }
                event.toNotification()?.let { notifier.post(it) }
            },
            moved = roomPreferences.moved,
            named = { roomLists.lastNamed(it) }, namedRevision = roomLists.namedRevision)
    }
    val notifier: AppNotifier by lazy {
        AppNotifier(this, notificationInbox, preview = { settings.notificationPreview.first() }, onOpened = { opened ->
            // 条件・名前で待つの一致のお知らせを開いたら、その条件の「新着」の印も消す。
            RadarNotices.originOf(opened.id)?.let { (origin, since) ->
                appScope.launch { try { radar.markOriginRead(origin, since) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { } }
            }
        })
    }
    /** 条件・名前で待つの新しい一致を、お知らせと端末通知に出す（前面でも背景でも）。 */
    val radarNotices: RadarNotices by lazy {
        RadarNotices(radar, notificationInbox, post = { notification, silent, alertAgain -> notifier.post(notification, silent = silent, alertAgain = alertAgain) },
            persistence = io.github.springthief1123.lovelyspace.notify.RadarNoticeStore(
                getSharedPreferences(io.github.springthief1123.lovelyspace.notify.RadarNoticeStore.PREFS, MODE_PRIVATE)))
    }

    /** 入室中の部屋（pwd を route に載せないための置き場）。進行中の部屋は暗号化して端末に残す。 */
    val activeRooms: ActiveRooms by lazy {
        ActiveRooms(io.github.springthief1123.lovelyspace.data.ActiveRoomStore(
            getSharedPreferences(io.github.springthief1123.lovelyspace.data.ActiveRoomStore.PREFS, MODE_PRIVATE),
            io.github.springthief1123.lovelyspace.data.KeystoreSecretBox(),
        ))
    }
}
