package io.github.springthief1123.lovelyspace.notify

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
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
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult)
    return {
        val hasPermission = AppNotifier.hasPermission(context)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission && !notificationPermissionWasRequested(context) -> {
                // 画面を離れたりプロセスが作り直されても再度ダイアログを出さないよう、起動前に永続化する。
                markNotificationPermissionRequested(context)
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            !hasPermission || !NotificationManagerCompat.from(context).areNotificationsEnabled() ->
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


private const val PERMISSION_PREFS = "notification_permission"
private const val KEY_PERMISSION_REQUESTED = "requested"

private fun notificationPermissionWasRequested(context: Context): Boolean =
    context.getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_PERMISSION_REQUESTED, false)

private fun markNotificationPermissionRequested(context: Context) {
    context.getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_PERMISSION_REQUESTED, true)
        .apply()
}
