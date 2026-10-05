package io.github.springthief1123.lovelyspace.core

import org.junit.Assert.*
import org.junit.Test

class ProfileValidationTest {
    @Test fun permitsSecretAgeAndChecksBoundaries() {
        assertTrue(validProfile("名前", 1, null))
        assertTrue(validProfile("名前", 2, 18))
        assertTrue(validProfile("名前", 1, 99))
        assertFalse(validProfile(" ", 1, 20))
        assertFalse(validProfile("名前", 0, 20))
        assertFalse(validProfile("名前", 1, 17))
        assertFalse(validProfile("名前", 1, 100))
    }
    @Test fun countsSiteUtf16WidthIncludingHalfWidthKatakanaAndEmoji() {
        assertEquals(9, messageWidth("abあｱ😀"))
        assertEquals(500, messageWidth("あ".repeat(250)))
        assertEquals(501, messageWidth("あ".repeat(250) + "a"))
    }
}
