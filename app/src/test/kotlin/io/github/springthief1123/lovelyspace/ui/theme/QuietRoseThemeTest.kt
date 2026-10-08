package io.github.springthief1123.lovelyspace.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quiet Rose の配色・角丸が Material 3 の既定値に戻っていないことを確かめる。 */
class QuietRoseThemeTest {
    private val schemes = listOf(
        Triple("light", LightColors, lightColorScheme()),
        Triple("dark", DarkColors, darkColorScheme()),
    )

    /** 標準部品が暗黙に使うロール。既定値（紫系の baseline）のままだと Quiet Rose から浮く。 */
    private val implicitRoles: List<Pair<String, (ColorScheme) -> Color>> = listOf(
        "surfaceContainerLowest" to { it.surfaceContainerLowest },
        "surfaceContainerLow" to { it.surfaceContainerLow },
        "surfaceContainerHighest" to { it.surfaceContainerHighest },
        "surfaceDim" to { it.surfaceDim },
        "surfaceBright" to { it.surfaceBright },
        "inverseSurface" to { it.inverseSurface },
        "inverseOnSurface" to { it.inverseOnSurface },
        "inversePrimary" to { it.inversePrimary },
        "tertiaryContainer" to { it.tertiaryContainer },
        "onTertiaryContainer" to { it.onTertiaryContainer },
    )

    @Test fun implicitRolesAreNotMaterialBaseline() {
        for ((name, scheme, baseline) in schemes) for ((role, pick) in implicitRoles) {
            assertNotEquals("$name.$role が Material 3 の既定値のまま", pick(baseline), pick(scheme))
        }
    }

    @Test fun floatingPanelsUseTheCardSurface() {
        // 検索パネル・部屋のメニュー・ボトムシート（Low）と標準メニュー（Container）は通常のカードと同じ面。
        for ((name, scheme, _) in schemes) {
            assertEquals("$name.surfaceContainerLow", scheme.surface, scheme.surfaceContainerLow)
            assertEquals("$name.surfaceContainer", scheme.surface, scheme.surfaceContainer)
        }
    }

    @Test fun textOnImplicitSurfacesKeepsContrast() {
        for ((name, s, _) in schemes) {
            listOf(
                "onSurface/Low" to (s.onSurface to s.surfaceContainerLow),
                "onSurfaceVariant/Low" to (s.onSurfaceVariant to s.surfaceContainerLow),
                "onSurface/Highest" to (s.onSurface to s.surfaceContainerHighest),
                "error/Low" to (s.error to s.surfaceContainerLow),
                "inverseOnSurface/inverseSurface" to (s.inverseOnSurface to s.inverseSurface),
                "inversePrimary/inverseSurface" to (s.inversePrimary to s.inverseSurface),
                "onErrorContainer/errorContainer" to (s.onErrorContainer to s.errorContainer),
                "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
            ).forEach { (pair, colors) ->
                val ratio = contrast(colors.first, colors.second)
                assertTrue("$name $pair のコントラスト $ratio が 4.5 未満", ratio >= 4.5)
            }
        }
    }

    @Test fun materialShapesFollowLovelyShapes() {
        assertSame(LovelyShapes.control, LovelyMaterialShapes.extraSmall)
        assertSame(LovelyShapes.control, LovelyMaterialShapes.small)
        assertSame(LovelyShapes.panel, LovelyMaterialShapes.medium)
        assertSame(LovelyShapes.menu, LovelyMaterialShapes.large)
        assertSame(LovelyShapes.sheet, LovelyMaterialShapes.extraLarge)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun luminance(c: Color): Double {
        fun channel(v: Float) = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }
}
