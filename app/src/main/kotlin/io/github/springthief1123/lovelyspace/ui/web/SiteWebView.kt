package io.github.springthief1123.lovelyspace.ui.web

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.springthief1123.lovelyspace.core.chat.ChatRoomRef
import org.json.JSONObject

/**
 * 本家のページをアプリ内で表示する。ロボット確認が必要なフォーム（入室・部屋作成）や、まだ解析していない画面に使う。
 * 確認と送信ボタンは利用者が操作する（認証を自動で通すことはしない）。
 *
 * [onRoomOpened] を渡すと、サイトが `2shot.php?room_id=..&pwd=..`（入室・部屋作成の完了）へ移るところで止め、
 * アプリのチャット画面へ引き継ぐ。ラブルーム以外へのリンクは端末のブラウザで開く。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SiteWebView(
    url: String,
    genreKey: String,
    modifier: Modifier = Modifier,
    /**
     * ページを読み終えるたびに実行する JavaScript（フォームの入力済み化など）。
     * プロフィールを含むので、ラブルームのうちパスが [scriptPath] で終わるページでだけ実行する。
     */
    onLoadScript: String? = null,
    scriptPath: String? = null,
    onRoomOpened: ((ChatRoomRef) -> Unit)? = null,
) {
    val currentOnRoomOpened = rememberUpdatedState(onRoomOpened)
    val currentScript = rememberUpdatedState(onLoadScript)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    private var done = false

                    /** チャット画面へ引き継いだら true。 */
                    private fun interceptRoom(uri: Uri?): Boolean {
                        val handler = currentOnRoomOpened.value ?: return false
                        val room = uri?.toChatRoom(genreKey) ?: return false
                        if (!done) {
                            done = true
                            handler(room)
                        }
                        return true
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (!request.isForMainFrame) return false
                        if (interceptRoom(request.url)) return true
                        if (!request.url.isShalove()) {
                            try {
                                view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                            } catch (_: ActivityNotFoundException) {
                            }
                            return true
                        }
                        return false
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        // 転送が shouldOverrideUrlLoading を通らない端末向けの保険。
                        if (interceptRoom(url?.let(Uri::parse))) view.stopLoading()
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        val script = currentScript.value ?: return
                        val uri = url?.let(Uri::parse) ?: return
                        val path = scriptPath ?: return
                        if (uri.isShalove() && uri.path?.endsWith(path) == true) view.evaluateJavascript(script, null)
                    }
                }
                loadUrl(url)
            }
        },
        onRelease = { it.destroy() },
    )
}

private fun Uri.isShalove(): Boolean =
    scheme == "https" && (host == "shalove.net" || host?.endsWith(".shalove.net") == true)

private fun Uri.toChatRoom(genreKey: String): ChatRoomRef? {
    if (!isShalove() || path?.endsWith("/2shot.php") != true) return null
    val roomId = getQueryParameter("room_id")?.toLongOrNull() ?: return null
    val pwd = getQueryParameter("pwd")?.takeIf { it.isNotEmpty() } ?: return null
    return ChatRoomRef(host!!, roomId, pwd, genreKey)
}

/**
 * フォーム [formName] の各欄に値を入れる JavaScript。値が null の欄は触らない。
 * ラジオボタン（性別）は value が一致するものを選ぶ。
 */
fun prefillFormScript(formName: String, values: Map<String, String?>): String {
    val assignments = values.entries.filter { it.value != null }.joinToString("\n") { (key, value) ->
        "  set(${JSONObject.quote(key)}, ${JSONObject.quote(value)});"
    }
    return """
        (function() {
          var f = document.forms[${JSONObject.quote(formName)}];
          if (!f) return;
          function set(name, value) {
            var e = f.elements[name];
            if (!e) return;
            if (e.length && e.tagName === undefined) {
              for (var i = 0; i < e.length; i++) e[i].checked = e[i].value === value;
              return;
            }
            e.value = value;
            e.dispatchEvent(new Event('input', { bubbles: true }));
            e.dispatchEvent(new Event('keyup', { bubbles: true }));
            e.dispatchEvent(new Event('change', { bubbles: true }));
          }
        $assignments
        })();
    """.trimIndent()
}
