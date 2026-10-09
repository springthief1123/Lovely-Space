package io.github.springthief1123.lovelyspace.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PresetLabelsTest {
    @Test fun profileLabelKeepsWhatWasTyped() {
        assertEquals("いつもの", profilePresetLabel(" いつもの ", "合成の名前"))
    }

    @Test fun profileLabelFallsBackToName() {
        assertEquals("合成の名前", profilePresetLabel("  ", " 合成の名前 "))
    }

    @Test fun messageLabelKeepsWhatWasTyped() {
        assertEquals("夜用", messagePresetLabel("夜用", "合成の待機メッセージです"))
    }

    @Test fun shortMessageBecomesTheLabel() {
        assertEquals("合成の 短い文", messagePresetLabel("", "  合成の   短い文 "))
    }

    @Test fun longMessageIsCutAfterTwelveCharacters() {
        assertEquals("一二三四五六七八九十一二…", messagePresetLabel("", "一二三四五六七八九十一二三四五"))
    }

    @Test fun messageLabelDoesNotSplitSurrogatePairs() {
        val emoji = "🌹"
        assertEquals(emoji.repeat(12) + "…", messagePresetLabel("", emoji.repeat(13)))
    }
}
