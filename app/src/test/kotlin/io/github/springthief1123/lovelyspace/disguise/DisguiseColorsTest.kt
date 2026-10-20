package io.github.springthief1123.lovelyspace.disguise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisguiseColorsTest {
    @Test fun hsvConvertsPrimaryColorsBothWays() {
        assertEquals(0xFFFF0000.toInt(), Hsv(0f, 1f, 1f).toColor())
        assertEquals(0xFF00FF00.toInt(), Hsv(120f, 1f, 1f).toColor())
        assertEquals(0xFF0000FF.toInt(), Hsv(240f, 1f, 1f).toColor())
        assertEquals(0xFFFFFFFF.toInt(), Hsv(200f, 0f, 1f).toColor())
        assertEquals(0xFF000000.toInt(), Hsv(200f, 1f, 0f).toColor())
        val blue = Hsv.of(0xFF1A73E8.toInt())
        assertEquals(214f, blue.hue, 1f)
        assertEquals(0xFF1A73E8.toInt(), blue.toColor())
    }

    /** パレットの色はどれもホイールの値に直して戻しても同じ色になる（ホイールに切り替えても色が変わらない）。 */
    @Test fun paletteColorsSurviveTheWheel() {
        (DisguisePresetColors + DisguisePaletteColors).forEach { color ->
            assertEquals(formatHexColor(color), formatHexColor(Hsv.of(color).toColor()))
        }
    }

    @Test fun hueWrapsAroundTheWheel() {
        assertEquals(Hsv(0f, 1f, 1f).toColor(), Hsv(360f, 1f, 1f).toColor())
        assertEquals(Hsv(300f, 1f, 1f).toColor(), Hsv(-60f, 1f, 1f).toColor())
    }

    @Test fun parsesAndFormatsColorCodes() {
        assertEquals("#1A73E8", formatHexColor(0xFF1A73E8.toInt()))
        assertEquals(0xFF1A73E8.toInt(), parseHexColor("#1a73e8"))
        assertEquals(0xFF1A73E8.toInt(), parseHexColor(" 1A73E8 "))
        assertNull(parseHexColor("1A73E"))
        assertNull(parseHexColor("1A73EG"))
        assertNull(parseHexColor("#FF1A73E8"))
    }
}
