package io.github.springthief1123.lovelyspace

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
import io.github.springthief1123.lovelyspace.settings.RoomMessageLines
import io.github.springthief1123.lovelyspace.settings.TextScale
import io.github.springthief1123.lovelyspace.ui.rooms.LocalRoomMessageMaxLines
import io.github.springthief1123.lovelyspace.settings.ThemeMode
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpaceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LovelySpaceApp
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
}
