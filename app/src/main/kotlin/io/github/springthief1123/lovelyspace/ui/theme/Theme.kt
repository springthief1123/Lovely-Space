package io.github.springthief1123.lovelyspace.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.springthief1123.lovelyspace.settings.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFFB4235C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E2),
    onPrimaryContainer = Color(0xFF3E001B),
    secondary = Color(0xFF74565F),
    secondaryContainer = Color(0xFFFFD9E2),
    onSecondaryContainer = Color(0xFF2B151C),
    tertiary = Color(0xFF7C5635),
    background = Color(0xFFFFF8F8),
    surface = Color(0xFFFFF8F8),
    surfaceContainer = Color(0xFFFAEBEE),
    surfaceContainerHigh = Color(0xFFF4E5E8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB1C8),
    onPrimary = Color(0xFF650030),
    primaryContainer = Color(0xFF8E0A45),
    onPrimaryContainer = Color(0xFFFFD9E2),
    secondary = Color(0xFFE3BDC6),
    secondaryContainer = Color(0xFF5A3F47),
    onSecondaryContainer = Color(0xFFFFD9E2),
    tertiary = Color(0xFFEFBD94),
    background = Color(0xFF191113),
    surface = Color(0xFF191113),
    surfaceContainer = Color(0xFF261D20),
    surfaceContainerHigh = Color(0xFF31282A),
)

/** 性別や部屋の状態を表すアプリ固有の色。 */
@Immutable
data class LovelyColors(
    val female: Color,
    val male: Color,
    val waiting: Color,
    val publicWaiting: Color,
    val full: Color,
)

private val LightLovely = LovelyColors(
    female = Color(0xFFD63A74),
    male = Color(0xFF3A6FD6),
    waiting = Color(0xFF2E8B57),
    publicWaiting = Color(0xFF00897B),
    full = Color(0xFF8D8589),
)

private val DarkLovely = LovelyColors(
    female = Color(0xFFFF8AB4),
    male = Color(0xFF8AB4FF),
    waiting = Color(0xFF7FD6A2),
    publicWaiting = Color(0xFF6FD9CB),
    full = Color(0xFFA8A0A4),
)

val LocalLovelyColors = staticCompositionLocalOf { LightLovely }

@Composable
fun LovelySpaceTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalLovelyColors provides if (dark) DarkLovely else LightLovely,
    ) {
        MaterialTheme(colorScheme = colorScheme, typography = Typography(), content = content)
    }
}
