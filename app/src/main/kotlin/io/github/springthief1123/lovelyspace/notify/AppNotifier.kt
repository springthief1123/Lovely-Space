package io.github.springthief1123.lovelyspace.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.springthief1123.lovelyspace.MainActivity
import io.github.springthief1123.lovelyspace.R
import io.github.springthief1123.lovelyspace.settings.NotificationPreview
import org.json.JSONObject

/**
 * 端末通知を出す。出した内容は同時にお知らせ（[inbox]）に残すので、
 * 通知の権限が無い・オフにされている場合もアプリ内のベルからは確認できる。
 */
class AppNotifier(
    private val context: Context,
    private val inbox: NotificationInbox,
    private val preview: suspend () -> NotificationPreview,
) {
    private val manager = NotificationManagerCompat.from(context)

    /** 種類ごとの通知チャンネルを用意する。何度呼んでも既存の設定は変わらない。 */
    fun ensureChannels() {
        manager.createNotificationChannelsCompat(NotificationKind.entries.map { kind ->
            NotificationChannelCompat.Builder(kind.channelId, importance(kind))
                .setName(kind.label)
                .setDescription(kind.description)
                .build()
        })
    }

    /** 端末通知を出せる状態か（権限があり、アプリの通知がオフにされていない）。 */
    fun canPost(): Boolean = hasPermission(context) && manager.areNotificationsEnabled()

    /** その種類のチャンネルが利用者にオフにされていないか。 */
    fun channelEnabled(kind: NotificationKind): Boolean =
        manager.getNotificationChannelCompat(kind.channelId)?.importance != NotificationManagerCompat.IMPORTANCE_NONE

    @SuppressLint("MissingPermission") // canPost() で権限を確かめてから出す
    suspend fun post(notification: AppNotification) {
        inbox.add(notification)
        if (!canPost()) return
        manager.notify(notification.id, NOTIFICATION_ID, build(notification, preview()))
    }

    /** お知らせを開く（既読にして画面側へ渡し、残っている端末通知を消す）。 */
    fun open(id: String) {
        inbox.open(id) ?: return
        manager.cancel(id, NOTIFICATION_ID)
    }

    /** 通知のタップで起動したときに開く。履歴に残さない種類は intent に載せた開く先を使う。 */
    fun open(intent: Intent?) {
        val id = notificationId(intent) ?: return
        if (inbox[id] != null) return open(id)
        val notification = unloggedNotification(intent!!, id) ?: return
        inbox.show(notification)
    }

    internal fun build(notification: AppNotification, preview: NotificationPreview): Notification {
        val body = notification.body(preview)
        val builder = NotificationCompat.Builder(context, notification.kind.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notification.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setWhen(notification.at)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent(notification))
        if (notification.kind == NotificationKind.ONGOING) {
            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
        }
        if (notification.publicOnLockScreen(preview)) {
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        } else {
            // ロック画面では他の利用者の名前・待機メッセージを出さず、お知らせがあることだけを示す。
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(NotificationCompat.Builder(context, notification.kind.channelId)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(LOCKED_TITLE)
                    .setContentText(LOCKED_TEXT)
                    .setWhen(notification.at)
                    .build())
        }
        return builder.build()
    }

    private fun contentIntent(notification: AppNotification): PendingIntent = PendingIntent.getActivity(
        context, notification.id.hashCode(), openIntent(context, notification),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun importance(kind: NotificationKind) = when (kind) {
        NotificationKind.ROOM_ENTRY, NotificationKind.WAITLIST -> NotificationManagerCompat.IMPORTANCE_HIGH
        NotificationKind.RADAR_MATCH -> NotificationManagerCompat.IMPORTANCE_DEFAULT
        NotificationKind.ONGOING -> NotificationManagerCompat.IMPORTANCE_LOW
    }

    companion object {
        const val ACTION_OPEN = "io.github.springthief1123.lovelyspace.OPEN_NOTIFICATION"
        const val EXTRA_ID = "notification_id"
        private const val NOTIFICATION_ID = 1
        const val LOCKED_TITLE = "Lovely Space"
        const val LOCKED_TEXT = "新しいお知らせがあります"

        private const val EXTRA_KIND = "notification_kind"
        private const val EXTRA_TARGET = "notification_target"

        /** 通知のタップで開く intent。履歴に無くても開けるよう、種類と開く先も載せる（本文は載せない）。 */
        fun openIntent(context: Context, notification: AppNotification): Intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN)
            .putExtra(EXTRA_ID, notification.id)
            .putExtra(EXTRA_KIND, notification.kind.name)
            .putExtra(EXTRA_TARGET, notification.target.toJson().toString())
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

        private fun unloggedNotification(intent: Intent, id: String): AppNotification? = runCatching {
            AppNotification(
                id = id,
                kind = NotificationKind.valueOf(intent.getStringExtra(EXTRA_KIND)!!),
                title = "",
                text = "",
                target = notificationTarget(JSONObject(intent.getStringExtra(EXTRA_TARGET)!!)),
                at = System.currentTimeMillis(),
                read = true,
            )
        }.getOrNull()

        /** 通知のタップで起動したときの、お知らせの ID。 */
        fun notificationId(intent: Intent?): String? = intent
            ?.takeIf { it.action == ACTION_OPEN }
            // 最近使ったアプリから開き直したときは、前の通知の intent がそのまま届くので開かない。
            ?.takeIf { it.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0 }
            ?.getStringExtra(EXTRA_ID)

        /** Android 13 以降は通知の実行時権限が要る。 */
        fun hasPermission(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}
