package io.github.springthief1123.lovelyspace.core.chat

import io.github.springthief1123.lovelyspace.core.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalTime

class ChatParsersTest {
    private val room = ChatRoomRef("2shot.chat.shalove.net", 900000001, "0123456789abcdef0123456789abcdef", "chah")
    private val initial = ChatState(fromSize = 540)

    @Test
    fun parsesPartnerLineFromLivePoll() {
        val u = ChatUpdateParser.parse(fixture("chat/ajax_live_partner.txt"), initial)
        assertEquals(654L, u.state.fromSize)
        assertEquals(28799, u.state.mugonLimitSeconds)
        assertEquals(28761, u.state.roomLimitSeconds)
        assertTrue(u.state.isFilledRoom)
        assertEquals(368, u.state.loadAverage)
        assertEquals(1, u.newLines.size)
        val line = u.newLines.single()
        assertEquals("ミナ", line.speaker)
        assertTrue(line.speakerIsFemale)
        assertEquals("こんばんは。よろしくお願いします♡", line.text)
        assertEquals(LocalTime.of(23, 7, 11), line.time)
        assertFalse(u.clearLog)
        assertFalse(u.someoneEntered)
        assertNull(u.endMessage)
        assertNull("not_show_rom=1 なら人数は出さない", u.romCount)
        assertEquals(true, u.isPublic)
    }

    @Test
    fun parsesOwnLineWithLineBreak() {
        val u = ChatUpdateParser.parse(fixture("chat/ajax_send.txt"), initial)
        assertEquals(726L, u.state.fromSize)
        val line = u.newLines.single()
        assertEquals("タロウ", line.speaker)
        assertFalse(line.speakerIsFemale)
        assertEquals("こんばんは\nはじめまして", line.text)
    }

    @Test
    fun parsesEntryAlertAndNotice() {
        val u = ChatUpdateParser.parse(fixture("chat/ajax_owner_entered.txt"), initial)
        assertTrue(u.someoneEntered)
        assertTrue(u.guestLeft)
        assertTrue(u.canBanGuest)
        assertEquals(2, u.romCount)
        assertFalse(u.state.isFilledRoom)
        assertEquals("お知らせ: 'テスト'", u.information)
        val notice = u.newLines.single()
        assertTrue(notice.isNotice)
        assertEquals("タロウ(男)さん(Android 一時ID Ab3dE)が入室しましたので、このチャットルームをロックしました。", notice.text)
    }

    @Test
    fun emptyEntryClearsLogAndDieMessageEndsRoom() {
        val u = ChatUpdateParser.parse(fixture("chat/ajax_cleared_and_ended.txt"), initial)
        assertTrue(u.clearLog)
        // 空要素より前の行は消される側なので返さない。
        assertEquals(listOf("ルーム作成者（待機者）によって発言がクリアされました。"), u.newLines.map { it.text })
        assertEquals("この部屋は閉鎖されました", u.endMessage)
    }

    @Test
    fun keepsPreviousStateForMissingValues() {
        val u = ChatUpdateParser.parse("<?xml version=\"1.0\" ?>\nloglines = new Array();", initial.copy(loadAverage = 99))
        assertEquals(540L, u.state.fromSize)
        assertEquals(99, u.state.loadAverage)
        assertFalse(u.hasNewLines)
    }

    @Test
    fun noNewLines() {
        val u = ChatUpdateParser.parse(fixture("chat/ajax_no_new.txt"), initial)
        assertEquals(900L, u.state.fromSize)
        assertTrue(u.newLines.isEmpty())
        assertFalse(u.hasNewLines)
    }

    @Test
    fun parsesChatPage() {
        val page = ChatPageParser.parse(fixture("chat/chat_page_guest.html"), room)
        assertEquals("チャH", page.title)
        assertEquals("タロウ", page.myName)
        assertFalse(page.isOwner)
        assertTrue(page.isPublic)
        assertEquals(690L, page.state.fromSize)
        assertEquals(28789, page.state.mugonLimitSeconds)
        assertTrue(page.state.isFilledRoom)
        assertEquals(434, page.state.loadAverage)
        assertEquals(5, page.state.reloadIntervalInitialSeconds)
        assertEquals(600, page.state.reloadIntervalMaxSeconds)
        assertEquals("ゆっくりお話しできる方", page.waitingMessage)

        assertEquals(3, page.lines.size)
        val (mine, partner, notice) = page.lines
        assertEquals("タロウ", mine.speaker)
        assertEquals("こんばんは", mine.text)
        assertEquals(LocalTime.of(22, 23, 31), mine.time)
        assertTrue(partner.speakerIsFemale)
        assertEquals("こんばんは\n写真です", partner.text)
        assertEquals(listOf("https://2shot.chat.shalove.net/img/up/sample.jpg"), partner.imageUrls)
        assertTrue(notice.isNotice)
    }

    @Test
    fun pageWithoutRoomVarsIsNotOpened() {
        // 終了した部屋を開き直すと、部屋の画面ではないページが返る（合成データ）。
        val html = "<html><head><title>ラブルーム</title></head><body><p>この部屋は終了しました</p></body></html>"
        assertThrows(RoomPageUnavailableException::class.java) { ChatPageParser.parse(html, room) }
    }

    @Test
    fun detectsOwnerFromCloseFormWhenAuthIsMissing() {
        val page = ChatPageParser.parse(fixture("chat/chat_page_owner.html"), room)
        assertTrue(page.isOwner)
        assertFalse(page.state.isFilledRoom)
        assertEquals(120L, page.state.fromSize)
        assertEquals("タロウ", page.myName)
        assertTrue(page.lines.single().isNotice)
    }

    @Test
    fun parsesEntryForm() {
        val form = EntryFormParser.parse(fixture("chat/preenter.html"), "2shot.chat.shalove.net", "https://2shot.chat.shalove.net/PreEnterRoom")!!
        assertEquals(900000002L, form.roomId)
        assertEquals("chah", form.genreKey)
        assertEquals("fedcba9876543210fedcba9876543210", form.pwd)
        assertEquals("ミナ (25) さん 女 (Android 一時ID Zz9Yy) が 待機中 です", form.hostDescription)
        assertEquals("ゆっくりお話しできる方", form.waitingMessage)
        assertTrue(form.requiresCaptcha)
        assertEquals("", form.defaultName)
    }

    @Test
    fun detectsEntryFormWithoutCaptcha() {
        val form = EntryFormParser.parse(fixture("chat/preenter_no_captcha.html"), "2shot.chat.shalove.net", "https://2shot.chat.shalove.net/PreEnterRoom")!!
        assertFalse(form.requiresCaptcha)
    }

    @Test
    fun noEntryFormOnOtherPages() {
        assertNull(EntryFormParser.parse("<html><body>この部屋は満室です</body></html>", "h", "https://h/"))
    }
}
