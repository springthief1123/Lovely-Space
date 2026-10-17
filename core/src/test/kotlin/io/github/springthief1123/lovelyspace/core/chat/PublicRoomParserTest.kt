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
    @Test fun closedOrAuthenticationPageIsNotAnEmptyChatAndSaysWhatTheSiteReturned() {
        val failure = runCatching {
            PublicRoomParser.parse("<title>合成の確認ページ</title><h1>ログインしてください</h1><form>サイトの本文</form>",
                "https://chat.shalove.net/PublicRoom?room_id=5&genre_key=chah", "https://chat.shalove.net/g/chah/")
        }.exceptionOrNull() as PublicRoomUnavailableException
        assertTrue(failure.page!!.startsWith("「合成の確認ページ」・転送先 /g/chah/・枠 0・フォーム 1"))
        assertTrue(failure.message!!.contains("本家の応答: 「合成の確認ページ」"))
    }
    @Test fun linesOutsideChatareaAreStillRead() {
        val html = """<div class="log"><div class="hello"><table><tr><td>合成利用者</td><td>&gt;</td>
            <td>合成メッセージ<small class="choiusu">(12:34:56)</small></td></tr></table></div></div>"""
        assertEquals("合成メッセージ", PublicRoomParser.parse(html, "https://chat.shalove.net/PublicRoom").lines.single().text)
    }
    @Test fun followsOnlyAFrameOfTheSameRoomOnTheSameHost() {
        val url = "https://chat.shalove.net/PublicRoom?room_id=5&genre_key=chah"
        val frames = """<frameset><frame src="https://ads.example.com/x?room_id=5"><frame src="/PublicRoom?room_id=6&mode=log">
            <frame src="/PublicRoomLog?room_id=5&genre_key=chah"></frameset>"""
        assertEquals("https://chat.shalove.net/PublicRoomLog?room_id=5&genre_key=chah", PublicRoomParser.chatFrameUrl(frames, url, 5))
        assertNull(PublicRoomParser.chatFrameUrl("<div id='chatarea'></div><iframe src='/PublicRoom?room_id=5&x=1'></iframe>", url, 5))
        assertNull(PublicRoomParser.chatFrameUrl("<iframe src='/ad?room_id=7'></iframe>", url, 5))
    }
}
