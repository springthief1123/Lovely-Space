package io.github.springthief1123.lovelyspace.ui.rooms

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.springthief1123.lovelyspace.ui.theme.LocalLovelyColors
import kotlin.math.max
import kotlin.math.roundToInt

/** 部屋カードの長押しメニューの 1 項目。`separated` の項目は区切り線の下にまとめる。 */
data class RoomMenuItem(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val separated: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 指の位置 [touch] を基準にメニューを置く。横は指の真下を中心に、縦は指の少し下に出し、
 * 画面の下に収まらなければ指の上へ出す。どちらでも [margin] より画面の端へは寄せない。
 */
internal fun roomMenuPosition(touch: IntOffset, menu: IntSize, window: IntSize, margin: Int, gap: Int): IntOffset {
    val x = (touch.x - menu.width / 2).coerceIn(margin, max(margin, window.width - margin - menu.width))
    val below = touch.y + gap
    val above = touch.y - gap - menu.height
    val y = when {
        below + menu.height <= window.height - margin -> below
        above >= margin -> above
        else -> window.height - margin - menu.height
    }.coerceIn(margin, max(margin, window.height - margin - menu.height))
    return IntOffset(x, y)
}

/**
 * 長押しした指の位置に出す部屋の操作メニュー。Material のドロップダウンではなく、
 * 部屋の名前と状態を見出しにした角丸のパネルで、保存・追跡などを直接選べる。
 * [touch] は [visible] を持つ親（カード）の左上からの位置。
 */
@Composable
internal fun RoomActionMenu(
    visible: Boolean,
    touch: Offset,
    title: String,
    subtitle: String,
    items: List<RoomMenuItem>,
    onDismiss: () -> Unit,
) {
    val transition = remember { MutableTransitionState(false) }
    LaunchedEffect(visible) { transition.targetState = visible }
    if (!transition.currentState && !transition.targetState) return

    val density = LocalDensity.current
    val margin = with(density) { MENU_SCREEN_MARGIN.roundToPx() }
    val gap = with(density) { MENU_FINGER_GAP.roundToPx() }
    val provider = remember(touch, margin, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val finger = IntOffset(anchorBounds.left + touch.x.roundToInt(), anchorBounds.top + touch.y.roundToInt())
                return roomMenuPosition(finger, popupContentSize, windowSize, margin, gap)
            }
        }
    }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(
            visibleState = transition,
            enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.92f, transformOrigin = TransformOrigin.Center),
            exit = fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.96f, transformOrigin = TransformOrigin.Center),
        ) {
            RoomActionPanel(title, subtitle, items, onDismiss)
        }
    }
}

@Composable
private fun RoomActionPanel(title: String, subtitle: String, items: List<RoomMenuItem>, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val scheme = MaterialTheme.colorScheme
    // ポップアップは別のウィンドウなので背景のぼかしは使えない。ガラスに近い明るい面と縁取りで見せる。
    Column(
        Modifier
            .padding(8.dp)
            .width(MENU_WIDTH)
            .shadow(16.dp, shape, clip = false)
            .clip(shape)
            .background(scheme.surfaceContainerLow)
            .border(BorderStroke(1.dp, LocalLovelyColors.current.glassBorder), shape)
            .padding(vertical = 6.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val (main, separated) = items.partition { !it.separated }
        Column(Modifier.padding(horizontal = 6.dp)) {
            main.forEach { RoomActionRow(it, onDismiss) }
        }
        if (separated.isNotEmpty()) {
            Spacer(Modifier.padding(horizontal = 18.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
            Column(Modifier.padding(horizontal = 6.dp)) {
                separated.forEach { RoomActionRow(it, onDismiss) }
            }
        }
    }
}

@Composable
private fun RoomActionRow(item: RoomMenuItem, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val alpha = if (item.enabled) 1f else 0.38f
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = item.enabled, role = Role.Button) { onDismiss(); item.onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(item.icon, contentDescription = null, tint = scheme.primary.copy(alpha = alpha), modifier = Modifier.size(20.dp))
        Text(item.label, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface.copy(alpha = alpha),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val MENU_WIDTH = 248.dp
private val MENU_SCREEN_MARGIN = 12.dp
private val MENU_FINGER_GAP = 12.dp
