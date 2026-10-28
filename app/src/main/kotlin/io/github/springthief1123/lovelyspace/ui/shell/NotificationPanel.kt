package io.github.springthief1123.lovelyspace.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.data.formatObservationTime
import io.github.springthief1123.lovelyspace.data.PinView
import io.github.springthief1123.lovelyspace.lock.LocalAppLocked
import io.github.springthief1123.lovelyspace.notify.AppNotification
import io.github.springthief1123.lovelyspace.notify.NotificationKind
import io.github.springthief1123.lovelyspace.notify.NotificationSamples
import io.github.springthief1123.lovelyspace.notify.NotificationTarget
import io.github.springthief1123.lovelyspace.notify.unreadCount
import io.github.springthief1123.lovelyspace.ui.components.LovelyGlassSurface
import io.github.springthief1123.lovelyspace.ui.rooms.ActionChip
import io.github.springthief1123.lovelyspace.ui.rooms.MetaChip
import io.github.springthief1123.lovelyspace.ui.rooms.RoomCardHeading
import io.github.springthief1123.lovelyspace.ui.theme.LovelyShapes
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpacing
import kotlin.math.abs

/**
 * 通知ベルを開いたときのお知らせ。上のバーの少し下に、画面の左右中央で開く。
 * 上のバー・下のナビと同じく背後の画面をぼかすため（Haze は同じ窓の中でしかぼかせない）、Popup ではなく
 * 画面の外枠（[LovelyAppShell]）の上に重ねて描く。Back・パネルの外のタップで閉じる。
 */
@Composable
internal fun NotificationOverlay(
    visible: Boolean,
    entries: List<AppNotification>,
    onDismiss: () -> Unit,
    onOpen: (AppNotification) -> Unit,
    onEnter: (AppNotification) -> Unit,
    onRemove: (AppNotification) -> Unit,
    onMarkAllRead: () -> Unit,
    onOpenSettings: () -> Unit,
    pins: List<PinView>? = null,
) {
    val locked = LocalAppLocked.current
    LaunchedEffect(locked) { if (locked && visible) onDismiss() }
    BackHandler(enabled = visible, onBack = onDismiss)
    val transition = remember { MutableTransitionState(false) }
    LaunchedEffect(visible, locked) { transition.targetState = visible && !locked }
    if (!transition.currentState && !transition.targetState) return
    Box(Modifier.fillMaxSize()) {
        // パネルの外のタップで閉じる（下の画面には届かせない）。
        if (visible) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } })
        // 標準のメニュー（QuietDropdownMenu）と同じく、ベル（右上）の位置から拡大して開く（開く 120ms・閉じる 75ms、0.8 倍から）。
        val origin = TransformOrigin(BELL_PIVOT_X, 0f)
        AnimatedVisibility(
            visibleState = transition,
            enter = scaleIn(tween(120, easing = LinearOutSlowInEasing), initialScale = 0.8f, transformOrigin = origin) + fadeIn(tween(30)),
            exit = scaleOut(tween(75), targetScale = 0.8f, transformOrigin = origin) + fadeOut(tween(75)),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = LovelySpacing.topBarHeight + PANEL_GAP),
        ) {
            NotificationPanel(entries, onOpen, onEnter, onRemove, onMarkAllRead, onOpenSettings, pins)
        }
    }
}

/** お知らせの一覧。新しい順に並べ、未読には印を付ける。左右どちらかへスワイプすると、その 1 件を消す。 */
@Composable
internal fun NotificationPanel(
    entries: List<AppNotification>,
    onOpen: (AppNotification) -> Unit,
    onEnter: (AppNotification) -> Unit,
    onRemove: (AppNotification) -> Unit,
    onMarkAllRead: () -> Unit,
    onOpenSettings: () -> Unit,
    pins: List<PinView>? = null,
) {
    val configuration = LocalConfiguration.current
    // 画面の左右に 12dp ずつ残し、タブレット・横向きでは読みやすい幅で止める。
    val width = (configuration.screenWidthDp.dp - 24.dp).coerceAtMost(440.dp)
    // パネル全体（見出し・設定ボタン込み）は画面の高さの [PANEL_HEIGHT_FRACTION] までにし、件数が多くても画面を埋めずに一覧の中でスクロールする。
    // 横向き・分割画面・文字拡大でも、ベル（上のバー）より下の空きに収める。
    val maxHeight = minOf(configuration.screenHeightDp.dp * PANEL_HEIGHT_FRACTION, configuration.screenHeightDp.dp - PANEL_TOP_RESERVE)
        .coerceAtLeast(160.dp)
    // 上のバー・下のナビと同じガラスの面（背後の画面をぼかす）。
    LovelyGlassSurface(Modifier.padding(bottom = 12.dp).width(width).heightIn(max = maxHeight)
        // パネルの中のタップを外側（閉じる）に渡さない。
        .pointerInput(Unit) { detectTapGestures { } }, shape = LovelyShapes.menu) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("お知らせ", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (entries.unreadCount > 0) TextButton(onClick = onMarkAllRead) { Text("すべて既読") }
            }
            if (entries.isEmpty()) {
                Text("お知らせはまだありません", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f, fill = false),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        SwipeToRemove(onRemove = { onRemove(entry) }, modifier = Modifier.animateItem()) {
                            NotificationCard(entry, onClick = { onOpen(entry) }, onEnter = { onEnter(entry) }, pins = pins)
                        }
                    }
                }
                Text("左右にスワイプすると消せます", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 18.dp, top = 6.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 4.dp))
            TextButton(onClick = onOpenSettings, modifier = Modifier.padding(start = 6.dp)) { Text("通知の設定") }
        }
    }
}

/** 上のバーとパネルの間。バーに密着させない。 */
private val PANEL_GAP = 8.dp

/** パネルの幅に対するベルの位置（拡大の起点）。ベルは上のバーの右端にある。 */
private const val BELL_PIVOT_X = 0.9f

/** 上のバー（ステータスバー込み）とパネルの下の余白の分。パネルの高さはこれを引いた画面の高さまで。 */
private val PANEL_TOP_RESERVE = 120.dp

/** パネルの高さの上限（画面の高さに対する割合）。下の一覧が少し見えたままにして、画面を埋め尽くさない。 */
private const val PANEL_HEIGHT_FRACTION = 0.6f

/**
 * 左右どちらへスワイプしても [onRemove] する。背後には何も出さず、カードを動かした量に応じて薄くする。
 * スワイプできない読み上げでは、操作の一覧から消せる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToRemove(onRemove: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val currentOnRemove by rememberUpdatedState(onRemove)
    val state = rememberSwipeToDismissBoxState()
    LaunchedEffect(state.currentValue) {
        if (state.currentValue != SwipeToDismissBoxValue.Settled) currentOnRemove()
    }
    var width by remember { mutableIntStateOf(0) }
    SwipeToDismissBox(
        state = state,
        modifier = modifier.onSizeChanged { width = it.width }.semantics {
            customActions = listOf(CustomAccessibilityAction("お知らせを消す") { currentOnRemove(); true })
        },
        backgroundContent = {},
    ) {
        Box(Modifier.graphicsLayer {
            val offset = runCatching { state.requireOffset() }.getOrDefault(0f)
            alpha = if (width > 0) (1f - abs(offset) / width).coerceIn(0f, 1f) else 1f
        }) { content() }
    }
}

/**
 * お知らせ 1 件のカード。部屋のお知らせは、見つける画面の部屋カードと同じ見出し（名前・性別・年齢）と待機メッセージで、
 * 誰のどんな部屋かを先に見せる。カードを押すと入室前画面（部屋でなければその画面）を開き、「入室する」は確認を飛ばして入る。
 */
@Composable
private fun NotificationCard(entry: AppNotification, onClick: () -> Unit, onEnter: () -> Unit, pins: List<PinView>?) {
    val room = entry.room
    val roomTarget = entry.target as? NotificationTarget.Room
    val scheme = MaterialTheme.colorScheme
    NoticeCardSurface(Modifier.testTag("notification-${entry.id}")) {
        Column(
            Modifier.clickable(onClickLabel = if (roomTarget != null) "入室前の画面を開く" else "開く", onClick = onClick)
                .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = if (roomTarget != null) 4.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 何のお知らせか（と未読の印・時刻）を 1 行目に小さく出す。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(end = 6.dp).size(7.dp).background(if (entry.read) Color.Transparent else scheme.primary, CircleShape))
                Text(entry.displayTitle, style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (entry.read) FontWeight.Normal else FontWeight.SemiBold,
                    color = if (entry.read) scheme.onSurfaceVariant else scheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(formatObservationTime(entry.at), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp))
            }
            if (room != null) {
                RoomCardHeading(room, placeholder = "名前の見えない部屋", elapsed = null)
                room.message.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Text(entry.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                entry.message?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (roomTarget != null || room != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (Genres[roomTarget?.genreKey ?: room?.genreKey.orEmpty()]?.label)?.let {
                        MetaChip(it, scheme.onSurfaceVariant, filled = false)
                    }
                    // ピン・順番待ちで待機メッセージが登録時と違う（別の人の部屋かもしれない）ときだけ添える。
                    if (entry.kind == NotificationKind.WAITLIST && roomTarget != null && !roomTarget.verified) {
                        MetaChip("待機メッセージ変更", scheme.error, filled = true)
                    }
                    Spacer(Modifier.weight(1f))
                    // 見つける画面・レーダーと同じ「入室する」チップ。押せる範囲は 48dp を取る。
                    // テスト通知は見た目だけ出し、押してもカードと同じ（本家の部屋は開かない）。
                    if (roomTarget != null) {
                        // 過去の空き通知で誤って入室を促さない。ピンの最新の観測状態で判定する。
                        when (if (pins != null && isPinVacancyNotice(entry)) pinVacancyAction(entry, pins) else null) {
                            PinVacancyAction.REVIEW -> ActionChip("内容を確認", emphasized = false,
                                onClick = onClick, modifier = Modifier.minimumInteractiveComponentSize())
                            PinVacancyAction.UNAVAILABLE -> Text("今は空きを確認できません",
                                style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            else -> ActionChip("入室する", emphasized = true, onClick = onEnter,
                                modifier = Modifier.minimumInteractiveComponentSize())
                        }
                    } else if (NotificationSamples.isSample(entry)) {
                        ActionChip("入室する", emphasized = true, onClick = onClick, onClickLabel = "開く（テスト通知）",
                            modifier = Modifier.minimumInteractiveComponentSize())
                    }
                }
            }
        }
    }
}

/**
 * お知らせカードの面。部屋カードと同じ角丸・縁取りで、ガラスのパネルが少し透けるよう面を半透明にする
 * （ぼかしはパネルが受け持つ。カードごとにはぼかさない）。
 */
@Composable
private fun NoticeCardSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(shape = LovelyShapes.panel, color = MaterialTheme.colorScheme.surface.copy(alpha = NOTICE_CARD_ALPHA), tonalElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = modifier.fillMaxWidth(), content = content)
}

/** お知らせカードの面の濃さ。文字が読める濃さを保ちつつ、背後のガラスが少し透ける。 */
private const val NOTICE_CARD_ALPHA = 0.6f
