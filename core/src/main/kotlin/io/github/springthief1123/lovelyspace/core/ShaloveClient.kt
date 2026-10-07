package io.github.springthief1123.lovelyspace.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.github.springthief1123.lovelyspace.core.chat.ChatPage
import io.github.springthief1123.lovelyspace.core.chat.ChatPageParser
import io.github.springthief1123.lovelyspace.core.chat.ChatRoomRef
import io.github.springthief1123.lovelyspace.core.chat.ChatSession
import io.github.springthief1123.lovelyspace.core.chat.EntryForm
import io.github.springthief1123.lovelyspace.core.chat.EntryFormParser
import io.github.springthief1123.lovelyspace.core.chat.EntryProfile
import io.github.springthief1123.lovelyspace.core.chat.EntryResult
import okhttp3.Cookie
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 本家サイトへのすべての通信をここに集約する。
 *
 * 規約で「通常のブラウザ利用とは異なるアクセスを繰り返す」ツールアクセスが禁止されているため、
 * - リクエスト同士の間隔を [minInterval] 以上空ける
 * - 同じ一覧 URL は [listCacheTtl] の間は再取得しない
 * をここで強制する。
 *
 * 入室後のチャット（[ChatSession]）は本家のページと同じ規則（新着取得は常に 1 本・間隔は本家の計算式・
 * 発言は 1.5 秒以上空ける）で通信し、ページ操作用の最小間隔とは別に管理する。
 */
class ShaloveClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val minInterval: Duration = 3.seconds,
    private val listCacheTtl: Duration = 20.seconds,
    /** 経過時間の計測用（ミリ秒）。端末の時計合わせの影響を受けないよう単調増加の時計を使う。 */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val gate = Mutex()
    private val listGate = Mutex()
    private var lastRequestAt: Long? = null
    private class CachedList(val page: RoomListPage, val fetchedAt: Long, val generation: Long)

    /** [listGate] の外からも読むので同期付きにする。書き込みは [listGate] の中だけ。 */
    private val listCache: MutableMap<String, CachedList> = java.util.Collections.synchronizedMap(mutableMapOf())
    private var generation = 0L

    /**
     * 一覧を取得する。キャッシュの確認から保存までを [listGate] の中で行うので、
     * 同じ URL を同時に呼んでも本家へのアクセスは 1 回になる。
     * [forceRefresh] でも、呼び出した時点より後に別の呼び出しが取得し終えていればその結果を使う。
     */
    suspend fun fetchRoomList(query: RoomQuery, forceRefresh: Boolean = false): RoomListPage {
        val url = query.toUrl()
        val seen = listCache[url]?.generation
        return listGate.withLock {
            listCache[url]?.let { entry ->
                val fresh = clock() - entry.fetchedAt < listCacheTtl.inWholeMilliseconds
                if ((!forceRefresh && fresh) || entry.generation != seen) return@withLock entry.page
            }
            val html = get(url)
            val page = RoomListParser.parse(html, query.genre.key, query.page, url)
            listCache[url] = CachedList(page, clock(), ++generation)
            page
        }
    }

    /** レート制限付きの GET。レスポンス本文をサイトの文字コードで文字列にして返す。 */
    suspend fun get(url: String): String = gated {
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).awaitBody()
    }

    /** ヘッダー待ち・本文読み取り中のどちらでも、画面側の取消をHTTP通信へ伝える。 */
    private suspend fun Call.awaitBody(): String = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    val result = runCatching {
                        if (!res.isSuccessful) throw HttpStatusException(res.code, res.request.url.toString())
                        decodeBody(res)
                    }
                    result.fold({ if (cont.isActive) cont.resume(it) }, { if (cont.isActive) cont.resumeWithException(it) })
                }
            }
        })
    }

    /** ページの読み込みやフォーム送信など、人の操作 1 回に当たる通信。前の通信から [minInterval] 以上空ける。 */
    private suspend fun <T> gated(block: suspend () -> T): T = gate.withLock {
        lastRequestAt?.let { last ->
            val wait = last + minInterval.inWholeMilliseconds - clock()
            if (wait > 0) sleep(wait)
        }
        lastRequestAt = clock()
        withContext(Dispatchers.IO) { block() }
    }

    // ---- 入室・チャット ----

    /** 入室前画面を開く。部屋が埋まった・閉じられた場合など、フォームが無ければ null。 */
    suspend fun openEntry(host: String, roomId: Long, genreKey: String): EntryForm? {
        val url = "https://$host/PreEnterRoom?room_id=$roomId&genre_key=$genreKey"
        return gated {
            http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { res ->
                if (!res.isSuccessful) throw HttpStatusException(res.code, url)
                val bytes = res.body?.bytes() ?: ByteArray(0)
                val charset = detectCharset(res.header("Content-Type"), bytes)
                EntryFormParser.parse(String(bytes, charset), host, url)?.copy(formCharset = charset.name())
            }
        }
    }

    /**
     * 入室する。成功するとサイトが本人用の pwd を付けた `2shot.php` へ転送するので、その URL から部屋を特定する。
     * [captchaToken] はロボット除け認証が求められたとき（[EntryForm.requiresCaptcha]）に WebView で得た値。
     */
    suspend fun enter(form: EntryForm, profile: EntryProfile, captchaToken: String? = null): EntryResult {
        val charset = runCatching { Charset.forName(form.formCharset) }.getOrDefault(Charsets.UTF_8)
        val body = FormBody.Builder(charset)
            .add("room_id", form.roomId.toString())
            .add("pwd", form.pwd)
            .add("genre_key", form.genreKey)
            .add("shotact", "entry")
            .add("name", profile.name)
            .add("sex", profile.sex.toString())
            .add("years", profile.years?.toString().orEmpty())
            .apply {
                if (captchaToken != null) {
                    // どの認証が出たかでフィールド名が違うので、すべてに入れる（ブラウザも該当欄だけを送る）。
                    add("cf-turnstile-response", captchaToken)
                    add("h-captcha-response", captchaToken)
                    add("g-recaptcha-response", captchaToken)
                }
            }
            .build()
        val url = "https://${form.host}/PreEnterRoom"
        return gated {
            val request = Request.Builder().url(url).post(body)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "$url?room_id=${form.roomId}&genre_key=${form.genreKey}")
                .build()
            noRedirectHttp.newCall(request).execute().use { res ->
                val location = res.header("Location")
                if (res.isRedirect && location != null) {
                    val target = res.request.url.resolve(location)
                    val pwd = target?.queryParameter("pwd")
                    val roomId = target?.queryParameter("room_id")?.toLongOrNull()
                    if (target != null && target.encodedPath.endsWith("/2shot.php") && pwd != null && roomId != null) {
                        return@use EntryResult.Entered(ChatRoomRef(target.host, roomId, pwd, form.genreKey))
                    }
                    return@use EntryResult.Rejected("入室できませんでした")
                }
                if (!res.isSuccessful) throw HttpStatusException(res.code, url)
                EntryResult.Rejected(EntryFormParser.errorMessage(decodeBody(res)))
            }
        }
    }

    /** 待機画面・チャット画面を開き、初期状態（直近のログ・読み出し位置など）を得る。 */
    suspend fun openChat(room: ChatRoomRef): ChatPage = ChatPageParser.parse(get(room.pageUrl), room)

    /** 公開閲覧は入室・発言・退室を送信しない。ページ通信と同じ制限を共有する。 */
    suspend fun openPublicRoom(host: String, genreKey: String, roomId: Long): io.github.springthief1123.lovelyspace.core.chat.PublicRoomPage {
        val url = SitePages.publicRoom(host, genreKey, roomId)
        return io.github.springthief1123.lovelyspace.core.chat.PublicRoomParser.parse(get(url), url)
    }

    /**
     * 開いた部屋の新着取得・発言用のセッションを作る。
     * [previous] が同じ部屋なら、ページから読み出し位置を取り直しても発言・poll の間隔制限を引き継ぐ。
     */
    fun chatSession(page: ChatPage, previous: ChatSession? = null): ChatSession =
        previous
            ?.takeIf { it.room == page.room }
            ?.resynchronized(page.state)
            ?: ChatSession(longPollHttp, page.room, page.state, clock, sleep)

    /** 退室する（作成者は [close] で部屋ごと閉じる）。 */
    suspend fun leave(room: ChatRoomRef) {
        postRoomForm(room, "shotact" to "bye")
    }

    /** 部屋を閉鎖する（作成者のみ）。 */
    suspend fun close(room: ChatRoomRef) {
        postRoomForm(room, "shotact" to "close")
    }

    // ---- 作成者の操作 ----
    // 本家ではどれも 2shot.php へのフォーム送信で、応答は送信後の部屋の画面。
    // 応答が部屋の画面なら読んで返す（操作が反映されたかの確認に使う）。転送や部屋以外の画面なら null。

    /** 相手を退室させる（本家の「相手を退室」）。 */
    suspend fun banGuest(room: ChatRoomRef): ChatPage? = postRoomForm(room, "shotact" to "ban").asRoomPage(room)

    /** 発言をクリアする（本家の「発言クリア」）。 */
    suspend fun clearLog(room: ChatRoomRef): ChatPage? = postRoomForm(room, "clearchatlog" to "1").asRoomPage(room)

    /** 待機メッセージを変更する。本家のフォームは空の `chat` を一緒に送る（空発言は記録されない）。 */
    suspend fun changeWaitingMessage(room: ChatRoomRef, message: String): ChatPage? =
        postRoomForm(room, "chat" to "", "message" to message).asRoomPage(room)

    /**
     * 公開・非公開を切り替える。本家は非公開にするには参加者全員の年齢確認が必要で、
     * 条件を満たさないと変わらないことがあるので、戻り値の [ChatPage.isPublic] で確かめる。
     */
    suspend fun setPublic(room: ChatRoomRef, public: Boolean): ChatPage? =
        postRoomForm(room, "set_is_public" to if (public) "1" else "0").asRoomPage(room)

    private fun String?.asRoomPage(room: ChatRoomRef): ChatPage? =
        this?.let { runCatching { ChatPageParser.parse(it, room) }.getOrNull() }

    /**
     * `2shot.php` へ部屋の hidden 項目と [fields] を送る。成功した応答の本文を返す（転送なら null）。
     * `2shot.php` は UTF-8 のページなので、フォームも UTF-8 で送る。
     */
    private suspend fun postRoomForm(room: ChatRoomRef, vararg fields: Pair<String, String>): String? {
        val url = "https://${room.host}/2shot.php"
        val body = FormBody.Builder(Charsets.UTF_8)
            .add("room_id", room.roomId.toString())
            .add("pwd", room.pwd)
            .add("genre_key", room.genreKey)
            .apply { fields.forEach { (name, value) -> add(name, value) } }
            .build()
        return gated {
            val request = Request.Builder().url(url).post(body)
                .header("User-Agent", USER_AGENT)
                .header("Referer", room.pageUrl)
                .build()
            noRedirectHttp.newCall(request).execute().use { res ->
                if (res.isRedirect) return@use null
                if (!res.isSuccessful) throw HttpStatusException(res.code, url)
                decodeBody(res)
            }
        }
    }

    private val noRedirectHttp: OkHttpClient by lazy {
        http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    /** `ajax.php?live=1` はサーバーが新着まで応答を保留するので、読み取りの待ち時間を長く取る。 */
    private val longPollHttp: OkHttpClient by lazy {
        http.newBuilder().readTimeout(LONG_POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    }

    companion object {
        /** live 取得の応答待ちの上限。超えたら取り直す。 */
        const val LONG_POLL_TIMEOUT_SECONDS = 120L

        /**
         * パーサが PC 版レイアウトを前提にしているため、PC の Chrome として振る舞う。
         * スマホ版レイアウトの解析に対応したら変更する。
         */
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        fun defaultHttpClient(cookieJar: CookieJar = InMemoryCookieJar()): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .apply { if (cookieJar is SharedSiteCookieJar) addNetworkInterceptor(cookieJar) }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        internal fun decodeBody(res: Response): String {
            val bytes = res.body?.bytes() ?: return ""
            return String(bytes, detectCharset(res.header("Content-Type"), bytes))
        }

        /** Content-Type → meta charset → Shift_JIS(Windows-31J) の順に判定する。 */
        internal fun detectCharset(contentType: String?, bytes: ByteArray): Charset {
            val name = CHARSET.find(contentType.orEmpty())?.groupValues?.get(1)
                ?: META_CHARSET.find(String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1))?.groupValues?.get(1)
            return when (name?.lowercase()) {
                null, "shift_jis", "shift-jis", "sjis", "x-sjis", "windows-31j", "cp932" -> SITE_CHARSET
                else -> runCatching { Charset.forName(name) }.getOrDefault(SITE_CHARSET)
            }
        }

        private val CHARSET = Regex("""charset=["']?([\w-]+)""", RegexOption.IGNORE_CASE)
        private val META_CHARSET = Regex("""<meta[^>]+charset=["']?([\w-]+)""", RegexOption.IGNORE_CASE)
    }
}

/** [url] はログに残っても困らないよう、部屋の pwd を伏せた形で持つ。 */
class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code: ${redact(url)}") {
    val url: String = redact(url)

    companion object {
        private val PWD = Regex("""([?&]pwd=)[^&#]*""")

        internal fun redact(url: String): String = PWD.replace(url, "$1***")
    }
}

/**
 * サブドメイン（chat. / 2shot.chat. / lr.chat.）間で共有されるメモリ上の Cookie。
 * JVM のテストや Android 以外の呼び出し用。Android は SharedSiteCookieJar を使う。
 */
class InMemoryCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) {
            this.cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            this.cookies += c
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        this.cookies.removeAll { it.expiresAt < now }
        return this.cookies.filter { it.matches(url) }
    }
}
