package io.github.springthief1123.lovelyspace.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomQueryTest {
    private val kanto = Genres["kanto"]!!
    private val chah = Genres["chah"]!!

    @Test
    fun plainGenreUrlUsesGenreHost() {
        assertEquals("https://chat.shalove.net/g/kanto/", RoomQuery(kanto).toUrl())
        assertEquals("https://2shot.chat.shalove.net/g/chah/", RoomQuery(chah).toUrl())
    }

    @Test
    fun firstPageFiltersUseTheSiteFormQuery() {
        val url = RoomQuery(genre = kanto, sex = Gender.FEMALE, waitingOnly = true).toUrl()
        assertEquals("https://chat.shalove.net/g/kanto/?vsex=2&vwait=1", url)
    }

    @Test
    fun plainLaterPageKeepsThePagerPath() {
        assertEquals("https://chat.shalove.net/g/kanto/pageID/3/", RoomQuery(kanto, page = 3).toUrl())
    }

    @Test
    fun pageAndFiltersAreEncoded() {
        val url = RoomQuery(
            genre = kanto,
            sex = Gender.FEMALE,
            prefecture = 13,
            ageBand = "20-29",
            publicOnly = true,
            waitingOnly = true,
            page = 3,
        ).toUrl()
        assertEquals(
            "https://chat.shalove.net/g/kanto/vsex/2/vpref/13/vyears/20-29/vnonpub/2/vwait/1/pageID/3/",
            url,
        )
    }

    @Test
    fun pagerUrlMustCarryTheSameFiltersAndPage() {
        val query = RoomQuery(kanto, sex = Gender.FEMALE, waitingOnly = true, page = 2)
        assertTrue(query.matchesPagerUrl("https://chat.shalove.net/g/kanto/vsex/2/vwait/1/pageID/2/"))
        // 条件の並び順は問わない。
        assertTrue(query.matchesPagerUrl("https://chat.shalove.net/g/kanto/vwait/1/vsex/2/pageID/2/"))
        assertFalse(query.matchesPagerUrl("https://chat.shalove.net/g/kanto/vsex/1/vwait/1/pageID/2/"))
        assertFalse(query.matchesPagerUrl("https://chat.shalove.net/g/kanto/vsex/2/pageID/2/"))
        assertFalse(query.matchesPagerUrl("https://chat.shalove.net/g/kanto/vsex/2/vwait/1/vnonpub/2/pageID/2/"))
        assertFalse(query.matchesPagerUrl("https://chat.shalove.net/g/kanto/vsex/2/vwait/1/pageID/3/"))
        assertFalse(query.matchesPagerUrl("https://chat.shalove.net/g/talk/vsex/2/vwait/1/pageID/2/"))
        assertFalse(query.matchesPagerUrl("https://chat.shalove.net/g/kanto/vsex/2/vsex/2/vwait/1/pageID/2/"))
        assertTrue(RoomQuery(kanto, page = 3).matchesPagerUrl("https://chat.shalove.net/g/kanto/pageID/3/"))
    }

    @Test
    fun searchWordsAreShiftJisEncoded() {
        val url = RoomQuery(genre = kanto, message = "雑談").toUrl()
        // 「雑談」の Shift_JIS は 0x8E47 0x926B
        assertEquals("https://chat.shalove.net/g/kanto/?srchmsg=%8E%47%92%6B", url)
    }

    @Test
    fun blankSearchWordsAreOmitted() {
        assertEquals("https://chat.shalove.net/g/kanto/", RoomQuery(kanto, name = " ", message = "").toUrl())
    }

    @Test
    fun everyGenreHasUniqueKey() {
        assertEquals(Genres.all.size, Genres.all.map { it.key }.toSet().size)
    }
}
