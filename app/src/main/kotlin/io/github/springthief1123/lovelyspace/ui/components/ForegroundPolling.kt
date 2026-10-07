package io.github.springthief1123.lovelyspace.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/** タブ離脱・入室・バックグラウンド移行で取得を取消し、復帰時に再開する。 */
@Composable
fun ForegroundPolling(enabled: Boolean, key: Any = Unit, onStop: () -> Unit = {}, poll: suspend () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val current = rememberUpdatedState(poll)
    val stop = rememberUpdatedState(onStop)
    // 手動取得の取消は自動更新スイッチに関係なく登録する。
    DisposableEffect(owner, key) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) stop.value()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); stop.value() }
    }
    LaunchedEffect(owner, enabled, key) {
        if (enabled) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { current.value() }
    }
}
