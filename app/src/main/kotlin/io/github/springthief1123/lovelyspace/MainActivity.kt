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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LovelySpaceApp
        // 画面の作り直し（回転など）では同じ通知を開き直さない。
        if (savedInstanceState == null) openNotification(intent)
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
        openNotification(intent)
    }

    /** 通知のタップで開かれたら、お知らせを既読にして該当の画面へ移す（移動は AppNavHost が行う）。 */
    private fun openNotification(intent: Intent?) {
        val id = AppNotifier.notificationId(intent) ?: return
        (application as LovelySpaceApp).notifier.open(id)
    }
}
