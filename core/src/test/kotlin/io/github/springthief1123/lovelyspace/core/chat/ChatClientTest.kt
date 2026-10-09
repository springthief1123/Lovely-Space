package io.github.springthief1123.lovelyspace.core.chat

import io.github.springthief1123.lovelyspace.core.HttpStatusException
import io.github.springthief1123.lovelyspace.core.ShaloveClient
import io.github.springthief1123.lovelyspace.core.fixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
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
        pacing = { io.github.springthief1123.lovelyspace.core.RefreshPacing(minIntervalMs = 3_000) },
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
        assertEquals("tok", fields["h-captcha-response"])
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
    fun resyncPreservesMinimumSendInterval() = runTest {
        respond(fixture("chat/ajax_send.txt"))
        respond(fixture("chat/ajax_send.txt"))
        val first = client.chatSession(page(fromSize = 690))

        first.send("1回目")
        val resynced = client.chatSession(page(fromSize = 726), previous = first)
        resynced.send("2回目")

        assertEquals("再同期しても連続発言は 1.5 秒空ける", listOf(ChatSession.MIN_SEND_INTERVAL_MS), sleeps)
    }

    @Test
    fun pollWaitsForIntervalAfterSend() = runTest {
        respond(fixture("chat/ajax_send.txt"))
        respond(fixture("chat/ajax_no_new.txt"))
        val session = client.chatSession(page(fromSize = 690))

        // 取得の後の sleep 中に発言した場合と同じ状況: 発言が先に済んでから次の取得に入る。
        session.send("こんばんは")
        val sentAt = now
        assertEquals("発言後の間隔が空くまでは取りに行かない", null, session.poll())
        assertEquals(1, requests.size)

        session.updates().first()

        assertEquals(2, requests.size)
        assertEquals("1", requests[1].first.url.queryParameter("live"))
        assertEquals("726", requests[1].first.url.queryParameter("fromsize"))
        // 発言の応答（2 人そろって新着あり）なので、本家と同じく 2 秒空けてから取る。
        assertEquals(listOf(2_000L), sleeps)
        assertTrue(now >= sentAt + 2_000)
    }

    @Test
    fun firstUpdateDoesNotSleepBeforePolling() = runTest {
        // 実際の単調時計は原点が任意。正・負のどちらでも、初回には待機期限がない。
        for (initialClock in listOf(1_000_000L, -1_000_000L)) {
            now = initialClock
            respond(fixture("chat/ajax_cleared_and_ended.txt"))
            val session = client.chatSession(page(fromSize = 540))
            session.updates().first()
            assertTrue("初回取得前にsleepしてはいけない: $initialClock / $sleeps", sleeps.isEmpty())
            assertEquals("sleepで時計が変化してはいけない", initialClock, now)
        }
        assertEquals(2, requests.size)
    }

    @Test
    fun updatesCanOnlyBeCollectedOnce() = runTest {
        respond(fixture("chat/ajax_cleared_and_ended.txt"))
        val session = client.chatSession(page(fromSize = 540))
        val received = CompletableDeferred<Unit>()
        val first = launch { session.updates().collect { received.complete(Unit); awaitCancellation() } }
        // 通信は OkHttp のスレッドで返るので、実時間で最初の応答を待つ。
        withContext(Dispatchers.Default) { withTimeout(5_000) { received.await() } }
        val error = runCatching { session.updates().collect {} }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        first.cancel()
        assertEquals("取得は 1 本だけ", 1, requests.size)
    }

    @Test
    fun httpErrorDoesNotLeakPwd() = runTest {
        responses += { code(500).message("Server Error") }
        val session = client.chatSession(page(fromSize = 540))
        val error = runCatching { session.poll() }.exceptionOrNull() as HttpStatusException
        assertEquals(500, error.code)
        assertTrue(room.pwd !in error.url)
        assertTrue(room.pwd !in error.message.orEmpty())
        assertTrue("pwd=***" in error.url)
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

    private fun roomForm(vararg fields: Pair<String, String>) =
        mapOf("room_id" to "900000001", "pwd" to room.pwd, "genre_key" to "chah") + fields

    @Test
    fun banGuestPostsBan() = runTest {
        respond("", "text/html")
        client.banGuest(room)
        val (req, body) = requests.single()
        assertEquals("POST", req.method)
        assertEquals("/2shot.php", req.url.encodedPath)
        assertEquals(roomForm("shotact" to "ban"), form(body))
    }

    @Test
    fun clearLogPostsClearChatLog() = runTest {
        respond("", "text/html")
        client.clearLog(room)
        assertEquals(roomForm("clearchatlog" to "1"), form(requests.single().second))
    }

    @Test
    fun changeWaitingMessagePostsUtf8WithEmptyChat() = runTest {
        respond(fixture("chat/chat_page_owner.html"), "text/html; charset=UTF-8")
        val page = client.changeWaitingMessage(room, "ゆっくり\nお話ししましょう")
        assertEquals(roomForm("chat" to "", "message" to "ゆっくり\nお話ししましょう"), form(requests.single().second))
        assertTrue("本文は UTF-8 でエンコードする", "%E3%82%86" in requests.single().second)
        // 応答の部屋の画面から、反映後の待機メッセージを読める。
        assertEquals("ゆっくり\nお話ししましょう", page?.waitingMessage)
    }

    @Test
    fun setPublicPostsFlag() = runTest {
        respond("", "text/html")
        respond("", "text/html")
        client.setPublic(room, public = false)
        client.setPublic(room, public = true)
        assertEquals(roomForm("set_is_public" to "0"), form(requests[0].second))
        assertEquals(roomForm("set_is_public" to "1"), form(requests[1].second))
        assertEquals("ページ操作の間隔を空ける", listOf(3_000L), sleeps)
    }

    @Test
    fun ownerActionReturnsNullForNonRoomPageOrRedirect() = runTest {
        respond("<html><body>エラー</body></html>", "text/html")
        responses += { code(302).message("Found").header("Location", "/2shot.php?room_id=900000001") }
        assertEquals(null, client.clearLog(room))
        assertEquals(null, client.banGuest(room))
    }

    @Test
    fun ownerActionErrorDoesNotLeakPwd() = runTest {
        responses += { code(500).message("Server Error") }
        val error = runCatching { client.banGuest(room) }.exceptionOrNull() as HttpStatusException
        assertTrue(room.pwd !in error.message.orEmpty())
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
