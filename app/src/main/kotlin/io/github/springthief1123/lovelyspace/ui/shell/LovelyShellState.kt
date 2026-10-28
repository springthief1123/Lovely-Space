package io.github.springthief1123.lovelyspace.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** トップバーに出す検索ボタン。[activeCount] は指定中の条件の数で、バッジに出す。[open] は検索シートを開いているか。押すたびに開け閉めする。 */
data class ShellSearchButton(val activeCount: Int, val open: Boolean, val onClick: () -> Unit)

/** 「見つける」の先頭へ戻す操作と、トップバー直下へ固定表示する新着件数。 */
data class ShellScrollToTop(val newCount: Int, val onClick: () -> Unit)

/**
 * 画面（「見つける」）から、外枠のトップバーと下部タブへ渡す状態。
 * 新着件数のGlass表示とタブ再タップは、外枠にある同じ先頭移動操作を使う。
 * 画面の切り替え中は 2 つの画面が同時にあるため、出した画面だけが取り下げられるよう [owner] で見分ける。
 */
@Stable
class LovelyShellState {
    private var owner: Any? = null
    var searchButton by mutableStateOf<ShellSearchButton?>(null)
        private set
    var scrollToTop by mutableStateOf<ShellScrollToTop?>(null)
        private set

    fun publish(owner: Any, searchButton: ShellSearchButton?, scrollToTop: ShellScrollToTop?) {
        this.owner = owner
        this.searchButton = searchButton
        this.scrollToTop = scrollToTop
    }

    fun release(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        searchButton = null
        scrollToTop = null
    }
}

val LocalLovelyShellState = staticCompositionLocalOf { LovelyShellState() }
