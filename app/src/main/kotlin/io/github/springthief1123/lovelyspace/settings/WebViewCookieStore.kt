package io.github.springthief1123.lovelyspace.settings

import android.os.Handler
import android.os.HandlerThread
import android.webkit.CookieManager
import io.github.springthief1123.lovelyspace.core.SiteCookieStore
import java.io.IOException

/** WebView の保管先を唯一の Cookie ソースにする。別の保存コピーを作らない。 */
class WebViewCookieStore : SiteCookieStore {
    private val manager = CookieManager.getInstance().apply { setAcceptCookie(true) }
    private val cookieThread by lazy { HandlerThread("LovelySpace-Cookies").apply { start() } }
    private val handler by lazy { Handler(cookieThread.looper) }

    override fun read(url: String): String? = manager.getCookie(url)

    override fun write(url: String, setCookie: String) {
        // HTTP の IO スレッドから呼ばれる。UI をブロックせず、反映完了を待ってから転送へ進む。
        val result = CookieWriteResult()
        if (!handler.post {
            try {
                manager.setCookie(url, setCookie) { accepted -> result.complete(accepted == true) }
            } catch (e: Exception) {
                result.fail(e)
            }
        }) result.fail(IOException("Cookie保存の処理を開始できませんでした"))
        result.awaitApplied()
    }
}
