package io.github.springthief1123.lovelyspace.ui.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object LovelySpacing {
    val screenHorizontal = 20.dp
    val section = 24.dp
    val item = 14.dp
    val compact = 8.dp

    /** Glass Top Bar 本体の高さ。ステータスバーは含まない。 */
    val topBarHeight = 58.dp

    /** Top Bar と各画面の先頭コンテンツの間に残す余白。 */
    val topBarContentGap = 16.dp

    val bottomContentInset = 124.dp

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

/**
 * 役割ごとの角丸。新しい部品はここか [LovelyMaterialShapes] 経由で使い、画面で半径の数値を増やさない。
 * 既存の部屋カード（16dp）・部屋のメニュー（20dp）・検索パネル（24dp）・Glass ナビの値と対応している。
 */
object LovelyShapes {
    /** 入力欄・チップ・小さな通知など、操作部品の角丸。 */
    val control = RoundedCornerShape(12.dp)
    /** 通常のカード・項目の面。 */
    val panel = RoundedCornerShape(16.dp)
    /** 浮かんで開くメニュー（RoomActionMenu と同じ）。 */
    val menu = RoundedCornerShape(20.dp)
    /** 大きな面：検索パネル・ダイアログ・ボトムシートの上端。 */
    val sheet = RoundedCornerShape(24.dp)
    val bottomGlass = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
}

/**
 * MaterialTheme に渡す Shapes。標準部品が暗黙に使う角丸を Quiet Rose の役割にそろえる。
 * - extraSmall / small: 入力欄・標準メニュー・スナックバー・チップ（既定 4dp / 8dp → [LovelyShapes.control]）
 * - medium: カード（既定 12dp → [LovelyShapes.panel]）
 * - large: メニュー級の浮かぶ面（既定 16dp → [LovelyShapes.menu]）
 * - extraLarge: ダイアログ・ボトムシート（既定 28dp → [LovelyShapes.sheet]）
 * ボタン・スイッチ・セグメントボタンは Shapes ではなく全丸で、ここでは変わらない。
 */
val LovelyMaterialShapes = Shapes(
    extraSmall = LovelyShapes.control,
    small = LovelyShapes.control,
    medium = LovelyShapes.panel,
    large = LovelyShapes.menu,
    extraLarge = LovelyShapes.sheet,
)

/** ナビゲーションの実測高さを含む余白。シェル外では既定値を使う。 */
val LocalLovelyBottomContentInset = staticCompositionLocalOf { LovelySpacing.bottomContentInset }

@Composable
fun lovelyMainContentBottomInset(): Dp = LocalLovelyBottomContentInset.current
