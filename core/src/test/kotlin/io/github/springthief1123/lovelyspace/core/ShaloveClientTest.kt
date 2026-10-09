package io.github.springthief1123.lovelyspace.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    fun aPageTheUserOpenedGoesBeforeQueuedListFetchesWithTheSameSpacing() = runBlocking {
        repeat(4) { server.enqueue(MockResponse().setBody("ok")) }
        val gated = ShaloveClient(pacing = { RefreshPacing(minIntervalMs = 3_000) }, clock = { now }, sleep = { ms -> sleeps += ms; now += ms; kotlinx.coroutines.delay(100) })
        gated.get(server.url("/a").toString())
        // b が間隔を待っている間に、一覧の取得 d と利用者の操作 c が順番待ちに並ぶ（d が先）。
        val b = async { gated.get(server.url("/b").toString()) }
        kotlinx.coroutines.delay(20)
        val d = async { gated.get(server.url("/d").toString()) }
        kotlinx.coroutines.delay(20)
        val c = async { gated.get(server.url("/c").toString(), operation = true) }
        awaitAll(b, c, d)
        assertEquals(listOf("/a", "/b", "/c", "/d"), (1..4).map { server.takeRequest().path })
        // 順番を入れ替えても、間隔は毎回空けている。
        assertEquals(listOf(3_000L, 3_000L, 3_000L), sleeps)
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
        assertListCancellation(MockResponse().setBody("<html></html>").throttleBody(1, 1, TimeUnit.SECONDS), waitForBody = true)
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
        repeat(2) { server.enqueue(MockResponse().setBody("<html></html>")) }
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
        http = OkHttpClient.Builder().addInterceptor { chain ->
            calls[0]++
            now += 100
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("<html></html>".toResponseBody("text/html; charset=Shift_JIS".toMediaType()))
                .build()
        }.build(),
        pacing = pacing,
        clock = { now },
        sleep = { ms -> now += ms },
    )

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
                """ else "<html></html>"
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
