package io.github.springthief1123.lovelyspace.settings

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Cookieが反映される前に次のHTTP要求を開始しないための完了結果。 */
internal class CookieWriteResult {
    private val done = CountDownLatch(1)
    private var accepted = false
    private var failure: Exception? = null
    fun complete(success: Boolean, persist: () -> Unit = {}) {
        if (success) {
            try { persist() } catch (e: Exception) { fail(e); return }
        }
        accepted = success
        done.countDown()
    }
    fun fail(error: Exception) { failure = error; done.countDown() }
    fun awaitApplied(timeoutMillis: Long = 10_000) {
        try {
            if (!done.await(timeoutMillis, TimeUnit.MILLISECONDS)) throw IOException("Cookieの反映がタイムアウトしました")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Cookieの反映が中断されました", e)
        }
        failure?.let { throw IOException("Cookieを保存できませんでした", it) }
        if (!accepted) throw IOException("WebViewがCookieの保存を拒否しました")
    }
}
