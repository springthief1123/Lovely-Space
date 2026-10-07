package io.github.springthief1123.lovelyspace

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.CompositionLocalProvider
import io.github.springthief1123.lovelyspace.notify.AppNotifier
import io.github.springthief1123.lovelyspace.settings.RoomMessageLines
import io.github.springthief1123.lovelyspace.settings.TextScale
import io.github.springthief1123.lovelyspace.ui.rooms.LocalRoomMessageMaxLines
import io.github.springthief1123.lovelyspace.settings.ThemeMode
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpaceTheme

class MainActivity : ComponentActivity() {
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
                    AppNavHost()
                }
            }
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
