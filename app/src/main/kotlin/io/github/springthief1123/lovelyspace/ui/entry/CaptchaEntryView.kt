package io.github.springthief1123.lovelyspace.ui.entry

import android.annotation.SuppressLint
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
import io.github.springthief1123.lovelyspace.core.chat.EntryProfile
import org.json.JSONObject

/**
 * ロボット確認が求められたときの入室。本家の入室前画面をそのまま表示し、名前などは入力済みにしておく。
 * 確認と「入室」ボタンは利用者が操作する（認証を自動で通すことはしない）。
 * 入室できるとサイトが `2shot.php?room_id=..&pwd=..` へ転送するので、そこで止めてアプリのチャット画面へ移る。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CaptchaEntryView(
    host: String,
    genreKey: String,
    roomId: Long,
    profile: EntryProfile,
    onEntered: (ChatRoomRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnEntered = rememberUpdatedState(onEntered)
    val url = "https://$host/PreEnterRoom?room_id=$roomId&genre_key=$genreKey"
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    private var done = false

                    private fun intercept(uri: Uri?): Boolean {
                        val room = uri?.toChatRoom(genreKey) ?: return false
                        if (!done) {
                            done = true
                            currentOnEntered.value(room)
                        }
                        return true
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.isForMainFrame && intercept(request.url)

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        // 転送が shouldOverrideUrlLoading を通らない端末向けの保険。
                        if (intercept(url?.let(Uri::parse))) view.stopLoading()
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(prefillScript(profile), null)
                    }
                }
                loadUrl(url)
            }
        },
        onRelease = { it.destroy() },
    )
}

private fun Uri.toChatRoom(genreKey: String): ChatRoomRef? {
    if (scheme != "https" || path?.endsWith("/2shot.php") != true) return null
    val roomId = getQueryParameter("room_id")?.toLongOrNull() ?: return null
    val pwd = getQueryParameter("pwd")?.takeIf { it.isNotEmpty() } ?: return null
    val siteHost = host?.takeIf { it.endsWith(".shalove.net") } ?: return null
    return ChatRoomRef(siteHost, roomId, pwd, genreKey)
}

private fun prefillScript(profile: EntryProfile): String {
    val name = JSONObject.quote(profile.name)
    val sex = JSONObject.quote(profile.sex.toString())
    val years = JSONObject.quote(profile.years?.toString().orEmpty())
    return """
        (function() {
          var f = document.forms['entry'];
          if (!f) return;
          var e = f.elements;
          if (e['name']) e['name'].value = $name;
          var sex = e['sex'];
          if (sex && sex.length) for (var i = 0; i < sex.length; i++) sex[i].checked = sex[i].value === $sex;
          if (e['years']) e['years'].value = $years;
        })();
    """.trimIndent()
}
