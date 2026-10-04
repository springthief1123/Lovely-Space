package io.github.springthief1123.lovelyspace.core.chat

import io.github.springthief1123.lovelyspace.core.HttpStatusException
import io.github.springthief1123.lovelyspace.core.ShaloveClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 入室中の 1 部屋との通信（新着取得と発言）。[ShaloveClient.chatSession] で作る。
 *
 * ブラウザの `2shot.js` と同じ通信をする:
 * - 新着取得は `ajax.php?live=1` を常に 1 本だけ。サーバーは新着が出るまで応答を保留する
 * - 次の取得までの間隔は [PollSchedule]（本家と同じ規則）
 * - 発言は取得中の通信を中断してから `POST ajax.php`。連続発言は 1.5 秒空ける（本家と同じ）
 */
class ChatSession internal constructor(
    private val http: OkHttpClient,
    val room: ChatRoomRef,
    initialState: ChatState,
    private val clock: () -> Long,
    private val sleep: suspend (Long) -> Unit,
) {
    @Volatile
    var state: ChatState = initialState
        private set

    /** 更新間隔。発言と取得の両方から変更するので [lock] の中でだけ触る。 */
    private val schedule = PollSchedule(initialState)

    /** 応答の反映と発言を直列にする。通信の待ち時間中は持たない。 */
    private val lock = Mutex()

    @Volatile
    private var pending: Call? = null
    private var lastSendAt: Long? = null

    /**
     * 新着を取り続ける。部屋が終了した応答を流したら完了する。
     * 失敗（通信エラー）は例外として流すので、呼び出し側で retry するか終了する。
     */
    fun updates(): Flow<ChatUpdate> = flow {
        // flow は集めるたびに別のループになるので、同時に 2 つ集めて取得が 2 本にならないようにする。
        check(collecting.compareAndSet(false, true)) { "updates() は同時に 1 か所でしか集められません" }
        try {
            while (true) {
                // 発言のあとは、発言側で決めた間隔が過ぎるまで取りに行かない。
                // 取得の後の sleep 中に発言があった場合もここで待つ。
                awaitPollAllowed()
                // PollSchedule は発言側からも変更されるので、すべて lock の中で触る。
                lock.withLock { schedule.onPollStarted(state) }
                // null は発言で中断された・取得の直前に発言があった。発言の応答が新着を含むので取り直さずに待つ。
                val update = poll() ?: continue
                emit(update)
                if (update.endMessage != null) return@flow
                sleep(lock.withLock { schedule.onResponse(update) })
            }
        } finally {
            collecting.set(false)
        }
    }

    private val collecting = AtomicBoolean(false)

    /**
     * 次の取得を始めてよい時刻（[clock] の値）。発言の応答（または失敗）のときに、
     * 本家と同じ「発言後の間隔」を足して決める。[lock] の中で読み書きする。
     */
    private var pollNotBefore = Long.MIN_VALUE

    private suspend fun awaitPollAllowed() {
        while (true) {
            // 発言中は lock を持っているので、ここで発言の完了も待つ。
            val wait = lock.withLock { pollNotBefore - clock() }
            if (wait <= 0) return
            sleep(wait)
        }
    }

    /** 取得を 1 本に保つ。 */
    private val pollLock = Mutex()

    /** 1 回だけ新着を取る。発言で中断された、または発言後の間隔がまだ空いていなければ null。 */
    suspend fun poll(live: Boolean = true): ChatUpdate? = pollLock.withLock { pollOnce(live) }

    private suspend fun pollOnce(live: Boolean): ChatUpdate? {
        // 取得の開始は発言と同じ lock の中で行う。発言の POST 中に古い fromSize で取りに行かず、
        // 発言側は登録済みの取得を必ず中断できる。
        val (call, from) = lock.withLock {
            // 発言後の間隔が空いていない（lock を待つ間に発言があったなど）。
            if (clock() < pollNotBefore) return null
            val from = state.fromSize
            val call = http.newCall(ajaxRequest(from, live = live, chat = null))
            pending = call
            call to from
        }
        val body = try {
            call.await()
        } catch (e: IOException) {
            if (call.isCanceled()) return null
            throw e
        } finally {
            if (pending === call) pending = null
        }
        return lock.withLock {
            // 応答待ちの間に発言していたら、その応答に同じ新着が含まれているので捨てる。
            if (state.fromSize != from) null else apply(body)
        }
    }

    /** 発言する。応答には自分の発言を含む新着が入っている。 */
    suspend fun send(text: String): ChatUpdate = lock.withLock {
        lastSendAt?.let { last ->
            val wait = last + MIN_SEND_INTERVAL_MS - clock()
            if (wait > 0) sleep(wait)
        }
        lastSendAt = clock()
        pending?.cancel()
        schedule.onSend()
        try {
            val body = http.newCall(ajaxRequest(state.fromSize, live = false, chat = text)).await()
            apply(body).also { pollNotBefore = clock() + schedule.onResponse(it) }
        } catch (e: Throwable) {
            // 送れなくても、中断した取得は間隔を空けて再開させる。
            pollNotBefore = clock() + schedule.onFailure()
            throw e
        }
    }

    private fun apply(body: String): ChatUpdate {
        val update = ChatUpdateParser.parse(body, state, room.pageUrl)
        state = update.state
        return update
    }

    private fun ajaxRequest(fromSize: Long, live: Boolean, chat: String?): Request {
        val url = buildString {
            append("https://").append(room.host).append("/ajax.php?")
            if (live) append("live=1")
            append("&room_id=").append(room.roomId)
            append("&pwd=").append(room.pwd)
            append("&fromsize=").append(fromSize)
            append("&kct=").append(System.currentTimeMillis())
        }
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", ShaloveClient.USER_AGENT)
            .header("Referer", room.pageUrl)
        if (chat != null) {
            // 本家と同じく UTF-8 の URL エンコードで送る。
            builder.post(FormBody.Builder(Charsets.UTF_8).add("chat", chat).build())
        }
        return builder.build()
    }

    private suspend fun Call.await(): String = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    val result = runCatching {
                        if (!res.isSuccessful) throw HttpStatusException(res.code, res.request.url.toString())
                        res.body?.string().orEmpty()
                    }
                    result.fold({ if (cont.isActive) cont.resume(it) }, { if (cont.isActive) cont.resumeWithException(it) })
                }
            }
        })
    }

    companion object {
        /** 本家の発言間隔の下限（`submitMain` の 1500ms）。 */
        const val MIN_SEND_INTERVAL_MS = 1_500L
    }
}
