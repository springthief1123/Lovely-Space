package io.github.springthief1123.lovelyspace.ui.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.core.PinState
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.data.PinView
import io.github.springthief1123.lovelyspace.notify.AppNotification
import io.github.springthief1123.lovelyspace.notify.NotificationKind
import io.github.springthief1123.lovelyspace.notify.NotificationTarget
import io.github.springthief1123.lovelyspace.ui.rooms.ActionChip
import io.github.springthief1123.lovelyspace.ui.rooms.RoomCardHeading
import io.github.springthief1123.lovelyspace.ui.theme.LovelyShapes

/** 新着のピン空きだけ画面上へ浮かせる。履歴上の「似た部屋」や条件の一致は対象外。 */
internal fun isPinVacancyNotice(notice: AppNotification): Boolean =
    notice.kind == NotificationKind.WAITLIST && notice.id.startsWith("pin-") && notice.target is NotificationTarget.Room

internal enum class PinVacancyAction { ENTER, REVIEW, UNAVAILABLE }

/**
 * 過去の通知だけを根拠に入室を促さない。保存時の同一性確認に加え、いま取得済みの一覧に
 * 同じ部屋が入室可能として載っている場合だけワンタップ入室を許可する。通信は増やさない。
 */
internal fun pinVacancyAction(notice: AppNotification, pins: List<PinView>): PinVacancyAction {
    val target = notice.target as? NotificationTarget.Room ?: return PinVacancyAction.UNAVAILABLE
    if (!isPinVacancyNotice(notice)) return PinVacancyAction.UNAVAILABLE
    if (!target.verified) return PinVacancyAction.REVIEW
    val pin = pins.firstOrNull {
        it.preference.host == target.host && it.room.genreKey == target.genreKey && it.room.id == target.roomId
    } ?: return PinVacancyAction.UNAVAILABLE
    if (!pin.listed || pin.state != PinState.OPEN || pin.room.action != RoomAction.ENTER ||
        notice.room?.message != pin.room.message
    ) return PinVacancyAction.UNAVAILABLE
    return PinVacancyAction.ENTER
}

/**
 * Vivaldi版の入室ボタン付きトーストを Quiet Rose の不透明なカードで再現する。
 * 通知ベルの履歴は別に残る。カードを閉じても監視を止めない。
 */
@Composable
internal fun PinVacancyToast(
    notice: AppNotification,
    pins: List<PinView>,
    onEnter: () -> Unit,
    onReview: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val action = pinVacancyAction(notice, pins)
    Surface(
        modifier = modifier.widthIn(max = 440.dp).fillMaxWidth().testTag("pin-vacancy-toast"),
        shape = LovelyShapes.menu,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("ピンした部屋が空きました", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "空き通知を閉じる")
                }
            }
            notice.room?.let { room ->
                RoomCardHeading(room, placeholder = "名前の見えない部屋", elapsed = null)
                room.message.takeIf { it.isNotBlank() }?.let { message ->
                    Text(message, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (action) {
                    PinVacancyAction.ENTER -> Text("入室できます", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    PinVacancyAction.REVIEW -> Text("待機メッセージが変更されています", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    PinVacancyAction.UNAVAILABLE -> Text("現在の空きは確認できません", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
                if (action != PinVacancyAction.UNAVAILABLE) {
                    ActionChip(
                        if (action == PinVacancyAction.ENTER) "入室する" else "内容を確認",
                        emphasized = action == PinVacancyAction.ENTER,
                        onClick = if (action == PinVacancyAction.ENTER) onEnter else onReview,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    )
                }
            }
        }
    }
}
