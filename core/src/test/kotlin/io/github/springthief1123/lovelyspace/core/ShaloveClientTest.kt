package io.github.springthief1123.lovelyspace.core

import io.github.springthief1123.lovelyspace.core.chat.ChatRoomRef
import io.github.springthief1123.lovelyspace.core.chat.EntryForm
import io.github.springthief1123.lovelyspace.core.chat.EntryProfile
import io.github.springthief1123.lovelyspace.core.chat.EntryResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

class ShaloveClientTest {
    private val server = MockWebServer()
    private var now = 1_000_000L
    private val sleeps = mutableListOf<Long>()

    private val client = ShaloveClient(
        pacing = { RefreshPacing(minIntervalMs = 3_000) },
        clock = { now },
        sleep = { ms -> sleeps += ms; now += ms },
    )

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun acceptsOnlyTheSameRoomAndHostOnEntryRedirect() = runTest {
        val locations = ArrayDeque(listOf(
            "/2shot.php?room_id=42&pwd=test-token",
            "https://example.com/2shot.php?room_id=42&pwd=test-token",
            "http://chat.shalove.net/2shot.php?room_id=42&pwd=test-token",
            "/sub/2shot.php?room_id=42&pwd=test-token",
            "/2shot.php?room_id=43&pwd=test-token",
            "/2shot.php?room_id=42&pwd=",
        ))
        val client = ShaloveClient(
            http = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(302).message("Found")
                    .header("Location", locations.removeFirst())
                    .body("".toResponseBody()).build()
            }.build(),
            clock = { now },
            sleep = { ms -> now += ms },
        )
        val form = EntryForm(
            host = "chat.shalove.net",
            roomId = 42,
            genreKey = "kanto",
            pwd = "entry-token",
            hostDescription = "",
            waitingMessage = "",
            requiresCaptcha = false,
            defaultName = "",
        )
        val profile = EntryProfile(name = "テスト", sex = 1, years = 30)

        assertEquals(
            EntryResult.Entered(ChatRoomRef("chat.shalove.net", 42, "test-token", "kanto")),
            client.enter(form, profile),
        )
        repeat(5) {
            assertEquals(EntryResult.Rejected("入室できませんでした"), client.enter(form, profile))
        }
    }

    @Test
    fun banIsNotSentIfParticipantChangedWhileWaitingForGate() = runTest {
        val calls = IntArray(1)
        var sameParticipant = true
        val client = ShaloveClient(
            http = OkHttpClient.Builder().addInterceptor { chain ->
                calls[0]++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("ok".toResponseBody()).build()
            }.build(),
            clock = { now },
            sleep = { ms -> now += ms; sameParticipant = false },
        )
        client.get("https://chat.shalove.net/synthetic")
        val room = ChatRoomRef("chat.shalove.net", 42, "test-pwd", "kanto")

        assertEquals(null, client.banGuest(room) { sameParticipant })
        assertEquals("対象が入れ替わったら BAN の HTTP POST は送らない", 1, calls[0])
    }

    @Test
    fun decodesShiftJisWhenNoCharsetIsDeclared() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write("待機中".toByteArray(SITE_CHARSET))).setHeader("Content-Type", "text/html"))
        assertEquals("待機中", client.get(server.url("/").toString()))
    }

    @Test
    fun honoursDeclaredCharset() = runTest {
        server.enqueue(MockResponse().setBody("満室").setHeader("Content-Type", "text/html; charset=UTF-8"))
        assertEquals("満室", client.get(server.url("/").toString()))
    }

    @Test
    fun sendsDesktopUserAgent() = runTest {
        server.enqueue(MockResponse().setBody("ok"))
        client.get(server.url("/").toString())
        assertEquals(ShaloveClient.USER_AGENT, server.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun spacesRequestsByMinInterval() = runTest {
        repeat(3) { server.enqueue(MockResponse().setBody("ok")) }
        client.get(server.url("/a").toString())
        now += 1_000
        client.get(server.url("/b").toString())
        client.get(server.url("/c").toString())
        assertEquals(listOf(2_000L, 3_000L), sleeps)
    }

    @Test
    fun aPageTheUserOpenedGoesBeforeQueuedListFetchesWithTheSameSpacing() = runTest {
        repeat(4) { server.enqueue(MockResponse().setBody("ok")) }
        val sentAt = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val http = OkHttpClient.Builder().addInterceptor { chain -> sentAt += testScheduler.currentTime; chain.proceed(chain.request()) }.build()
        val gated = ShaloveClient(http = http, pacing = { RefreshPacing(minIntervalMs = 3_000) }, clock = { testScheduler.currentTime }, sleep = { delay(it) })
        gated.get(server.url("/a").toString())
        // 一覧の取得 b・d が間隔を待っている間に、利用者の操作 c が来る。c は待っている b・d を追い越す。
        val b = async { gated.get(server.url("/b").toString()) }
        advanceTimeBy(20)
        val d = async { gated.get(server.url("/d").toString()) }
        advanceTimeBy(20)
        val c = async { gated.get(server.url("/c").toString(), operation = true) }
        awaitAll(b, c, d)
        val paths = (1..4).map { server.takeRequest().path }
        assertEquals(listOf("/a", "/c"), paths.take(2))
        assertEquals(setOf("/b", "/d"), paths.drop(2).toSet())
        // 順番を入れ替えても、間隔は毎回空けている。
        assertEquals(listOf(3_000L, 3_000L, 3_000L), sentAt.zipWithNext { x, y -> y - x })
    }

    @Test
    fun aRefreshTheUserPressedSkipsTheCacheAndDoesNotWait() = runTest {
        val calls = IntArray(1)
        val sleeps = mutableListOf<Long>()
        val client = ShaloveClient(
            http = countingHttp(calls),
            pacing = { RefreshPacing(minIntervalMs = 3_000) },
            clock = { now },
            sleep = { ms -> sleeps += ms; now += ms },
        )
        val query = RoomQuery(genre = Genres.default)
        client.fetchRoomList(query)
        // 押すたびにキャッシュを使わず取り直す。本家のブラウザと同じく、前の通信から間隔を空けない。
        client.fetchRoomList(query, manual = true)
        client.fetchRoomList(query, manual = true)
        assertEquals(3, calls[0])
        assertEquals(emptyList<Long>(), sleeps)
        // 押した後の自動の取得は、設定どおり 3 秒空ける。
        client.fetchRoomList(query.copy(page = 2))
        assertEquals(listOf(2_900L), sleeps)
    }

    @Test
    fun aResponseThatIsNotARoomListFailsAndKeepsThePreviousList() = runTest {
        val bodies = ArrayDeque(listOf(fixture("genre_kanto_page1.html"), "<html><head><meta charset=\"utf-8\"></head><body><p>しばらくお待ちください</p></body></html>"))
        val client = ShaloveClient(
            http = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(bodies.removeFirst().toResponseBody("text/html; charset=UTF-8".toMediaType())).build()
            }.build(),
            clock = { now },
            sleep = { ms -> now += ms },
        )
        val query = RoomQuery(Genres["kanto"]!!)
        val first = client.fetchRoomList(query)
        // 連打した取り直しで一覧ではないページが返っても、0 件の一覧にはせず失敗にする。
        val failure = runCatching { client.fetchRoomList(query, manual = true) }.exceptionOrNull()
        assertTrue(failure is RoomListUnavailableException)
        assertTrue((failure as RoomListUnavailableException).page!!.startsWith("「しばらくお待ちください」・表 0・行 0"))
        // 前回の一覧はキャッシュに残る。
        assertEquals(first, client.fetchRoomList(query))
    }

    @Test
    fun noWaitWhenEnoughTimeHasPassed() = runTest {
        repeat(2) { server.enqueue(MockResponse().setBody("ok")) }
        client.get(server.url("/a").toString())
        now += 10_000
        client.get(server.url("/b").toString())
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun cancellingListBeforeHeadersCancelsHttpAndAllowsNextGenre() {
        assertListCancellation(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE), waitForBody = false)
    }

    @Test
    fun cancellingListWhileReadingBodyCancelsHttpAndAllowsNextGenre() {
        assertListCancellation(MockResponse().setBody(EMPTY_LIST).throttleBody(1, 1, TimeUnit.SECONDS), waitForBody = true)
    }

    /** 実際のOkHttp Callとローカルサーバーで、取消・一覧ロック・キャッシュ・間隔をまとめて確認。 */
    private fun assertListCancellation(firstResponse: MockResponse, waitForBody: Boolean) = runBlocking {
        val calls = CopyOnWriteArrayList<Call>()
        val bodyStarted = CompletableDeferred<Unit>()
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                // 本家には通信せず、同じCallの接続先だけを合成データのローカルサーバーへ向ける。
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) { calls += call }
                override fun responseBodyStart(call: Call) { bodyStarted.complete(Unit) }
            })
            .build()
        val client = ShaloveClient(http = http, clock = { now }, sleep = { ms -> sleeps += ms; now += ms })
        server.enqueue(firstResponse)
        repeat(2) { server.enqueue(MockResponse().setBody(EMPTY_LIST)) }
        val original = RoomQuery(Genres.default)
        val obsolete = async { client.fetchRoomList(original) }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
        if (waitForBody) withTimeout(5_000) { bodyStarted.await() }
        withTimeout(5_000) { obsolete.cancelAndJoin() }
        assertTrue("Coroutineの取消が実際のCallへ伝わる", calls.first().isCanceled())

        // 読み取りタイムアウトを待たず次ジャンルへ進め、最小通信間隔は短くしない。
        val next = RoomQuery(Genres["talk"]!!)
        assertEquals(next.genre.key, withTimeout(5_000) { client.fetchRoomList(next) }.genreKey)
        assertEquals(listOf(3_000L), sleeps)
        // 取消した一覧を空の成功結果としてキャッシュしない。
        withTimeout(5_000) { client.fetchRoomList(original) }
        assertEquals(3, calls.size)
        assertEquals(listOf(3_000L, 3_000L), sleeps)
    }

    @Test
    fun detectsMetaCharset() {
        val bytes = """<html><head><meta charset="utf-8"></head>""".toByteArray()
        assertEquals(Charsets.UTF_8, ShaloveClient.detectCharset("text/html", bytes))
        assertEquals(SITE_CHARSET, ShaloveClient.detectCharset(null, "<html>".toByteArray()))
    }

    /** 本家へ出ずに一覧の HTML を返し、通信回数を数えるクライアント。通信には 100ms かかる想定。 */
    private fun countingListClient(calls: IntArray, pacing: () -> RefreshPacing = { RefreshPacing() }) = ShaloveClient(
        http = countingHttp(calls),
        pacing = pacing,
        clock = { now },
        sleep = { ms -> now += ms },
    )

    private fun countingHttp(calls: IntArray) = OkHttpClient.Builder().addInterceptor { chain ->
        calls[0]++
        now += 100
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(EMPTY_LIST.toResponseBody("text/html; charset=Shift_JIS".toMediaType()))
            .build()
    }.build()

    @Test
    fun concurrentListFetchesHitTheSiteOnce() = runTest {
        val calls = IntArray(1)
        val client = countingListClient(calls)
        val query = RoomQuery(genre = Genres.default)
        List(3) { async { client.fetchRoomList(query) } }.awaitAll()
        assertEquals(1, calls[0])
    }

    @Test
    fun concurrentForcedRefreshesShareOneFetch() = runTest {
        val calls = IntArray(1)
        val client = countingListClient(calls)
        val query = RoomQuery(genre = Genres.default)
        List(2) { async { client.fetchRoomList(query, forceRefresh = true) } }.awaitAll()
        assertEquals(1, calls[0])
        // 取得し終えた後の強制更新は取り直す。
        client.fetchRoomList(query, forceRefresh = true)
        assertEquals(2, calls[0])
    }

    @Test
    fun listsAreCachedForTheShortestAutomaticRefresh() = runTest {
        // 既定では最も短い自動の取り直しは 4 秒。1 ページ目も 2 ページ目以降も同じ期間だけ使い回す。
        for (page in listOf(1, 2)) {
            val calls = IntArray(1)
            val client = countingListClient(calls)
            val query = RoomQuery(genre = Genres.default, page = page)
            client.fetchRoomList(query)
            now += 3_000
            client.fetchRoomList(query)
            assertEquals(1, calls[0])
            now += 1_500
            client.fetchRoomList(query)
            assertEquals(2, calls[0])
        }
    }

    @Test
    fun cacheAndSpacingFollowTheCurrentSettings() = runTest {
        val calls = IntArray(1)
        var pacing = RefreshPacing()
        val client = countingListClient(calls) { pacing }
        val query = RoomQuery(genre = Genres.default)
        client.fetchRoomList(query)
        // 設定を 2 秒に縮めると、次の取得から 2 秒で取り直す。
        pacing = RefreshPacing(minIntervalMs = 1_000, searchHeadMs = 2_000, radarHeadMs = 2_000, waitlistMs = 2_000)
        now += 2_100
        client.fetchRoomList(query)
        assertEquals(2, calls[0])
    }

    @Test
    fun spacingNeverGoesBelowOneSecond() = runTest {
        val gated = ShaloveClient(pacing = { RefreshPacing(minIntervalMs = 100) }, clock = { now }, sleep = { ms -> sleeps += ms; now += ms })
        server.enqueue(MockResponse().setBody("a"))
        server.enqueue(MockResponse().setBody("b"))
        gated.get(server.url("/").toString())
        gated.get(server.url("/").toString())
        assertEquals(listOf(1_000L), sleeps)
    }

    @Test
    fun laterFilteredPagesUseThePagerLinkFromTheSite() = runTest {
        val kanto = Genres["kanto"]!!
        val requested = mutableListOf<String>()
        val client = ShaloveClient(
            http = OkHttpClient.Builder().addInterceptor { chain ->
                requested += chain.request().url.toString()
                val body = if (requested.size == 1) """
                    <a href="https://chat.shalove.net/g/kanto/vwait/1/vsex/2/pageID/2/">次</a>
                    <a href="https://example.com/g/kanto/pageID/3/">別のホスト</a>
                    <a href="https://chat.shalove.net/g/kanto/vsex/1/vwait/1/pageID/3/">別の性別</a>
                    <a href="https://chat.shalove.net/g/talk/pageID/4/">別のジャンル</a>
                    $EMPTY_LIST
                """ else EMPTY_LIST
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("text/html; charset=Shift_JIS".toMediaType())).build()
            }.build(),
            clock = { now },
            sleep = { ms -> now += ms },
        )
        val first = RoomQuery(kanto, sex = Gender.FEMALE, waitingOnly = true)
        client.fetchRoomList(first)
        client.fetchRoomList(first.copy(page = 2))
        // 今の条件と食い違うリンク（別ホスト・別の性別・別ジャンル）は使わず、自前で組み立てる。
        client.fetchRoomList(first.copy(page = 3))
        assertEquals(
            listOf(
                "https://chat.shalove.net/g/kanto/?vsex=2&vwait=1",
                "https://chat.shalove.net/g/kanto/vwait/1/vsex/2/pageID/2/",
                "https://chat.shalove.net/g/kanto/vsex/2/vwait/1/pageID/3/",
            ),
            requested,
        )
    }
}

/** 部屋が 1 件も無いジャンルの一覧（見出しの数が 0）。 */
private const val EMPTY_LIST = """<html><body><nobr><span class="wait">待機中</span> 0</nobr><nobr><span class="fill">満室</span> 0</nobr></body></html>"""
