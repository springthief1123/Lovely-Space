package io.github.springthief1123.lovelyspace.ui.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object LovelySpacing {
    val screenHorizontal = 18.dp
    val section = 24.dp
    val item = 14.dp
    val compact = 8.dp

    /** Glass Top Bar 本体の高さ。ステータスバーは含まない。 */
    val topBarHeight = 58.dp

    /** Top Bar と各画面の先頭コンテンツの間に残す余白。 */
    val topBarContentGap = 16.dp

    val bottomContentInset = 108.dp

    /** 右下の 56dp FAB より上へ Snackbar を逃がすための余白。 */
    val snackbarBottomInset = 160.dp
}

/**
 * Edge-to-edge 環境で Glass Top Bar の下からコンテンツを開始するための余白。
 * 固定値ではなく端末ごとの status bar inset を含める。
 */
@Composable
fun lovelyMainContentTopPadding(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
        LovelySpacing.topBarHeight +
        LovelySpacing.topBarContentGap

object LovelyShapes {
    val control = RoundedCornerShape(12.dp)
    val panel = RoundedCornerShape(16.dp)
    val bottomGlass = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
}
