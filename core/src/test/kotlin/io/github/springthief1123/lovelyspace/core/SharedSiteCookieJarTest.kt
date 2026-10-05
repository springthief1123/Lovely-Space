package io.github.springthief1123.lovelyspace.core

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
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
        val cookie = Cookie.parse(url, "session=; Max-Age=0; Domain=shalove.net; Path=/member; Secure; HttpOnly")!!
        jar.saveFromResponse(url, listOf(cookie))
        assertEquals(url.toString(), store.writes.single().first)
        assertEquals(cookie.toString(), store.writes.single().second)
        assertTrue(store.writes.single().second.contains("max-age=0"))
        assertTrue(store.writes.single().second.contains("path=/member"))
    }

    @Test fun neverSharesSiteCookiesWithOtherHostsOrCleartext() {
        val store = Store().apply { header = "session=secret" }
        val jar = SharedSiteCookieJar(store)
        for (value in listOf("https://shalove.net.evil.example/", "https://other.example/", "http://chat.shalove.net/")) {
            val url = value.toHttpUrl()
            assertTrue(jar.loadForRequest(url).isEmpty())
            jar.saveFromResponse(url, listOf(Cookie.Builder().name("x").value("y").hostOnlyDomain(url.host).build()))
        }
        assertTrue(store.reads.isEmpty())
        assertTrue(store.writes.isEmpty())
    }
}
