package io.github.springthief1123.lovelyspace.core

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/** Android の CookieManager を境界の外に置き、同じ Cookie 保管先を HTTP と WebView で使う。 */
interface SiteCookieStore {
    /** 対象 URL に送ってよい Cookie ヘッダー。domain・path・期限の判定は保管先が行う。 */
    fun read(url: String): String?

    /** Set-Cookie 全体を保存し、反映が終わってから戻る（直後の転送でも同じ状態を使う）。 */
    fun write(url: String, setCookie: String)
}

class SharedSiteCookieJar(private val store: SiteCookieStore) : CookieJar, Interceptor {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        // OkHttpの解析済みCookieにはSameSite等が残らない。元ヘッダーを保存済みなので上書きしない。
    }

    /** network interceptorとして登録し、転送を含めた各応答の元ヘッダーをそのまま保存する。 */
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        try {
            val url = response.request.url
            if (url.isSite()) response.headers("Set-Cookie").forEach { store.write(url.toString(), it) }
            return response
        } catch (e: Exception) {
            response.close()
            throw e
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!url.isSite()) return emptyList()
        // getCookie の値は Set-Cookie ではない。属性を作り直して永続化せず、今回のリクエストだけに使う。
        return store.read(url.toString()).orEmpty().split(';').mapNotNull { part ->
            val pair = part.trim()
            val separator = pair.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            runCatching {
                Cookie.Builder()
                    .name(pair.substring(0, separator).trim())
                    .value(pair.substring(separator + 1).trim())
                    .hostOnlyDomain(url.host)
                    .path("/")
                    .secure()
                    .build()
            }.getOrNull()
        }
    }
}

private fun HttpUrl.isSite(): Boolean =
    isHttps && (host == "shalove.net" || host.endsWith(".shalove.net"))
