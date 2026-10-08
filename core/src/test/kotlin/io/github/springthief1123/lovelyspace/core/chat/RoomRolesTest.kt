package io.github.springthief1123.lovelyspace.core.chat

import org.junit.Assert.*
import org.junit.Test

class RoomRolesTest {
    private fun say(speaker: String, text: String = "合成の発言") = ChatLine(speaker, false, text, null)
    private fun notice(text: String) = ChatLine(null, false, text, null)

    private val created = notice("ミナ(24)(女)さん(iPhone 一時ID Zz9yX)が新規部屋を作成して待機中です。")
    private val entered = notice("タロウ(男)さん(Android 一時ID Ab3dE)が入室しましたので、このチャットルームをロックしました。")

    @Test fun learnsOwnerAndGuestFromNotices() {
        val roles = RoomRoles().learn(listOf(say("タロウ"), entered, created))
        assertEquals(RoomRoles(owner = "ミナ", guest = "タロウ"), roles)
        assertEquals(false, roles.isGuest(say("ミナ")))
        assertEquals(true, roles.isGuest(say("タロウ")))
        assertEquals(true, roles.isGuest(say("タロウ◆合成")))
        assertNull(roles.isGuest(entered))
    }

    @Test fun oneKnownNameSplitsTheOther() {
        val onlyGuest = RoomRoles().learn(listOf(entered))
        assertEquals(true, onlyGuest.isGuest(say("タロウ")))
        assertEquals(false, onlyGuest.isGuest(say("ミナ")))
        val onlyOwner = RoomRoles().learn(listOf(created))
        assertEquals(true, onlyOwner.isGuest(say("タロウ")))
    }

    @Test fun unknownWithoutNoticesAndKeptAfterTheyScrollOut() {
        assertNull(RoomRoles().isGuest(say("ミナ")))
        val roles = RoomRoles().learn(listOf(created, entered)).learn(listOf(say("ミナ")))
        assertEquals(RoomRoles(owner = "ミナ", guest = "タロウ"), roles)
        // 同じ名前どうしは見分けられない。
        assertNull(RoomRoles(owner = "タロウ", guest = "タロウ").isGuest(say("タロウ")))
        // 入室者が入れ替わったら新しいお知らせの名前を使う。
        val next = notice("ジロウ(男)さん(Android 一時ID Cc1dD)が入室しましたので、このチャットルームをロックしました。")
        assertEquals("ジロウ", roles.learn(listOf(next, entered)).guest)
        assertNull(RoomRoles().learn(listOf(notice("ルーム作成者（待機者）によって発言がクリアされました。"))).isGuest(say("ミナ")))
    }
}
