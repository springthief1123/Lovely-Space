package io.github.springthief1123.lovelyspace.ui.web

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.springthief1123.lovelyspace.core.chat.ChatRoomRef
import org.json.JSONObject

/**
 * 本家のページをアプリ内で表示する。ロボット確認が必要なフォーム（入室・部屋作成）や、まだ解析していない画面に使う。
 * 確認は利用者と本家のページが行う（認証を自動で通すことはしない）。送信ボタンは利用者が押すか、入室で設定が有効なら
 * 本家のページがボタンを押せるようにした後で [onceScript] が押す（Yuya の決定、2026-10-18）。
 *
 * [onRoomOpened] を渡すと、サイトが `2shot.php?room_id=..&pwd=..`（入室・部屋作成の完了）へ移るところで止め、
 * アプリのチャット画面へ引き継ぐ。ラブルーム以外へのリンクは端末のブラウザで開く。
 */
// 先読み中に触れさせないための OnTouchListener は、押す操作を持たないので performClick を呼ばない。
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
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
    /**
     * [scriptPath] のページを最初に読み終えたときだけ、[onLoadScript] の後に実行する JavaScript（入室の自動送信）。
     * 送信して本家が同じページを返した（入室できなかった）ときに、送り直しを繰り返さないよう 1 回にする。
     * [shown] が false の間は実行せず、表示されたときに実行する。
     */
    onceScript: String? = null,
    /**
     * false の間は、ページを読み込むが見せない（ロボット確認の先読み）。触れず、読み上げにも出さず、[onceScript] も実行しない。
     * true になったら最新の [onLoadScript] で入力し直してから [onceScript] を実行する。
     */
    shown: Boolean = true,
    /**
     * 渡すと、[scriptPath] のページでロボット確認の欄と送信ボタンだけを残して本家のほかの部分を隠す（[captchaOnlyScript]）。
     * [captchaOnly] が false の間は本家の画面をそのまま出す。
     */
    captchaOnlyStyle: CaptchaOnlyStyle? = null,
    captchaOnly: Boolean = false,
    onRoomOpened: ((ChatRoomRef) -> Unit)? = null,
) {
    require(Uri.parse(url).isShalove()) { "Only HTTPS shalove.net pages can be opened in SiteWebView" }
    val currentOnRoomOpened = rememberUpdatedState(onRoomOpened)
    val currentScript = rememberUpdatedState(onLoadScript)
    val currentOnceScript = rememberUpdatedState(onceScript)
    val currentCaptchaOnly = rememberUpdatedState(captchaOnly)
    val currentShown = rememberUpdatedState(shown)
    val page = remember { PageProgress() }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // 本家のフォームとロボット確認には JavaScript が必要だが、端末内ファイルへのアクセスは不要。
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
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
                        val uri = url?.let(Uri::parse) ?: return
                        val path = scriptPath ?: return
                        if (!uri.isShalove() || uri.path?.endsWith(path) != true) return
                        currentScript.value?.let { view.evaluateJavascript(it, null) }
                        captchaOnlyStyle?.let { view.evaluateJavascript(captchaOnlyScript(it, currentCaptchaOnly.value), null) }
                        page.ready = true
                        view.evaluateJavascript(hiddenFlagScript(currentShown.value), null)
                        if (currentShown.value) {
                            page.filledWhileShown = true
                            page.runOnce(view, currentOnceScript.value)
                        }
                    }
                }
                loadUrl(url)
            }
        },
        update = { view ->
            // 先読み中は見せず、触れさせず、読み上げにも出さない。
            view.alpha = if (shown) 1f else 0f
            view.importantForAccessibility =
                if (shown) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            view.setOnTouchListener(if (shown) null else View.OnTouchListener { _, _ -> true })
            view.isFocusable = shown
            view.isFocusableInTouchMode = shown
            val uri = view.url?.let(Uri::parse) ?: return@AndroidView
            if (!uri.isShalove() || scriptPath == null || uri.path?.endsWith(scriptPath) != true) return@AndroidView
            // 表示の切り替え。確認欄の無いページでは何もしない。
            captchaOnlyStyle?.let { view.evaluateJavascript(captchaOnlyScript(it, captchaOnly), null) }
            if (!page.ready) return@AndroidView
            if (!shown) {
                // 隠している間は自動送信を止める（確認が通ってボタンが押せるようになっても押さない）。
                view.evaluateJavascript(hiddenFlagScript(false), null)
                // 次に見せるときに入力し直す（入力画面へ戻って値を変えたときのため）。
                page.filledWhileShown = false
                return@AndroidView
            }
            // 見せるとき。隠していた間に変えた入力を入れ直してから、自動送信を始める（再開する）。
            if (!page.filledWhileShown) {
                page.filledWhileShown = true
                onLoadScript?.let { view.evaluateJavascript(it, null) }
            }
            view.evaluateJavascript(hiddenFlagScript(true), null)
            page.runOnce(view, onceScript)
        },
        onRelease = { it.destroy() },
    )
}

/** 画面を隠しているかをページに伝え、見せたら [autoSubmitScript] の自動送信を確かめ直す JavaScript。 */
private fun hiddenFlagScript(shown: Boolean): String =
    "window.__lsHidden = ${!shown}; if (!window.__lsHidden && window.__lsAutoSubmitPress) window.__lsAutoSubmitPress();"

/** WebView の読み込みの進み具合。先読み（[SiteWebView] の shown = false）から表示へ移るときに使う。 */
private class PageProgress {
    /** [scriptPath] のページを読み終えた。 */
    var ready = false
    /** 表示している間に入力済みにした。 */
    var filledWhileShown = false
    private var onceRan = false

    fun runOnce(view: WebView, script: String?) {
        if (script == null || onceRan) return
        onceRan = true
        view.evaluateJavascript(script, null)
    }
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

/**
 * フォーム [formName] の送信ボタンが押せるようになったら 1 回だけ押す JavaScript。
 * 本家の入室画面の送信ボタン（`#submit_captcha`）は押せない状態で始まり、ロボット確認が通る・本家のスクリプトが一定時間で
 * 押せるようにする、のどちらかで押せるようになる。その変化を待って押すだけで、確認そのものには触れない。
 */
fun autoSubmitScript(formName: String): String = """
    (function() {
      var f = document.forms[${JSONObject.quote(formName)}];
      if (!f) return;
      var b = f.querySelector('#submit_captcha') || f.querySelector('input[type=submit],button[type=submit]');
      if (!b) return;
      var pressed = false;
      var observer = new MutationObserver(press);
      function press() {
        // 画面を隠している間は押さない。見せたときに SiteWebView が呼び直す。
        if (pressed || b.disabled || window.__lsHidden) return;
        pressed = true;
        observer.disconnect();
        b.click();
      }
      observer.observe(b, { attributes: true, attributeFilter: ['disabled'] });
      window.__lsAutoSubmitPress = press;
      press();
    })();
""".trimIndent()
