package io.github.springthief1123.lovelyspace.settings

import android.os.Handler
import android.os.HandlerThread
import android.webkit.CookieManager
import io.github.springthief1123.lovelyspace.core.SiteCookieStore
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** WebView の保管先を唯一の Cookie ソースにする。別の保存コピーを作らない。 */
class WebViewCookieStore : SiteCookieStore {
    private val manager = CookieManager.getInstance().apply { setAcceptCookie(true) }
    private val cookieThread by lazy { HandlerThread("LovelySpace-Cookies").apply { start() } }
    private val handler by lazy { Handler(cookieThread.looper) }

    override fun read(url: String): String? = manager.getCookie(url)

    override fun write(url: String, setCookie: String) {
        // HTTP の IO スレッドから呼ばれる。UI をブロックせず、反映完了を待ってから転送へ進む。
        val completed = CountDownLatch(1)
        handler.post {
            manager.setCookie(url, setCookie) { completed.countDown() }
        }
        try {
            if (!completed.await(10, TimeUnit.SECONDS)) throw IOException("Cookieの保存がタイムアウトしました")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Cookieの保存が中断されました", e)
        }
    }
}
