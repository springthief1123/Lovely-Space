package io.github.springthief1123.lovelyspace.ui.chat

import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.chat.ChatLine
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatOwnerGenderTest {
    // 合成データ。本家の実データは使わない。
    private fun line(id: Long, mine: Boolean, female: Boolean = false, speaker: String? = "合成$id") =
        UiLine(id, ChatLine(speaker = speaker, speakerIsFemale = female, text = "合成の発言", time = null), isMine = mine)

    @Test fun listedGenderWins() {
        val lines = listOf(line(1, mine = false, female = true))
        assertEquals(Gender.MALE, ownerGender(lines, ownerIsMine = false, listed = Gender.MALE))
        assertEquals(Gender.FEMALE, ownerGender(emptyList(), ownerIsMine = true, listed = Gender.FEMALE))
    }

    @Test fun femaleNameOnOwnerSideMeansFemaleOwner() {
        // 入室者として相手（左）が部屋主。
        assertEquals(Gender.FEMALE, ownerGender(listOf(line(1, mine = false, female = true)), ownerIsMine = false, listed = Gender.UNKNOWN))
        // 作成者として自分（右）が部屋主。
        assertEquals(Gender.FEMALE, ownerGender(listOf(line(1, mine = true, female = true)), ownerIsMine = true, listed = null))
    }

    @Test fun guestSideAndNoticesDoNotDecide() {
        val lines = listOf(line(1, mine = true, female = true), line(2, mine = false, female = true, speaker = null), line(3, mine = false))
        assertEquals(Gender.UNKNOWN, ownerGender(lines, ownerIsMine = false, listed = null))
    }
}
