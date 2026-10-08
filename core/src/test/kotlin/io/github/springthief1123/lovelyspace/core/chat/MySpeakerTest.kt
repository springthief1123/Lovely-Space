package io.github.springthief1123.lovelyspace.core.chat

import org.junit.Assert.*
import org.junit.Test

class MySpeakerTest {
    private fun line(speaker: String?, text: String) = ChatLine(speaker, false, text, null)

    @Test fun matchesTheFormNameIgnoringSurroundingSpaces() {
        val me = MySpeaker.of("タロウ")
        assertTrue(me.isMine(line("タロウ", "合成の発言")))
        assertTrue(me.isMine(line("タロウ\u00A0", "合成の発言")))
        assertFalse(me.isMine(line("ハナコ", "合成の発言")))
        // 名前の途中の空白は区別する。
        assertFalse(MySpeaker.of("ヤマ ダ").isMine(line("ヤマダ", "合成の発言")))
        assertFalse(MySpeaker.of("ヤマダ").isMine(line("ヤマ ダ", "合成の発言")))
        assertFalse(me.isMine(line(null, "タロウさんが入室しました")))
    }

    @Test fun learnsTheLogNameFromTheLineItSent() {
        // 発言欄の名前とログの名前欄の表記が違う（トリップ付きなど）場合。
        val me = MySpeaker.of("タロウ").learnFromSent("こんばんは\nはじめまして ",
            listOf(line("ハナコ", "よろしく"), line("タロウ◆合成", "こんばんは\nはじめまして")))
        assertTrue(me.isMine(line("タロウ◆合成", "次の発言")))
        assertFalse(me.isMine(line("ハナコ", "次の発言")))
    }

    @Test fun doesNotLearnFromNoticesOrUnrelatedLines() {
        val me = MySpeaker.of(null).learnFromSent("こんばんは",
            listOf(line(null, "こんばんは"), line("ハナコ", "よろしく")))
        assertEquals(MySpeaker(), me)
        assertFalse(me.isMine(line("ハナコ", "よろしく")))
    }
}
