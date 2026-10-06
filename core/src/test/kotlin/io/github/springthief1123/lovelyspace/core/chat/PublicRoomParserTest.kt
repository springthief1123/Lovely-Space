package io.github.springthief1123.lovelyspace.core.chat

import org.junit.Assert.*
import org.junit.Test

class PublicRoomParserTest {
    @Test fun publicLogUsesChatLinesAndDoesNotExposeSiteNavigationOrForms() {
        val html = """<html><title>合成ジャンル - サイト</title><body><nav>サイトの広告</nav>
          <div id="information">公開閲覧中</div><input name="pwd" value="synthetic-token">
          <div id="chatarea"><div class="hello"><table><tr><td>合成利用者</td><td>&gt;</td>
            <td>合成メッセージ<br>２行目<small class="choiusu">(12:34:56)</small></td></tr></table></div></div>
          </body></html>"""
        val page = PublicRoomParser.parse(html, "https://chat.shalove.net/PublicRoom?room_id=900000001")
        assertEquals("合成ジャンル", page.title)
        assertEquals("公開閲覧中", page.information)
        assertEquals("合成メッセージ\n２行目", page.lines.single().text)
        assertEquals("合成利用者", page.lines.single().speaker)
        assertFalse(page.toString().contains("synthetic-token"))
    }
    @Test fun emptyPublicLogIsValid() {
        assertTrue(PublicRoomParser.parse("<div id='chatarea'></div>", "https://chat.shalove.net/PublicRoom").lines.isEmpty())
    }
    @Test(expected = PublicRoomUnavailableException::class) fun closedOrAuthenticationPageIsNotAnEmptyChat() {
        PublicRoomParser.parse("<h1>ログインしてください</h1><form>サイトの本文</form>", "https://chat.shalove.net/PublicRoom")
    }
}
