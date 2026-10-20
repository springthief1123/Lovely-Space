package io.github.springthief1123.lovelyspace.disguise

import kotlin.math.abs
import kotlin.math.roundToInt

/** よく使われるアプリのアイコンの色。設定の「おすすめ」に並べる。 */
val DisguisePresetColors: List<Int> = listOf(
    0xFFFFFFFF, 0xFFE8EAED, 0xFF9AA0A6, 0xFF5F6368, 0xFF202124, 0xFF1A73E8,
    0xFF4FA3E0, 0xFF1E8E3E, 0xFFF29900, 0xFFD93025, 0xFF8E5CD9, 0xFFE0457B,
).map { it.toInt() }

/** パレット。色相ごとに明るい順の 4 段（Material の 300・500・700・900）を並べる（画面では 6 列で折り返す）。 */
val DisguisePaletteColors: List<Int> = listOf(
    0xFFE57373, 0xFFF44336, 0xFFD32F2F, 0xFFB71C1C, // 赤
    0xFFF06292, 0xFFE91E63, 0xFFC2185B, 0xFF880E4F, // ピンク
    0xFFBA68C8, 0xFF9C27B0, 0xFF7B1FA2, 0xFF4A148C, // 紫
    0xFF7986CB, 0xFF3F51B5, 0xFF303F9F, 0xFF1A237E, // 藍
    0xFF64B5F6, 0xFF2196F3, 0xFF1976D2, 0xFF0D47A1, // 青
    0xFF4DD0E1, 0xFF00BCD4, 0xFF0097A7, 0xFF006064, // シアン
    0xFF4DB6AC, 0xFF009688, 0xFF00796B, 0xFF004D40, // 青緑
    0xFF81C784, 0xFF4CAF50, 0xFF388E3C, 0xFF1B5E20, // 緑
    0xFFDCE775, 0xFFCDDC39, 0xFFAFB42B, 0xFF827717, // 黄緑
    0xFFFFD54F, 0xFFFFC107, 0xFFFFA000, 0xFFFF6F00, // 琥珀
    0xFFFFB74D, 0xFFFF9800, 0xFFF57C00, 0xFFE65100, // 橙
    0xFFA1887F, 0xFF795548, 0xFF5D4037, 0xFF3E2723, // 茶
    0xFF90A4AE, 0xFF607D8B, 0xFF455A64, 0xFF263238, // 青灰
).map { it.toInt() }

/** 色相 [hue]（0〜360）・彩度 [saturation]・明度 [value]（0〜1）。カラーホイールの状態。 */
data class Hsv(val hue: Float, val saturation: Float, val value: Float) {
    /** 不透明な ARGB にする。 */
    fun toColor(): Int {
        val h = ((hue % 360f) + 360f) % 360f / 60f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1 - abs(h % 2 - 1))
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = v - c
        fun channel(f: Float) = ((f + m) * 255).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    companion object {
        fun of(color: Int): Hsv {
            val r = (color shr 16 and 0xFF) / 255f
            val g = (color shr 8 and 0xFF) / 255f
            val b = (color and 0xFF) / 255f
            val max = maxOf(r, g, b)
            val delta = max - minOf(r, g, b)
            val hue = when {
                delta == 0f -> 0f
                max == r -> 60f * (((g - b) / delta) % 6f)
                max == g -> 60f * ((b - r) / delta + 2f)
                else -> 60f * ((r - g) / delta + 4f)
            }
            return Hsv(hue = (hue + 360f) % 360f, saturation = if (max == 0f) 0f else delta / max, value = max)
        }
    }
}

/** `#RRGGBB` の形（大文字）。 */
fun formatHexColor(color: Int): String = "#%06X".format(color and 0xFFFFFF)

/** `RRGGBB`（先頭の # は省略可、大小文字どちらでも）を不透明な ARGB にする。読めなければ null。 */
fun parseHexColor(text: String): Int? {
    val hex = text.trim().removePrefix("#")
    if (hex.length != 6 || hex.any { it.lowercaseChar() !in HEX_DIGITS }) return null
    return (0xFF shl 24) or hex.toInt(16)
}

private const val HEX_DIGITS = "0123456789abcdef"
