package io.github.springthief1123.lovelyspace.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.core.PinState
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.data.PinView
import io.github.springthief1123.lovelyspace.notify.AppNotification
import io.github.springthief1123.lovelyspace.notify.NotificationKind
import io.github.springthief1123.lovelyspace.notify.NotificationSamples
import io.github.springthief1123.lovelyspace.ui.components.LovelyGlassSurface
import io.github.springthief1123.lovelyspace.notify.NotificationTarget
import io.github.springthief1123.lovelyspace.ui.rooms.ActionChip
import io.github.springthief1123.lovelyspace.ui.theme.LovelyShapes
import kotlin.math.abs

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

internal const val MAX_FLOATING_PIN_NOTICES = 5

/** 最新の空きから最大5件。ベルの記録そのものは削除・変更しない。 */
internal fun floatingPinNotices(ids: List<String>, entries: List<AppNotification>): List<AppNotification> =
    ids.distinct().take(MAX_FLOATING_PIN_NOTICES).mapNotNull { id ->
        entries.firstOrNull { it.id == id && !it.read && isPinVacancyNotice(it) }
    }

/**
 * ふだんは1枚だけ表示し、複数あるときは背面に少しずらして重ねる。
 * カードをタップすると古い通知が上になるよう展開。長い一覧はスクロールできる。
 */
@Composable
internal fun PinVacancyToastStack(
    notices: List<AppNotification>,
    pins: List<PinView>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onEnter: (AppNotification) -> Unit,
    onReview: (AppNotification) -> Unit,
    onDismiss: (AppNotification) -> Unit,
    modifier: Modifier = Modifier,
    preview: Boolean = false,
) {
    val visible = notices.take(MAX_FLOATING_PIN_NOTICES)
    if (visible.isEmpty()) return
    val stackHeight = LocalConfiguration.current.screenHeightDp.dp * 0.56f
    if (expanded && visible.size > 1) {
        Column(modifier.widthIn(max = 440.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("ピンの空き通知 ${visible.size}件", style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { onExpandedChange(false) }) { Text("まとめる") }
            }
            // 新着が一番下。展開したときだけ画面の半分強まで伸ばし、超過分はスクロールする。
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = stackHeight - 48.dp),
                reverseLayout = true, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(visible, key = { it.id }) { notice ->
                    PinVacancyToast(notice, pins, onEnter = { onEnter(notice) },
                        onReview = { onReview(notice) }, onDismiss = { onDismiss(notice) }, preview = preview)
                }
            }
        }
    } else {
        Box(modifier.widthIn(max = 440.dp).fillMaxWidth().padding(top = if (visible.size > 1) 12.dp else 0.dp)) {
            // 固定高さで描くと文字サイズ変更時に崩れるため、表のカードと同じ大きさを使う。
            if (visible.size > 1) {
                for (depth in minOf(visible.size - 1, 2) downTo 1) {
                    LovelyGlassSurface(
                        modifier = Modifier.matchParentSize().offset(y = (-depth * 5).dp)
                            .padding(horizontal = (depth * 7).dp),
                        shape = LovelyShapes.control,
                    ) {}
                }
            }
            val first = visible.first()
            // 先頭がスワイプで消えたらDismiss状態を次の部屋に引き継がない。
            key(first.id) {
                PinVacancyToast(first, pins,
                    onEnter = { onEnter(first) }, onReview = { onReview(first) },
                    onDismiss = { onDismiss(first) },
                    onExpand = if (visible.size > 1) ({ onExpandedChange(true) }) else null,
                    extraCount = visible.size - 1,
                    preview = preview,
                )
            }
        }
    }
}

/** スワイプで浮遊表示だけを外す。通知ベルのお知らせは呼び出し元で保持する。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PinVacancyToast(
    notice: AppNotification,
    pins: List<PinView>,
    onEnter: () -> Unit,
    onReview: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onExpand: (() -> Unit)? = null,
    extraCount: Int = 0,
    preview: Boolean = false,
) {
    // 設定画面のサンプルは見た目のみ。コールバックは実際の入室・通信につながない。
    val action = if (preview && NotificationSamples.isSample(notice)) PinVacancyAction.ENTER
        else pinVacancyAction(notice, pins)
    val dismissAction by rememberUpdatedState(onDismiss)
    val swipe = rememberSwipeToDismissBoxState()
    LaunchedEffect(swipe.currentValue) {
        if (swipe.currentValue != SwipeToDismissBoxValue.Settled) dismissAction()
    }
    var width by remember { mutableIntStateOf(0) }
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier.fillMaxWidth().testTag("pin-vacancy-toast")
            .onSizeChanged { width = it.width }
            .semantics {
                customActions = listOf(CustomAccessibilityAction("浮いている空き通知を消す") {
                    dismissAction()
                    true
                })
            },
        backgroundContent = {},
    ) {
        Box(Modifier.graphicsLayer {
            val offset = runCatching { swipe.requireOffset() }.getOrDefault(0f)
            alpha = if (width > 0) (1f - abs(offset) / width).coerceIn(0f, 1f) else 1f
        }) {
            LovelyGlassSurface(
                modifier = Modifier.fillMaxWidth().then(
                    if (onExpand != null) Modifier.clickable(onClickLabel = "空き通知を広げる", onClick = onExpand)
                    else Modifier
                ),
                shape = LovelyShapes.control,
                contentAlignment = Alignment.CenterStart,
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val name = notice.room?.name?.takeIf { it.isNotBlank() } ?: "名前の見えない部屋"
                        Text("${name}の部屋が空きました", modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
                        if (extraCount > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text("ほか${extraCount}件", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(notice.room?.message?.takeIf { it.isNotBlank() } ?: notice.text,
                        style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(when (action) {
                            PinVacancyAction.ENTER -> "入室できます"
                            PinVacancyAction.REVIEW -> "待機メッセージ変更"
                            PinVacancyAction.UNAVAILABLE -> "現在の空きは未確認"
                        }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (action == PinVacancyAction.REVIEW) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (action != PinVacancyAction.UNAVAILABLE) {
                            ActionChip(if (action == PinVacancyAction.ENTER) "入室する" else "内容を確認",
                                emphasized = action == PinVacancyAction.ENTER,
                                onClick = if (action == PinVacancyAction.ENTER) onEnter else onReview,
                                modifier = Modifier.minimumInteractiveComponentSize())
                        }
                    }
                }
            }
        }
    }
}
