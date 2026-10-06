package io.github.springthief1123.lovelyspace.ui.rooms

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.core.RoomStatus
import io.github.springthief1123.lovelyspace.ui.theme.LocalLovelyColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration

private enum class SwipeSide { NONE, FAVORITE, HIDDEN }

internal enum class RoomCardSwipeRelease {
    CLOSED,
    REVEAL_FAVORITE,
    REVEAL_HIDDEN,
    COMMIT_FAVORITE,
    COMMIT_HIDDEN,
}

internal fun resolveRoomCardSwipeRelease(
    offsetPx: Float,
    velocityPx: Float,
    revealThresholdPx: Float,
    commitThresholdPx: Float,
    velocityThresholdPx: Float,
    favoriteEnabled: Boolean,
    hiddenEnabled: Boolean,
): RoomCardSwipeRelease {
    if (favoriteEnabled && offsetPx >= commitThresholdPx) return RoomCardSwipeRelease.COMMIT_FAVORITE
    if (hiddenEnabled && offsetPx <= -commitThresholdPx) return RoomCardSwipeRelease.COMMIT_HIDDEN

    if (abs(velocityPx) >= velocityThresholdPx) {
        return when {
            velocityPx > 0f && offsetPx >= 0f && favoriteEnabled -> RoomCardSwipeRelease.REVEAL_FAVORITE
            velocityPx < 0f && offsetPx <= 0f && hiddenEnabled -> RoomCardSwipeRelease.REVEAL_HIDDEN
            else -> RoomCardSwipeRelease.CLOSED
        }
    }

    return when {
        favoriteEnabled && offsetPx >= revealThresholdPx -> RoomCardSwipeRelease.REVEAL_FAVORITE
        hiddenEnabled && offsetPx <= -revealThresholdPx -> RoomCardSwipeRelease.REVEAL_HIDDEN
        else -> RoomCardSwipeRelease.CLOSED
    }
}

@Composable
fun RoomCard(
    room: Room,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isFavorite: Boolean = false,
    isHidden: Boolean = false,
    onFavoriteClick: (() -> Unit)? = null,
    onHideClick: (() -> Unit)? = null,
    actionsEnabled: Boolean = true,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val favoriteEnabled = actionsEnabled && onFavoriteClick != null
    val hiddenEnabled = actionsEnabled && onHideClick != null

    var cardWidthPx by remember(room.id, room.genreKey) { mutableFloatStateOf(0f) }
    var offsetPx by remember(room.id, room.genreKey) { mutableFloatStateOf(0f) }
    // 指の実移動量。確定域では見た目だけ端へ吸着させ、これを残すことで逆方向へ戻して取り消せる。
    var dragPositionPx by remember(room.id, room.genreKey) { mutableFloatStateOf(0f) }
    var commitArmed by remember(room.id, room.genreKey) { mutableStateOf(false) }
    var settleJob by remember(room.id, room.genreKey) { mutableStateOf<Job?>(null) }

    val revealWidthPx = min(
        with(density) { SWIPE_ACTION_WIDTH.toPx() },
        cardWidthPx * MAX_REVEAL_FRACTION,
    )
    val revealThresholdPx = with(density) { SWIPE_REVEAL_THRESHOLD.toPx() }
    val velocityThresholdPx = with(density) { SWIPE_VELOCITY_THRESHOLD.toPx() }
    val commitThresholdPx = max(
        cardWidthPx * MIN_COMMIT_FRACTION,
        cardWidthPx - with(density) { SWIPE_EDGE_REMAINING.toPx() },
    ).coerceAtLeast(revealWidthPx)

    suspend fun animateOffsetTo(target: Float) {
        animate(
            initialValue = offsetPx,
            targetValue = target,
            animationSpec = spring(dampingRatio = 0.86f, stiffness = 620f),
        ) { value, _ ->
            offsetPx = value
        }
        dragPositionPx = target
    }

    fun launchSettle(target: Float, after: (() -> Unit)? = null) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animateOffsetTo(target)
            after?.invoke()
        }
    }

    fun invokeMenuAction(side: SwipeSide) {
        val action = when (side) {
            SwipeSide.FAVORITE -> onFavoriteClick
            SwipeSide.HIDDEN -> onHideClick
            SwipeSide.NONE -> null
        } ?: return

        action()
        commitArmed = false
        launchSettle(0f)
    }

    fun commitFullSwipe(side: SwipeSide) {
        val action = when (side) {
            SwipeSide.FAVORITE -> onFavoriteClick
            SwipeSide.HIDDEN -> onHideClick
            SwipeSide.NONE -> null
        } ?: return

        val target = when (side) {
            SwipeSide.FAVORITE -> cardWidthPx
            SwipeSide.HIDDEN -> -cardWidthPx
            SwipeSide.NONE -> 0f
        }
        settleJob?.cancel()
        settleJob = scope.launch {
            animateOffsetTo(target)
            action()
            commitArmed = false
            animateOffsetTo(0f)
        }
    }

    val dragState = rememberDraggableState { delta ->
        val maxOffset = cardWidthPx.coerceAtLeast(0f)
        var next = (dragPositionPx + delta).coerceIn(-maxOffset, maxOffset)
        if (next > 0f && !favoriteEnabled) next = 0f
        if (next < 0f && !hiddenEnabled) next = 0f
        dragPositionPx = next

        val nowArmed = cardWidthPx > 0f && when {
            next >= commitThresholdPx && favoriteEnabled -> true
            next <= -commitThresholdPx && hiddenEnabled -> true
            else -> false
        }
        if (nowArmed && !commitArmed) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }

        // 確定域へ入った瞬間だけ前面カードを端へ吸着させる。
        // 指を戻して確定域を抜ければ、実移動量へ戻るためそのまま取り消せる。
        offsetPx = when {
            nowArmed && next > 0f -> maxOffset
            nowArmed && next < 0f -> -maxOffset
            else -> next
        }
        commitArmed = nowArmed
    }

    val side = when {
        offsetPx > 0.5f -> SwipeSide.FAVORITE
        offsetPx < -0.5f -> SwipeSide.HIDDEN
        else -> SwipeSide.NONE
    }

    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { cardWidthPx = it.width.toFloat() },
        ) {
            SwipeActionBackground(
                side = side,
                isFavorite = isFavorite,
                isHidden = isHidden,
                enabled = side != SwipeSide.NONE,
                onClick = { invokeMenuAction(side) },
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(offsetPx.roundToInt(), 0) }
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Horizontal,
                        enabled = favoriteEnabled || hiddenEnabled,
                        onDragStarted = {
                            settleJob?.cancel()
                            dragPositionPx = offsetPx
                            commitArmed = cardWidthPx > 0f && abs(dragPositionPx) >= commitThresholdPx
                        },
                        onDragStopped = { velocity ->
                            val release = resolveRoomCardSwipeRelease(
                                offsetPx = dragPositionPx,
                                velocityPx = velocity,
                                revealThresholdPx = revealThresholdPx,
                                commitThresholdPx = commitThresholdPx,
                                velocityThresholdPx = velocityThresholdPx,
                                favoriteEnabled = favoriteEnabled,
                                hiddenEnabled = hiddenEnabled,
                            )
                            commitArmed = false
                            when (release) {
                                RoomCardSwipeRelease.CLOSED -> launchSettle(0f)
                                RoomCardSwipeRelease.REVEAL_FAVORITE -> launchSettle(revealWidthPx)
                                RoomCardSwipeRelease.REVEAL_HIDDEN -> launchSettle(-revealWidthPx)
                                RoomCardSwipeRelease.COMMIT_FAVORITE -> commitFullSwipe(SwipeSide.FAVORITE)
                                RoomCardSwipeRelease.COMMIT_HIDDEN -> commitFullSwipe(SwipeSide.HIDDEN)
                            }
                        },
                    ),
            ) {
                RoomCardSurface(
                    room = room,
                    actionsEnabled = actionsEnabled,
                    favoriteAction = onFavoriteClick?.let { { invokeMenuAction(SwipeSide.FAVORITE) } },
                    hiddenAction = onHideClick?.let { { invokeMenuAction(SwipeSide.HIDDEN) } },
                    isFavorite = isFavorite,
                    isHidden = isHidden,
                    enabled = enabled || side != SwipeSide.NONE,
                    onClick = {
                        if (side != SwipeSide.NONE) {
                            launchSettle(0f)
                        } else if (enabled) {
                            onClick()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun BoxScope.SwipeActionBackground(
    side: SwipeSide,
    isFavorite: Boolean,
    isHidden: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val isFavoriteSide = side == SwipeSide.FAVORITE
    Box(
        Modifier
            .matchParentSize()
            .clip(shape)
            .background(
                if (isFavoriteSide) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = if (isFavoriteSide) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        if (enabled) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isFavoriteSide) {
                    Icon(
                        if (isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        if (isFavorite) "お気に入り解除" else "お気に入り",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    Text(
                        if (isHidden) "非表示解除" else "非表示",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Icon(
                        Icons.Outlined.VisibilityOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RoomCardSurface(
    room: Room,
    enabled: Boolean,
    onClick: () -> Unit,
    actionsEnabled: Boolean,
    favoriteAction: (() -> Unit)?,
    hiddenAction: (() -> Unit)?,
    isFavorite: Boolean,
    isHidden: Boolean,
) {
    var menuOpen by remember(room.id, room.genreKey) { mutableStateOf(false) }
    val lovely = LocalLovelyColors.current
    val genderColor = when (room.gender) {
        Gender.FEMALE -> lovely.female
        Gender.MALE -> lovely.male
        Gender.UNKNOWN -> MaterialTheme.colorScheme.outline
    }
    val statusColor = when (room.status) {
        RoomStatus.WAITING -> lovely.waiting
        RoomStatus.PUBLIC_WAITING -> lovely.publicWaiting
        RoomStatus.FULL -> lovely.full
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .height(IntrinsicSize.Min)
                .padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(statusColor.copy(alpha = 0.88f)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = room.name ?: "2ショットチャット中",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (room.name == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    val profile = buildString {
                        if (room.gender == Gender.FEMALE) append("女")
                        if (room.gender == Gender.MALE) append("男")
                        room.age?.let {
                            if (isNotEmpty()) append(" ")
                            append(it).append("歳")
                        }
                    }
                    if (profile.isNotEmpty()) {
                        Text(
                            profile,
                            style = MaterialTheme.typography.labelMedium,
                            color = genderColor,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Text(
                        statusLabel(room.status),
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                    if (favoriteAction != null || hiddenAction != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }, enabled = actionsEnabled) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "部屋の操作")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                favoriteAction?.let { action ->
                                    DropdownMenuItem(
                                        text = { Text(if (isFavorite) "お気に入り解除" else "お気に入り") },
                                        enabled = actionsEnabled,
                                        onClick = { menuOpen = false; action() },
                                    )
                                }
                                hiddenAction?.let { action ->
                                    DropdownMenuItem(
                                        text = { Text(if (isHidden) "非表示解除" else "非表示") },
                                        enabled = actionsEnabled,
                                        onClick = { menuOpen = false; action() },
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    room.area?.let { MetaText(it) }
                    if (!room.isPublic) {
                        MetaIcon(Icons.Outlined.Lock, "非公開")
                    }
                    if (room.action == RoomAction.PEEK) {
                        MetaIcon(Icons.Outlined.Visibility, "覗ける")
                    }
                    room.elapsed?.let { MetaText(formatElapsed(it)) }
                }

                if (room.message.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        room.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaText(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MetaIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        MetaText(label)
    }
}

private fun statusLabel(status: RoomStatus): String = when (status) {
    RoomStatus.WAITING -> "待機中"
    RoomStatus.PUBLIC_WAITING -> "公開待機"
    RoomStatus.FULL -> "満室"
}

internal fun formatElapsed(d: Duration): String {
    val h = d.inWholeHours
    val m = d.inWholeMinutes % 60
    return when {
        h > 0 -> "${h}時間${m}分"
        m > 0 -> "${m}分"
        else -> "たった今"
    }
}

private val SWIPE_ACTION_WIDTH = 116.dp
private val SWIPE_REVEAL_THRESHOLD = 36.dp
private val SWIPE_VELOCITY_THRESHOLD = 720.dp
private val SWIPE_EDGE_REMAINING = 64.dp
private const val MAX_REVEAL_FRACTION = 0.42f
private const val MIN_COMMIT_FRACTION = 0.72f
