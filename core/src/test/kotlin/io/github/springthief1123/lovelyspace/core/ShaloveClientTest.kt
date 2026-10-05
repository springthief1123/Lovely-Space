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
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

class ShaloveClientTest {
    private val server = MockWebServer()
    private var now = 1_000_000L
    private val sleeps = mutableListOf<Long>()

    private val client = ShaloveClient(
        minInterval = 3.seconds,
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
    fun noWaitWhenEnoughTimeHasPassed() = runTest {
        repeat(2) { server.enqueue(MockResponse().setBody("ok")) }
        client.get(server.url("/a").toString())
        now += 10_000
        client.get(server.url("/b").toString())
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun cancellingListBeforeHeadersCancelsHttpAndAllowsNextGenre() {
        assertListCancellation(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE), waitForHeaders = false)
    }

    @Test
    fun cancellingListWhileReadingBodyCancelsHttpAndAllowsNextGenre() {
        assertListCancellation(MockResponse().setBody("<html></html>").setBodyDelay(30, TimeUnit.SECONDS), waitForHeaders = true)
    }

    /** 実際のOkHttp Callとローカルサーバーで、取消・一覧ロック・キャッシュ・間隔をまとめて確認。 */
    private fun assertListCancellation(firstResponse: MockResponse, waitForHeaders: Boolean) = runBlocking {
        val calls = CopyOnWriteArrayList<Call>()
        val headers = CompletableDeferred<Unit>()
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                // 本家には通信せず、同じCallの接続先だけを合成データのローカルサーバーへ向ける。
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) { calls += call }
                override fun responseHeadersEnd(call: Call, response: Response) { headers.complete(Unit) }
            })
            .build()
        val client = ShaloveClient(http = http, clock = { now }, sleep = { ms -> sleeps += ms; now += ms })
        server.enqueue(firstResponse)
        repeat(2) { server.enqueue(MockResponse().setBody("<html></html>")) }
        val original = RoomQuery(Genres.default)
        val obsolete = async { client.fetchRoomList(original) }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
        if (waitForHeaders) withTimeout(5_000) { headers.await() }
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
    private fun countingListClient(calls: IntArray) = ShaloveClient(
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
    fun listCacheExpiresAfterTtl() = runTest {
        val calls = IntArray(1)
        val client = countingListClient(calls)
        val query = RoomQuery(genre = Genres.default)
        client.fetchRoomList(query)
        now += 19_000
        client.fetchRoomList(query)
        assertEquals(1, calls[0])
        now += 2_000
        client.fetchRoomList(query)
        assertEquals(2, calls[0])
    }
}
