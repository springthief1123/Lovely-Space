package io.github.springthief1123.lovelyspace.core

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.lang.reflect.Proxy
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SharedSiteCookieJarTest {
    private class Store : SiteCookieStore {
        var header: String? = null
        val writes = mutableListOf<Pair<String, String>>()
        val reads = mutableListOf<String>()
        override fun read(url: String): String? { reads += url; return header }
        override fun write(url: String, setCookie: String) { writes += url to setCookie }
    }

    @Test fun readsCurrentWebViewStateOnEveryRequest() {
        val store = Store()
        val jar = SharedSiteCookieJar(store)
        val url = "https://chat.shalove.net/member/".toHttpUrl()
        store.header = "session=first; token=a=b="
        assertEquals(listOf("first", "a=b="), jar.loadForRequest(url).map { it.value })
        store.header = "session=second"
        assertEquals("second", jar.loadForRequest(url).single().value)
        store.header = null // WebView でログアウト/削除された値を戻さない。
        assertTrue(jar.loadForRequest(url).isEmpty())
        assertEquals(3, store.reads.size)
    }

    @Test fun preservesScopeAndDeletionAttributesWhenWriting() {
        val store = Store()
        val jar = SharedSiteCookieJar(store)
        val url = "https://chat.shalove.net/member/".toHttpUrl()
        val raw = "session=; Max-Age=0; Domain=shalove.net; Path=/member; Secure; HttpOnly; SameSite=Strict; Priority=High"
        respond(jar, url.toString(), listOf(raw))
        assertEquals(url.toString(), store.writes.single().first)
        assertEquals(raw, store.writes.single().second)
        jar.saveFromResponse(url, listOf(Cookie.parse(url, raw)!!))
        assertEquals("解析済みCookieで属性を落とした上書きをしない", 1, store.writes.size)
    }

    @Test fun retainsExtendedAttributesAndSeparateHeadersOnRedirectResponses() {
        val store = Store()
        val jar = SharedSiteCookieJar(store)
        val headers = listOf(
            "one=a; Path=/; Secure; SameSite=None; Partitioned",
            "two=b; Expires=Wed, 21 Oct 2037 07:28:00 GMT; SameSite=Lax; Priority=Low",
        )
        respond(jar, "https://chat.shalove.net/", headers, code = 302)
        respond(jar, "https://lr.chat.shalove.net/", listOf("two=; Max-Age=0; SameSite=Lax"))
        assertEquals(headers + "two=; Max-Age=0; SameSite=Lax", store.writes.map { it.second })
        assertEquals(listOf(jar), ShaloveClient.defaultHttpClient(jar).networkInterceptors)
    }

    @Test fun cookieWriteFailureDoesNotReturnSuccessfulResponse() {
        val jar = SharedSiteCookieJar(object : SiteCookieStore {
            override fun read(url: String): String? = null
            override fun write(url: String, setCookie: String) { throw IOException("合成の保存失敗") }
        })
        try {
            respond(jar, "https://chat.shalove.net/", listOf("one=a"))
            fail("保存失敗はHTTP失敗にする")
        } catch (_: IOException) { }
    }

    @Test fun neverSharesSiteCookiesWithOtherHostsOrCleartext() {
        val store = Store().apply { header = "session=secret" }
        val jar = SharedSiteCookieJar(store)
        for (value in listOf("https://shalove.net.evil.example/", "https://other.example/", "http://chat.shalove.net/")) {
            val url = value.toHttpUrl()
            assertTrue(jar.loadForRequest(url).isEmpty())
            jar.saveFromResponse(url, listOf(Cookie.Builder().name("x").value("y").hostOnlyDomain(url.host).build()))
            respond(jar, value, listOf("x=y; SameSite=None"))
        }
        assertTrue(store.reads.isEmpty())
        assertTrue(store.writes.isEmpty())
    }

    private fun respond(jar: SharedSiteCookieJar, url: String, headers: List<String>, code: Int = 200) {
        val request = Request.Builder().url(url).build()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("合成")
            .body("".toResponseBody()).apply { headers.forEach { addHeader("Set-Cookie", it) } }.build()
        val chain = Proxy.newProxyInstance(Interceptor.Chain::class.java.classLoader, arrayOf(Interceptor.Chain::class.java)) { _, method, _ ->
            when (method.name) {
                "request" -> request
                "proceed" -> response
                else -> error("このテストでは呼ばれない: ${method.name}")
            }
        } as Interceptor.Chain
        jar.intercept(chain).close()
    }
}
