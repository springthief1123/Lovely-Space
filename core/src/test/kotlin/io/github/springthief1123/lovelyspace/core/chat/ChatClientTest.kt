package io.github.springthief1123.lovelyspace.core.chat

import io.github.springthief1123.lovelyspace.core.ShaloveClient
import io.github.springthief1123.lovelyspace.core.fixture
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import kotlin.time.Duration.Companion.seconds

/** 本家へ出ていく通信の形（URL・本文）を、応答を差し替えて確かめる。 */
class ChatClientTest {
    private val host = "2shot.chat.shalove.net"
    private val requests = mutableListOf<Pair<Request, String>>()
    private val responses = ArrayDeque<Response.Builder.() -> Unit>()
    private var now = 1_000_000L
    private val sleeps = mutableListOf<Long>()

    private val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val req = chain.request()
        val body = req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
        requests += req to body
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("".toResponseBody(null))
            .apply(responses.removeFirst())
            .build()
    }).build()

    private val client = ShaloveClient(
        http = http,
        minInterval = 3.seconds,
        clock = { now },
        sleep = { ms -> sleeps += ms; now += ms },
    )

    private val room = ChatRoomRef(host, 900000001, "0123456789abcdef0123456789abcdef", "chah")

    private fun respond(body: String, type: String = "application/xml; charset=UTF-8") {
        responses += { body(body.toResponseBody(type.toMediaType())) }
    }

    private fun form(body: String) = body.split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        k to URLDecoder.decode(v, "UTF-8")
    }

    @Test
    fun entersAndTakesPwdFromRedirect() = runTest {
        respond(fixture("chat/preenter_no_captcha.html"), "text/html; charset=UTF-8")
        responses += {
            code(302).message("Found")
            header("Location", "/2shot.php?room_id=900000002&pwd=aaaabbbbccccddddaaaabbbbccccdddd&redirect=1")
        }
        val entry = client.openEntry(host, 900000002, "chah")!!
        val result = client.enter(entry, EntryProfile("タロウ", sex = 1, years = 30))

        assertEquals(
            EntryResult.Entered(ChatRoomRef(host, 900000002, "aaaabbbbccccddddaaaabbbbccccdddd", "chah")),
            result,
        )
        val (req, body) = requests.last()
        assertEquals("POST", req.method)
        assertEquals("/PreEnterRoom", req.url.encodedPath)
        val fields = form(body)
        assertEquals("entry", fields["shotact"])
        assertEquals("タロウ", fields["name"])
        assertEquals("1", fields["sex"])
        assertEquals("30", fields["years"])
        assertEquals(entry.pwd, fields["pwd"])
        assertTrue("認証が無ければトークンは送らない", "cf-turnstile-response" !in fields)
        // 入室前画面と入室は通常の間隔制限を通る。
        assertEquals(listOf(3_000L), sleeps)
    }

    @Test
    fun rejectedWhenFormComesBack() = runTest {
        respond(fixture("chat/preenter.html"), "text/html; charset=UTF-8")
        respond("<html><body><div class=\"error\">名前を入力してください</div></body></html>", "text/html; charset=UTF-8")
        val entry = client.openEntry(host, 900000002, "chah")!!
        val result = client.enter(entry, EntryProfile("", sex = 1, years = null), captchaToken = "tok")
        assertTrue(result is EntryResult.Rejected)
        val fields = form(requests.last().second)
        assertEquals("tok", fields["cf-turnstile-response"])
        assertEquals("", fields["years"])
    }

    @Test
    fun pollUsesLiveAndCurrentFromSize() = runTest {
        respond(fixture("chat/ajax_live_partner.txt"))
        respond(fixture("chat/ajax_no_new.txt"))
        val session = client.chatSession(page(fromSize = 540))

        session.poll()
        session.poll()

        val (first, second) = requests.map { it.first.url }
        assertEquals("1", first.queryParameter("live"))
        assertEquals("540", first.queryParameter("fromsize"))
        assertEquals(room.pwd, first.queryParameter("pwd"))
        assertEquals("654", second.queryParameter("fromsize"))
        assertEquals(room.pageUrl, requests[0].first.header("Referer"))
        assertEquals(900L, session.state.fromSize)
    }

    @Test
    fun sendPostsUtf8AndAppliesResponse() = runTest {
        respond(fixture("chat/ajax_send.txt"))
        respond(fixture("chat/ajax_send.txt"))
        val session = client.chatSession(page(fromSize = 690))

        val update = session.send("こんばんは & よろしく")
        session.send("2回目")

        val (req, body) = requests.first()
        assertEquals("POST", req.method)
        assertEquals(null, req.url.queryParameter("live"))
        assertEquals("690", req.url.queryParameter("fromsize"))
        assertEquals("こんばんは & よろしく", form(body)["chat"])
        assertEquals(726L, update.state.fromSize)
        assertEquals("タロウ", update.newLines.single().speaker)
        // 連続発言は本家と同じく 1.5 秒空ける。
        assertEquals(listOf(ChatSession.MIN_SEND_INTERVAL_MS), sleeps)
    }

    @Test
    fun leavePostsBye() = runTest {
        respond("", "text/html")
        client.leave(room)
        val (req, body) = requests.single()
        assertEquals("/2shot.php", req.url.encodedPath)
        assertEquals(
            mapOf("room_id" to "900000001", "pwd" to room.pwd, "genre_key" to "chah", "shotact" to "bye"),
            form(body),
        )
    }

    private fun page(fromSize: Long) = ChatPage(
        room = room,
        title = "チャH",
        myName = "タロウ",
        isOwner = false,
        isPublic = true,
        lines = emptyList(),
        state = ChatState(fromSize = fromSize),
        waitingMessage = null,
    )
}
