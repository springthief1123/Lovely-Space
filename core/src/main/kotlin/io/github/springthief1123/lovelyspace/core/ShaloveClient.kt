package io.github.springthief1123.lovelyspace.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 本家サイトへのすべての通信をここに集約する。
 *
 * 規約で「通常のブラウザ利用とは異なるアクセスを繰り返す」ツールアクセスが禁止されているため、
 * - リクエスト同士の間隔を [minInterval] 以上空ける
 * - 同じ一覧 URL は [listCacheTtl] の間は再取得しない
 * をここで強制する。
 */
class ShaloveClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val minInterval: Duration = 3.seconds,
    private val listCacheTtl: Duration = 20.seconds,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val gate = Mutex()
    private var lastRequestAt = 0L
    private val listCache: MutableMap<String, Pair<Long, RoomListPage>> =
        java.util.Collections.synchronizedMap(mutableMapOf())

    suspend fun fetchRoomList(query: RoomQuery, forceRefresh: Boolean = false): RoomListPage {
        val url = query.toUrl()
        if (!forceRefresh) {
            cached(url)?.let { return it }
        }
        val html = get(url)
        val page = RoomListParser.parse(html, query.genre.key, query.page, url)
        listCache[url] = clock() to page
        return page
    }

    private fun cached(url: String): RoomListPage? {
        val (at, page) = listCache[url] ?: return null
        return if (clock() - at < listCacheTtl.inWholeMilliseconds) page else null
    }

    /** レート制限付きの GET。レスポンス本文をサイトの文字コードで文字列にして返す。 */
    suspend fun get(url: String): String = gate.withLock {
        val wait = lastRequestAt + minInterval.inWholeMilliseconds - clock()
        if (lastRequestAt != 0L && wait > 0) sleep(wait)
        lastRequestAt = clock()
        withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { res ->
                if (!res.isSuccessful) throw HttpStatusException(res.code, url)
                decodeBody(res)
            }
        }
    }

    companion object {
        /**
         * パーサが PC 版レイアウトを前提にしているため、PC の Chrome として振る舞う。
         * スマホ版レイアウトの解析に対応したら変更する。
         */
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(InMemoryCookieJar())
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

class HttpStatusException(val code: Int, val url: String) : IOException("HTTP $code: $url")

/**
 * サブドメイン（chat. / 2shot.chat. / lr.chat.）間で共有されるメモリ上の Cookie。
 * 永続化と WebView との共有はフェーズ2で行う。
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
