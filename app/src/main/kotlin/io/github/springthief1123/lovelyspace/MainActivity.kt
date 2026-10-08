package io.github.springthief1123.lovelyspace

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.springthief1123.lovelyspace.lock.LockScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import io.github.springthief1123.lovelyspace.notify.AppNotifier
import io.github.springthief1123.lovelyspace.settings.RoomMessageLines
import io.github.springthief1123.lovelyspace.settings.TextScale
import io.github.springthief1123.lovelyspace.ui.rooms.LocalRoomMessageMaxLines
import io.github.springthief1123.lovelyspace.settings.ThemeMode
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpaceTheme

// 生体認証（BiometricPrompt）を出すため FragmentActivity にする。
@OptIn(ExperimentalComposeUiApi::class)
class MainActivity : FragmentActivity() {
    private var handledNotificationId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LovelySpaceApp
        // 回転やプロセス破棄からの再生成では処理済み ID を引き継ぐ。このとき届く intent は前回のものなので、
        // 処理済みの通知なら開き直さない（ID が異なれば通常どおり処理する）。新しいタップは onNewIntent に届く。
        handledNotificationId = savedInstanceState?.getString(STATE_HANDLED_NOTIFICATION_ID)
        if (savedInstanceState == null || AppNotifier.notificationId(intent) != handledNotificationId) {
            openNotification(intent)
        }
        // アプリロックを使うときは、最近のアプリ一覧に画面の中身を出さない。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                app.appLock.state.map { it.config.enabled }.distinctUntilChanged().collect(::hideFromRecents)
            }
        }
        setContent {
            val themeMode by app.settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val textScale by app.settings.textScale.collectAsStateWithLifecycle(initialValue = TextScale.STANDARD)
            val messageLines by app.settings.roomMessageLines.collectAsStateWithLifecycle(initialValue = RoomMessageLines.FOUR)
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(dark) {
                val bars = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            LovelySpaceTheme(themeMode = themeMode, textScale = textScale) {
                CompositionLocalProvider(LocalRoomMessageMaxLines provides messageLines.maxLines) {
                    val lock by app.appLock.state.collectAsStateWithLifecycle()
                    val focusManager = LocalFocusManager.current
                    val keyboard = LocalSoftwareKeyboardController.current
                    // ロックしたら、後ろの入力欄のフォーカスとキーボードを外し、キー入力が届かないようにする。
                    LaunchedEffect(lock.locked) {
                        if (lock.locked) { focusManager.clearFocus(force = true); keyboard?.hide() }
                    }
                    Box {
                        // ロック中は後ろの画面を読み上げの対象から外し、フォーカスも移せないようにする。
                        Box(
                            Modifier
                                .then(if (lock.locked) Modifier.clearAndSetSemantics {} else Modifier)
                                .focusProperties { enter = { if (lock.locked) FocusRequester.Cancel else FocusRequester.Default } }
                                .focusGroup(),
                        ) { AppNavHost() }
                        if (lock.locked) LockScreen(app.appLock, lock)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as LovelySpaceApp).appLock.onForeground()
    }

    override fun onStop() {
        super.onStop()
        // 画面の回転などの作り直しは、背景に回ったことにしない。
        if (!isChangingConfigurations) (application as LovelySpaceApp).appLock.onBackground()
    }

    /**
     * Android 13 以降は最近のアプリ一覧のスクリーンショットだけを止める（利用者のスクリーンショットは撮れる）。
     * それより前の端末には同じ仕組みが無いので、画面全体を保護する（スクリーンショットも撮れなくなる）。
     */
    private fun hideFromRecents(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(!enabled)
        } else if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 次の Activity 再生成でも、この最新 Intent を重複判定の対象にする。
        setIntent(intent)
        // 新しく届いた intent は利用者のタップなので、同じ ID（消えない常駐通知の再タップ）でも開く。
        openNotification(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        handledNotificationId?.let { outState.putString(STATE_HANDLED_NOTIFICATION_ID, it) }
        super.onSaveInstanceState(outState)
    }

    /** 通知のタップで開かれたら、お知らせを既読にして該当の画面へ移す（移動は AppNavHost が行う）。 */
    private fun openNotification(intent: Intent?) {
        val id = AppNotifier.notificationId(intent) ?: return
        handledNotificationId = id
        (application as LovelySpaceApp).notifier.open(intent)
    }

    companion object {
        private const val STATE_HANDLED_NOTIFICATION_ID = "handled_notification_id"
    }
}
