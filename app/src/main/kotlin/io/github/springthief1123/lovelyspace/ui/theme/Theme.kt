package io.github.springthief1123.lovelyspace.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.springthief1123.lovelyspace.settings.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFFAD315F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6D9E3),
    onPrimaryContainer = Color(0xFF3A1120),
    secondary = Color(0xFF735D65),
    secondaryContainer = Color(0xFFF1E6E9),
    onSecondaryContainer = Color(0xFF2C2024),
    tertiary = Color(0xFF795D42),
    background = Color(0xFFFFFAFA),
    onBackground = Color(0xFF241B1E),
    surface = Color(0xFFFFFAFA),
    onSurface = Color(0xFF241B1E),
    surfaceVariant = Color(0xFFF1E8EB),
    onSurfaceVariant = Color(0xFF6D5E63),
    surfaceContainer = Color(0xFFF8EFF2),
    surfaceContainerHigh = Color(0xFFF1E6E9),
    outline = Color(0xFF9B8A90),
    outlineVariant = Color(0xFFE2D6DA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFA8C5),
    onPrimary = Color(0xFF5E1732),
    primaryContainer = Color(0xFF792444),
    onPrimaryContainer = Color(0xFFFFD9E5),
    secondary = Color(0xFFD9C1C9),
    secondaryContainer = Color(0xFF43363B),
    onSecondaryContainer = Color(0xFFF3E4E9),
    tertiary = Color(0xFFE1C2A4),
    background = Color(0xFF171214),
    onBackground = Color(0xFFFFF7FA),
    surface = Color(0xFF171214),
    onSurface = Color(0xFFFFF7FA),
    surfaceVariant = Color(0xFF2B2326),
    onSurfaceVariant = Color(0xFFE2D4D9),
    surfaceContainer = Color(0xFF211A1D),
    surfaceContainerHigh = Color(0xFF2A2225),
    outline = Color(0xFF9F8F94),
    outlineVariant = Color(0xFF44383C),
)

private val LovelyTypography = Typography(
    titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

@Immutable
data class LovelyColors(
    val female: Color,
    val male: Color,
    val waiting: Color,
    val publicWaiting: Color,
    val full: Color,
    val glassTint: Color,
    val glassBorder: Color,
    val divider: Color,
)

private val LightLovely = LovelyColors(
    female = Color(0xFFD63A74),
    male = Color(0xFF3A6FD6),
    waiting = Color(0xFF2E8B57),
    publicWaiting = Color(0xFF00897B),
    full = Color(0xFF8D8589),
    glassTint = Color(0xCCFFF8FA),
    glassBorder = Color(0xA6FFFFFF),
    divider = Color(0xFFE9DDE1),
)

private val DarkLovely = LovelyColors(
    female = Color(0xFFFF8AB4),
    male = Color(0xFF8AB4FF),
    waiting = Color(0xFF7FD6A2),
    publicWaiting = Color(0xFF6FD9CB),
    full = Color(0xFFA8A0A4),
    glassTint = Color(0xC921191C),
    glassBorder = Color(0x33FFFFFF),
    divider = Color(0xFF382E32),
)

val LocalLovelyColors = staticCompositionLocalOf { LightLovely }

@Composable
fun LovelySpaceTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalLovelyColors provides if (dark) DarkLovely else LightLovely) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = LovelyTypography,
            content = content,
        )
    }
}
