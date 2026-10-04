package io.github.springthief1123.lovelyspace.core

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

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
    fun detectsMetaCharset() {
        val bytes = """<html><head><meta charset="utf-8"></head>""".toByteArray()
        assertEquals(Charsets.UTF_8, ShaloveClient.detectCharset("text/html", bytes))
        assertEquals(SITE_CHARSET, ShaloveClient.detectCharset(null, "<html>".toByteArray()))
    }
}
