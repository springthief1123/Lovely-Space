package io.github.springthief1123.lovelyspace.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreKParserTest {
    private val page = GenreKParser.parse(fixture("genre_k_page.html"), "synthetic", "https://chat.shalove.net/?action=GenreK&genre_key=synthetic")

    @Test fun readsRoomsWithLinksAndSkipsPrivateFullRowsHeadersAndPagers() {
        assertEquals(listOf(900004L, 900003L, 900002L), page.rooms.map { it.id })
        assertEquals(900002L, page.nextCursor)
    }

    @Test fun aPublicFullRoomCarriesTheOwnerName() {
        val room = page.rooms.single { it.id == 900002L }
        assertEquals(RoomStatus.FULL, room.status)
        assertEquals(RoomAction.PEEK, room.action)
        // 名前の中の括弧は残し、末尾の数字の括弧だけを年齢にする。
        assertEquals("合成むらさき(見本)", room.name)
        assertEquals(35, room.age)
        assertEquals(Gender.FEMALE, room.gender)
        assertEquals("北海道", room.area)
        assertEquals("これは公開満室の合成募集文です。", room.message)
        assertTrue(room.isPublic)
    }

    @Test fun waitingRoomsKeepTimeOutOfTheAreaAndAllowMissingAgeAndArea() {
        val waiting = page.rooms.single { it.id == 900004L }
        assertEquals(RoomStatus.WAITING, waiting.status)
        assertEquals("合成あお", waiting.name)
        assertEquals(28, waiting.age)
        assertEquals(Gender.MALE, waiting.gender)
        assertEquals("東京", waiting.area)
        val open = page.rooms.single { it.id == 900003L }
        assertEquals(RoomStatus.PUBLIC_WAITING, open.status)
        assertNull(open.age)
        assertNull(open.area)
        assertEquals("合成みどり", open.name)
    }

    @Test fun theOldFormatWithoutTimeAndTheLastPageWithoutNextAreRead() {
        val html = """<html><body>
            <font color="#ff00cc">満室</font> 1
            <hr><font color="#ff00cc">満室</font> <a href="/PublicRoom?room_id=800001&amp;genre_key=synthetic&amp;back_uri=%2F">覗く</a>
            合成きいろ <font class="female" color="#ff0000">女</font> <br><span class="wrapsoft">合成の 募集文</span>
            <hr><font color="#ff00cc">満室</font> 非公開
            <hr><a class="pagereload" href="/?action=GenreK&amp;genre_key=synthetic">最新更新</a>
            </body></html>"""
        val parsed = GenreKParser.parse(html, "synthetic")
        val room = parsed.rooms.single()
        assertEquals("合成きいろ", room.name)
        assertNull(room.age)
        assertNull(room.area)
        assertEquals("合成の 募集文", room.message)
        assertNull(parsed.nextCursor)
    }

    @Test fun aPageThatIsNotAListHasNoRooms() {
        val parsed = GenreKParser.parse("<html><body><p>しばらくお待ちください</p></body></html>", "synthetic")
        assertTrue(parsed.rooms.isEmpty())
        assertNull(parsed.nextCursor)
    }

    @Test fun theUrlUsesTheCursorOnTheGenreHost() {
        val genre = Genres.all.first()
        assertEquals("https://${genre.host}/?action=GenreK&genre_key=${genre.key}&bfrid=123", genreKUrl(genre, 123))
    }
}
