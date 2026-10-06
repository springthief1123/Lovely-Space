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
import io.github.springthief1123.lovelyspace.settings.TextScale
import io.github.springthief1123.lovelyspace.settings.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF994B64),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF4E7EB),
    onPrimaryContainer = Color(0xFF492434),
    secondary = Color(0xFF766970),
    secondaryContainer = Color(0xFFF4E7EB),
    onSecondaryContainer = Color(0xFF302C31),
    tertiary = Color(0xFF795D42),
    background = Color(0xFFF7F4F2),
    onBackground = Color(0xFF302C31),
    surface = Color(0xFFFFFDFB),
    onSurface = Color(0xFF302C31),
    surfaceVariant = Color(0xFFF0E7E8),
    onSurfaceVariant = Color(0xFF766970),
    surfaceContainer = Color(0xFFFFFDFB),
    surfaceContainerHigh = Color(0xFFF4E7EB),
    outline = Color(0xFFA38D95),
    outlineVariant = Color(0xFFE9E0E0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFEFABC1),
    onPrimary = Color(0xFF291C24),
    primaryContainer = Color(0xFF3B2934),
    onPrimaryContainer = Color(0xFFF5DCE6),
    secondary = Color(0xFFBCB0BA),
    secondaryContainer = Color(0xFF3B2934),
    onSecondaryContainer = Color(0xFFF5EFF2),
    tertiary = Color(0xFFE1C2A4),
    background = Color(0xFF18171C),
    onBackground = Color(0xFFF5EFF2),
    surface = Color(0xFF242229),
    onSurface = Color(0xFFF5EFF2),
    surfaceVariant = Color(0xFF302B34),
    onSurfaceVariant = Color(0xFFBCB0BA),
    surfaceContainer = Color(0xFF242229),
    surfaceContainerHigh = Color(0xFF302B34),
    outline = Color(0xFFB5A0AB),
    outlineVariant = Color(0xFF3A343E),
)

private fun lovelyTypography(scale: TextScale) = with(scale.multiplier) {
    Typography(
        titleLarge = TextStyle(fontSize = (24 * this).sp, lineHeight = (30 * this).sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = (18 * this).sp, lineHeight = (24 * this).sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = (16 * this).sp, lineHeight = (23 * this).sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = (16 * this).sp, lineHeight = (24 * this).sp, fontWeight = FontWeight.Normal),
        bodyMedium = TextStyle(fontSize = (15 * this).sp, lineHeight = (24 * this).sp, fontWeight = FontWeight.Normal),
        bodySmall = TextStyle(fontSize = (12 * this).sp, lineHeight = (18 * this).sp, fontWeight = FontWeight.Normal),
        labelLarge = TextStyle(fontSize = (14 * this).sp, lineHeight = (18 * this).sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = (12 * this).sp, lineHeight = (16 * this).sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = (11 * this).sp, lineHeight = (14 * this).sp, fontWeight = FontWeight.Medium),
    )
}

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
    female = Color(0xFF766970),
    male = Color(0xFF766970),
    waiting = Color(0xFF396452),
    publicWaiting = Color(0xFF396452),
    full = Color(0xFF766970),
    glassTint = Color(0xCCFFFDFB),
    glassBorder = Color(0xA6FFFFFF),
    divider = Color(0xFFE9E0E0),
)

private val DarkLovely = LovelyColors(
    female = Color(0xFFBCB0BA),
    male = Color(0xFFBCB0BA),
    waiting = Color(0xFF9BD1B6),
    publicWaiting = Color(0xFF9BD1B6),
    full = Color(0xFFBCB0BA),
    glassTint = Color(0xC9242229),
    glassBorder = Color(0x33FFFFFF),
    divider = Color(0xFF3A343E),
)

val LocalLovelyColors = staticCompositionLocalOf { LightLovely }

@Composable
fun LovelySpaceTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    textScale: TextScale = TextScale.STANDARD,
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
            typography = lovelyTypography(textScale),
            content = content,
        )
    }
}
