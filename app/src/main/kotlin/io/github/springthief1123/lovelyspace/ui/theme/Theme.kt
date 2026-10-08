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

// Material 3 は未指定の色ロールに標準の紫系の既定値（baseline）を入れる。
// 標準部品（ボトムシート・ダイアログ・スイッチ・スナックバーなど）が暗黙に使うロールも含め、
// 使う可能性のあるロールはすべて Quiet Rose の値で埋める。値は docs/design-system.md の表と同期する。
internal val LightColors = lightColorScheme(
    primary = Color(0xFF994B64),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF4E7EB),
    onPrimaryContainer = Color(0xFF492434),
    inversePrimary = Color(0xFFEFABC1),
    secondary = Color(0xFF675A63),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF4E7EB),
    onSecondaryContainer = Color(0xFF302C31),
    tertiary = Color(0xFF795D42),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF3E3D3),
    onTertiaryContainer = Color(0xFF2E1F10),
    background = Color(0xFFF7F4F2),
    onBackground = Color(0xFF302C31),
    surface = Color(0xFFFFFDFB),
    onSurface = Color(0xFF302C31),
    surfaceVariant = Color(0xFFF0E7E8),
    onSurfaceVariant = Color(0xFF675A63),
    inverseSurface = Color(0xFF3A3439),
    inverseOnSurface = Color(0xFFF5EFF2),
    // 検索パネル・部屋のメニュー・ボトムシートは Low、標準メニューは Container を使う。
    // どちらも通常のカードと同じ不透明な面にそろえる（以前は Low が既定の薄紫 #F7F2FA だった）。
    surfaceContainerLowest = Color(0xFFFFFEFD),
    surfaceContainerLow = Color(0xFFFFFDFB),
    surfaceContainer = Color(0xFFFFFDFB),
    surfaceContainerHigh = Color(0xFFF4E7EB),
    surfaceContainerHighest = Color(0xFFEFE3E7),
    surfaceDim = Color(0xFFEDE6E4),
    surfaceBright = Color(0xFFFFFDFB),
    // 破壊的操作の赤は従来の表示（Material 3 1.3 の既定値）のまま、ライブラリ更新で変わらないよう固定する。
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFFA38D95),
    outlineVariant = Color(0xFFE9E0E0),
    scrim = Color.Black,
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFEFABC1),
    onPrimary = Color(0xFF291C24),
    primaryContainer = Color(0xFF3B2934),
    onPrimaryContainer = Color(0xFFF5DCE6),
    inversePrimary = Color(0xFF994B64),
    secondary = Color(0xFFD0C3CD),
    onSecondary = Color(0xFF2E2830),
    secondaryContainer = Color(0xFF3B2934),
    onSecondaryContainer = Color(0xFFF5EFF2),
    tertiary = Color(0xFFE1C2A4),
    onTertiary = Color(0xFF3E2D1A),
    tertiaryContainer = Color(0xFF4A3A2A),
    onTertiaryContainer = Color(0xFFF6E1CC),
    background = Color(0xFF18171C),
    onBackground = Color(0xFFF5EFF2),
    surface = Color(0xFF242229),
    onSurface = Color(0xFFF5EFF2),
    surfaceVariant = Color(0xFF302B34),
    onSurfaceVariant = Color(0xFFD0C3CD),
    inverseSurface = Color(0xFFF5EFF2),
    inverseOnSurface = Color(0xFF302C31),
    // ライトと同じく Low / Container は通常のカードの面にそろえる（以前は Low が既定の #1D1B20 だった）。
    surfaceContainerLowest = Color(0xFF131217),
    surfaceContainerLow = Color(0xFF242229),
    surfaceContainer = Color(0xFF242229),
    surfaceContainerHigh = Color(0xFF302B34),
    surfaceContainerHighest = Color(0xFF38323C),
    surfaceDim = Color(0xFF141318),
    surfaceBright = Color(0xFF36313A),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFFB5A0AB),
    outlineVariant = Color(0xFF3A343E),
    scrim = Color.Black,
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
    val femaleContainer: Color,
    val male: Color,
    val maleContainer: Color,
    val waiting: Color,
    val publicWaiting: Color,
    val full: Color,
    val glassTint: Color,
    val glassBorder: Color,
    val divider: Color,
)

private val LightLovely = LovelyColors(
    female = Color(0xFFA8456A),
    femaleContainer = Color(0xFFF7E3EA),
    male = Color(0xFF3C6496),
    maleContainer = Color(0xFFE2EAF5),
    waiting = Color(0xFF396452),
    publicWaiting = Color(0xFF2F6A73),
    full = Color(0xFF7A6E75),
    glassTint = Color(0xCCFFFDFB),
    glassBorder = Color(0xA6FFFFFF),
    divider = Color(0xFFE9E0E0),
)

private val DarkLovely = LovelyColors(
    female = Color(0xFFF2A7C0),
    femaleContainer = Color(0xFF432836),
    male = Color(0xFFA9C6F0),
    maleContainer = Color(0xFF26324A),
    waiting = Color(0xFF9BD1B6),
    publicWaiting = Color(0xFF8FCFD6),
    full = Color(0xFFB5A8AF),
    glassTint = Color(0xC9242229),
    glassBorder = Color(0x33FFFFFF),
    divider = Color(0xFF3A343E),
)

val LocalLovelyColors = staticCompositionLocalOf { LightLovely }
val LocalLovelyTextScale = staticCompositionLocalOf { 1f }

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
    CompositionLocalProvider(
        LocalLovelyColors provides if (dark) DarkLovely else LightLovely,
        LocalLovelyTextScale provides textScale.multiplier,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = lovelyTypography(textScale),
            shapes = LovelyMaterialShapes,
            content = content,
        )
    }
}
