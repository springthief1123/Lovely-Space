package io.github.springthief1123.lovelyspace.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SitePagesTest {
    @Test
    fun prefectureCodesFollowSite() {
        assertEquals(48, Prefectures.names.size)
        assertEquals("北海道", Prefectures.name(1))
        assertEquals("東京", Prefectures.name(13))
        assertEquals("沖縄", Prefectures.name(47))
        assertEquals("海外", Prefectures.name(48))
        assertNull(Prefectures.name(0))
    }

    @Test
    fun buildsPageUrls() {
        assertEquals("https://chat.shalove.net/member/", SitePages.memberAccount())
        assertEquals(
            "https://chat.shalove.net/?submit=submit_rein2shot&genre_key=zenkoku",
            SitePages.reenterRoom(Genres["zenkoku"]!!),
        )
        assertEquals(
            "https://2shot.chat.shalove.net/?submit=submit_rein2shot&genre_key=chah",
            SitePages.reenterRoom(Genres["chah"]!!),
        )
        assertEquals(
            "https://lr.chat.shalove.net/?submit=submit_rein2shot&genre_key=talk",
            SitePages.reenterRoom(Genres["talk"]!!),
        )
        val genre = Genres["chah"]!!
        assertEquals(
            "https://${genre.host}/PreMakeRoom?genre_key=chah&kct=1759590000",
            SitePages.makeRoom(genre, 1759590000),
        )
        assertEquals(
            "https://2shot.chat.shalove.net/PublicRoom?room_id=5&genre_key=chah",
            SitePages.publicRoom("2shot.chat.shalove.net", "chah", 5),
        )
    }
}
