package io.github.springthief1123.lovelyspace.ui.web

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.core.chat.ChatRoomRef
import io.github.springthief1123.lovelyspace.ui.components.QuietNotice
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * ロボット確認の画面。本家のページ（入室前・部屋作成）を [SiteWebView] で開き、入力済みにしたうえで、
 * 既定では本家の確認欄と送信ボタンだけを Quiet Rose の色で出す。確認そのものは本家の欄（Turnstile / hCaptcha）と利用者が行い、
 * アプリは表示を絞るだけで確認の中身には触れない。本家の注意書きやエラーを見たいときは画面全体の表示に切り替えられる。
 */
@Composable
fun RobotCheckView(
    url: String,
    genreKey: String,
    /** 確認欄だけを出しているときの案内（このあと何が起きるか）。 */
    guide: String,
    onLoadScript: String?,
    scriptPath: String,
    onRoomOpened: (ChatRoomRef) -> Unit,
    modifier: Modifier = Modifier,
    onceScript: String? = null,
) {
    var captchaOnly by rememberSaveable { mutableStateOf(true) }
    Column(modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            QuietNotice(
                text = if (captchaOnly) guide else "ラブルームの画面をそのまま表示しています。",
                actionLabel = if (captchaOnly) "ラブルームの画面をすべて表示" else "確認だけを表示",
                onAction = { captchaOnly = !captchaOnly },
            )
        }
        SiteWebView(
            url = url,
            genreKey = genreKey,
            onLoadScript = onLoadScript,
            scriptPath = scriptPath,
            onceScript = onceScript,
            captchaOnlyStyle = captchaOnlyStyle(),
            captchaOnly = captchaOnly,
            onRoomOpened = onRoomOpened,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/** 確認欄だけを出すときのページの色（CSS の値）。 */
data class CaptchaOnlyStyle(
    val background: String,
    val text: String,
    val primary: String,
    val onPrimary: String,
    val disabledContainer: String,
    val disabledContent: String,
)

@Composable
private fun captchaOnlyStyle(): CaptchaOnlyStyle {
    val colors = MaterialTheme.colorScheme
    return CaptchaOnlyStyle(
        background = colors.background.toCss(),
        text = colors.onBackground.toCss(),
        primary = colors.primary.toCss(),
        onPrimary = colors.onPrimary.toCss(),
        // Compose の無効なボタンと同じ透明度。
        disabledContainer = colors.onSurface.copy(alpha = 0.12f).toCss(),
        disabledContent = colors.onSurface.copy(alpha = 0.38f).toCss(),
    )
}

private fun Color.toCss(): String =
    "rgba(${(red * 255).roundToInt()},${(green * 255).roundToInt()},${(blue * 255).roundToInt()},$alpha)"

/**
 * 本家のロボット確認の欄（`#captcha_space`）と送信ボタン（`#submit_captcha`）、エラーらしい文言だけを残し、ほかを隠す JavaScript。
 * [captchaOnly] が false なら隠したものを元に戻す。確認欄の無いページ（確認が要らない入室など）では何もしない。
 *
 * 隠すのは読み込んだ時点にある要素だけで、後から本家の確認が足す要素（hCaptcha の画像選択など）はそのまま出る。
 * 入力欄は `display:none` にするだけなので、入力済みの値はそのまま送信される。
 */
fun captchaOnlyScript(style: CaptchaOnlyStyle, captchaOnly: Boolean): String {
    val css = """
        html.ls-captcha-only [data-ls-hidden] { display: none !important; }
        html.ls-captcha-only, html.ls-captcha-only body { background: ${style.background} !important; color: ${style.text} !important; }
        html.ls-captcha-only body { margin: 0 !important; padding: 24px 16px !important; }
        html.ls-captcha-only [data-ls-keep]:not(#captcha_space):not(#submit_captcha) {
          display: block !important; width: auto !important; margin: 0 !important; padding: 0 !important;
          border: 0 !important; background: transparent !important; box-shadow: none !important;
        }
        html.ls-captcha-only [data-ls-keep] { text-align: center !important; }
        html.ls-captcha-only #captcha_space { margin: 0 0 20px !important; }
        html.ls-captcha-only #captcha_space > .sub, html.ls-captcha-only #captcha_space > br { display: none !important; }
        html.ls-captcha-only #captcha_element > div, html.ls-captcha-only #captcha_element_alt { display: inline-block !important; }
        html.ls-captcha-only #submit_captcha {
          display: block !important; box-sizing: border-box; width: 100%; max-width: 360px; min-height: 48px;
          margin: 0 auto !important; padding: 12px 20px; border: 0; border-radius: 999px; -webkit-appearance: none;
          background: ${style.primary}; color: ${style.onPrimary}; font-size: 16px; font-weight: 500;
        }
        html.ls-captcha-only #submit_captcha:disabled { background: ${style.disabledContainer}; color: ${style.disabledContent}; }
    """.trimIndent()
    return """
        (function(captchaOnly) {
          var root = document.documentElement;
          var space = document.getElementById('captcha_space');
          if (!space || !/challenges\.cloudflare\.com|hcaptcha\.com/.test(root.innerHTML)) return;
          if (!root.hasAttribute('data-ls-captcha-ready')) {
            root.setAttribute('data-ls-captcha-ready', '');
            var keep = [space, document.getElementById('submit_captcha')]
              .concat(Array.prototype.slice.call(document.querySelectorAll('.ex, .err, .error')));
            var kept = new Set();
            keep.forEach(function(e) {
              for (var n = e; n && n !== document.body && n !== root; n = n.parentElement) kept.add(n);
            });
            kept.forEach(function(n) {
              n.setAttribute('data-ls-keep', '');
              var p = n.parentElement;
              if (!p) return;
              Array.prototype.slice.call(p.childNodes).forEach(function(c) {
                if (c.nodeType === Node.TEXT_NODE) {
                  // 「[」「]」など要素の外にある文字は、包んでから隠す。
                  if (!c.data.trim()) return;
                  var w = document.createElement('span');
                  p.replaceChild(w, c);
                  w.appendChild(c);
                  c = w;
                }
                if (c.nodeType !== Node.ELEMENT_NODE) return;
                if (kept.has(c) || /^(SCRIPT|STYLE|LINK|NOSCRIPT)$/.test(c.tagName)) return;
                if (c.querySelector('iframe[src*="hcaptcha.com"], iframe[src*="challenges.cloudflare.com"]')) return;
                c.setAttribute('data-ls-hidden', '');
              });
            });
            var s = document.createElement('style');
            s.textContent = ${JSONObject.quote(css)};
            (document.head || root).appendChild(s);
          }
          root.classList.toggle('ls-captcha-only', captchaOnly);
          if (captchaOnly) window.scrollTo(0, 0);
        })($captchaOnly);
    """.trimIndent()
}
