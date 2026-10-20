package io.github.springthief1123.lovelyspace.lock

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * アプリがロック中か。ダイアログ・シート・メニューはアプリの画面とは別の窓に出て、アプリの画面にかぶせた
 * ロック画面より上に重なる。これらの部品はこの値を見て、ロック中は中身を隠す（[hiddenWhileLocked]）。
 */
val LocalAppLocked = compositionLocalOf { false }

/**
 * ロック中は中身を描かず、読み上げの対象からも外す。入力中の内容などの状態は残し、解除すると元どおりに見える。
 * ダイアログ・シートの面に付ける。窓の上に出るロック画面（[LockWindow]）が出るまでの間も中身を見せない。
 */
fun Modifier.hiddenWhileLocked(locked: Boolean): Modifier =
    if (locked) drawWithContent {}.clearAndSetSemantics {} else this

/**
 * ロック中に初めて出ようとしたダイアログ・シートを、解除するまで出さないための判定。出してよければ true。
 * 後から足した窓はロック画面の窓より上に重なるため（通知や画面の状態で、ロック中に開くことがある）。
 * 開いたあとでロックしたものは、ロック画面の窓が後から上に重なるので出したままにする。
 */
@Composable
fun rememberShownWhileUnlocked(): Boolean {
    val locked = LocalAppLocked.current
    var openedWhileLocked by remember { mutableStateOf(locked) }
    LaunchedEffect(locked) { if (!locked) openedWhileLocked = false }
    return !(openedWhileLocked && locked)
}

/**
 * ロック画面を出す窓。アプリの画面の上に開いているダイアログ・シートより上に重ねるため、ロックした時点で
 * 新しい窓として足す。全画面・端まで描き、開くときのアニメーションと後ろを暗くする効果は付けない
 * （アニメーションの途中で後ろが見えないように）。戻るボタンは中身（[LockScreen]）で受ける。
 */
@Composable
fun LockWindow(content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        val lightBars = MaterialTheme.colorScheme.background.luminance() > 0.5f
        SideEffect {
            window ?: return@SideEffect
            window.setWindowAnimations(0)
            window.setDimAmount(0f)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
        // ロック画面の中のダイアログ（忘れた場合）は隠さない。
        CompositionLocalProvider(LocalAppLocked provides false) { content() }
    }
}
