package io.github.springthief1123.lovelyspace.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** トップバーに出す検索ボタン。[activeCount] は指定中の条件の数で、バッジに出す。[open] は検索シートを開いているか。押すたびに開け閉めする。 */
data class ShellSearchButton(val activeCount: Int, val open: Boolean, val onClick: () -> Unit)

/** 浮かせた「トップへ戻る」。一覧の上に新着が増えていれば [newCount] にその数を入れ、「新着 N件」と出す。 */
data class ShellScrollToTop(val newCount: Int, val onClick: () -> Unit)

/**
 * 画面（「見つける」）から、外枠のトップバーと浮かせたボタンへ渡す状態。
 * 外枠に置いたものだけが後ろの一覧をぼかせるので、「トップへ戻る」もここから外枠に出す。
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
