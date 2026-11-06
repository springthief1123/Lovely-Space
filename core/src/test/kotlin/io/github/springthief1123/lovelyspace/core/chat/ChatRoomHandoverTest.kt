package io.github.springthief1123.lovelyspace.core.chat

import org.junit.Assert.*
import org.junit.Test

class ChatRoomHandoverTest {
    private fun url(host: String = "2shot.chat.shalove.net", id: Long = 900000001, genre: String? = null) =
        "https://$host/2shot.php?room_id=$id&pwd=synthetic" + (genre?.let { "&genre_key=$it" } ?: "")

    @Test fun entryRequiresTheOriginalHostAndRoomId() {
        val policy = ChatRoomHandover.entry("chah", "2shot.chat.shalove.net", 900000001)
        assertNotNull(policy.fromUrl(url()))
        assertNull(policy.fromUrl(url(host = "chat.shalove.net")))
        assertNull(policy.fromUrl(url(id = 900000002)))
        assertNull(policy.fromUrl(url(genre = "imacha")))
    }

    @Test fun creationAcceptsNewIdsOnlyOnTheGenreHost() {
        val policy = ChatRoomHandover.create("chah")
        assertNotNull(policy.fromUrl(url(id = 900000002)))
        assertNull(policy.fromUrl(url(host = "chat.shalove.net")))
    }

    @Test fun reentryRequiresObservedGenreButCanCrossOfficialHosts() {
        val policy = ChatRoomHandover.reentry("chah")
        assertNotNull(policy.fromUrl(url(genre = "chah")))
        assertNotNull(policy.fromUrl(url(host = "chat.shalove.net", genre = "chah")))
        assertNull(policy.fromUrl(url()))
        assertNull(policy.fromUrl(url(genre = "kanto")))
        assertNull(policy.fromUrl(url(host = "unrelated.shalove.net", genre = "chah")))
    }

    @Test fun unsafeOrAmbiguousUrlsNeverBecomeNativeRooms() {
        val policy = ChatRoomHandover.create("chah")
        for (candidate in listOf(
            url().replace("https:", "http:"), url().replace("/2shot.php", "/nested/2shot.php"),
            url().replace("pwd=synthetic", "pwd="), url().replace(".net/", ".net:8443/"),
            url() + "&room_id=900000002", url(genre = "chah") + "&genre_key=imacha", url(id = -1),
        )) assertNull(candidate.substringBefore('?'), policy.fromUrl(candidate))
    }

    @Test fun formHandoverUsesTheSameEntryHostAndIdChecks() {
        val policy = ChatRoomHandover.entry("chah", "2shot.chat.shalove.net", 900000001)
        fun form(id: Long, host: String) = """<form action="https://$host/2shot.php"><input name="room_id" value="$id"><input name="pwd" value="synthetic"><input name="genre_key" value="chah"></form>"""
        val page = "https://2shot.chat.shalove.net/2shot.php"
        assertNotNull(ChatRoomFormParser.parse(form(900000001, "2shot.chat.shalove.net"), page, "chah", policy))
        assertNull(ChatRoomFormParser.parse(form(900000002, "2shot.chat.shalove.net"), page, "chah", policy))
        assertNull(ChatRoomFormParser.parse(form(900000001, "chat.shalove.net"), page, "chah", policy))
    }
}
