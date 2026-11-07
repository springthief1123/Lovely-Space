package io.github.springthief1123.lovelyspace.ui.chat

import android.text.SpannableString
import android.text.style.URLSpan
import android.text.util.Linkify
import androidx.core.text.util.LinkifyCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

/** 通常・公開ログで共用する本文。選択・コピーを保ったまま、Web URLだけを外部ブラウザで開く。 */
@Composable
internal fun ChatMessageText(text: String) {
    val color = MaterialTheme.colorScheme.primary
    val linked = remember(text, color) { linkifiedChatText(text, color) }
    Text(linked, style = MaterialTheme.typography.bodyLarge)
}

internal fun linkifiedChatText(text: String, color: Color): AnnotatedString {
    val spans = SpannableString(text)
    LinkifyCompat.addLinks(spans, Linkify.WEB_URLS)
    return buildAnnotatedString {
        append(text)
        spans.getSpans(0, spans.length, URLSpan::class.java).forEach { span ->
            val url = span.url
            if (url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)) {
                addLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))),
                    spans.getSpanStart(span), spans.getSpanEnd(span))
            }
        }
    }
}
