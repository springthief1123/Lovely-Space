package io.github.springthief1123.lovelyspace.notify

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat

/**
 * 端末通知を許可してもらう。Android 13 以降は実行時権限を求め、一度断られた後や
 * アプリの通知がオフにされている場合は端末の通知設定を開く。[onResult] には許可されたかを渡す。
 * 背景機能を初めてオンにするとき（#46）もこれを使う。
 */
@Composable
fun rememberNotificationPermissionRequest(onResult: (Boolean) -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) denied = true
        onResult(granted)
    }
    return {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !AppNotifier.hasPermission(context) && !denied ->
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            !AppNotifier.hasPermission(context) || !NotificationManagerCompat.from(context).areNotificationsEnabled() ->
                context.startActivity(appNotificationSettingsIntent(context))
            else -> onResult(true)
        }
    }
}

/** このアプリの通知設定（端末の設定画面）。 */
fun appNotificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** 種類ごとの通知チャンネルの設定（端末の設定画面）。 */
fun channelSettingsIntent(context: Context, kind: NotificationKind): Intent =
    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, kind.channelId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
