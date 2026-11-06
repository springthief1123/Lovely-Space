package io.github.springthief1123.lovelyspace.ui.chat

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatMessageTextTest {
    @Test fun multipleWebLinksKeepTextNewlinesQueriesAndFragments() {
        val text = "こちら https://example.com/path?q=1&x=2#part\n次は http://example.org/test"
        val linked = linkifiedChatText(text, Color.Red)
        assertEquals(text, linked.text)
        val links = linked.getLinkAnnotations(0, linked.length)
        assertEquals(listOf("https://example.com/path?q=1&x=2#part", "http://example.org/test"),
            links.map { (it.item as LinkAnnotation.Url).url })
        links.forEach { assertEquals((it.item as LinkAnnotation.Url).url, text.substring(it.start, it.end)) }
    }

    @Test fun ordinaryTextAndNonWebSchemesRemainPlain() {
        val text = "普通の文\njavascript:alert(1) file:///tmp/test content://media/1"
        val linked = linkifiedChatText(text, Color.Red)
        assertEquals(text, linked.text)
        assertTrue(linked.getLinkAnnotations(0, linked.length).isEmpty())
    }
}
