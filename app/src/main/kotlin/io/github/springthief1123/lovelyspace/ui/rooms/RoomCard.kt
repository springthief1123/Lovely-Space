package io.github.springthief1123.lovelyspace.ui.rooms

import androidx.compose.animation.core.animate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Female
import androidx.compose.material.icons.outlined.Male
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
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

/** 部屋カードの待機メッセージの最大行数。表示設定から MainActivity で与える。 */
val LocalRoomMessageMaxLines = compositionLocalOf { 4 }

/**
 * スワイプで開いている部屋カードを1枚に保つ。開いたカードの印だけを持ち、
 * 他のカードは印が自分でなくなったら閉じる。
 */
@Stable
class RoomCardSwipeCoordinator {
    var openToken: Any? by mutableStateOf<Any?>(null)
        private set

    fun open(token: Any) { openToken = token }
    fun closed(token: Any) { if (openToken === token) openToken = null }
    fun closeAll() { openToken = null }
}

/** アプリ内で1つを共有する。画面ごとに分けないので、別画面に開いたカードが残ることもない。 */
val LocalRoomCardSwipeCoordinator = staticCompositionLocalOf { RoomCardSwipeCoordinator() }

/**
 * 開くのに必要な移動量。閉じた状態からは操作幅の45%、開いた状態からは70%未満まで戻すと閉じる。
 * 開いたカードを少し戻すだけで閉じられ、閉じたカードは軽く触れただけでは開かない。
 */
internal fun roomCardRevealThreshold(revealWidthPx: Float, startedOpen: Boolean): Float =
    revealWidthPx * if (startedOpen) CLOSE_FROM_OPEN_FRACTION else OPEN_FROM_CLOSED_FRACTION

/** 操作幅を超えた分は抵抗を付けて動かし、勢い余って確定域へ入りにくくする。 */
internal fun roomCardRubberBand(dragPx: Float, revealWidthPx: Float): Float {
    val magnitude = abs(dragPx)
    if (magnitude <= revealWidthPx) return dragPx
    val resisted = revealWidthPx + (magnitude - revealWidthPx) * OVERDRAG_RESISTANCE
    return if (dragPx > 0f) resisted else -resisted
}

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
    onDetailsClick: (() -> Unit)? = null,
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
    var settleTarget by remember(room.id, room.genreKey) { mutableFloatStateOf(0f) }
    var dragging by remember(room.id, room.genreKey) { mutableStateOf(false) }
    // 開いた状態から始めた操作では反対側へ通り抜けさせない（戻しすぎ防止）。
    var gestureStartSide by remember(room.id, room.genreKey) { mutableFloatStateOf(0f) }
    var lastRootY by remember(room.id, room.genreKey) { mutableStateOf<Float?>(null) }
    val coordinator = LocalRoomCardSwipeCoordinator.current
    val swipeToken = remember(room.id, room.genreKey) { Any() }

    val revealWidthPx = min(
        with(density) { SWIPE_ACTION_WIDTH.toPx() },
        cardWidthPx * MAX_REVEAL_FRACTION,
    )
    val velocityThresholdPx = with(density) { SWIPE_VELOCITY_THRESHOLD.toPx() }
    val commitThresholdPx = max(
        cardWidthPx * MIN_COMMIT_FRACTION,
        cardWidthPx - with(density) { SWIPE_EDGE_REMAINING.toPx() },
    ).coerceAtLeast(revealWidthPx)

    suspend fun animateOffsetTo(target: Float) {
        animate(
            initialValue = offsetPx,
            targetValue = target,
            // 跳ね返りのない減衰で落ち着かせ、戻りすぎ・行きすぎに見えないようにする。
            animationSpec = spring(dampingRatio = 1f, stiffness = SETTLE_STIFFNESS),
        ) { value, _ ->
            offsetPx = value
        }
        dragPositionPx = target
    }

    fun launchSettle(target: Float, after: (() -> Unit)? = null) {
        settleJob?.cancel()
        settleTarget = target
        if (target == 0f) coordinator.closed(swipeToken) else coordinator.open(swipeToken)
        settleJob = scope.launch {
            animateOffsetTo(target)
            after?.invoke()
        }
    }

    // スクロールで一覧から外れたカードが「開いている」まま残らないようにする。
    DisposableEffect(coordinator, swipeToken) { onDispose { coordinator.closed(swipeToken) } }

    // 別のカードが開いたら、このカードは閉じる。
    val openToken = coordinator.openToken
    LaunchedEffect(openToken) {
        if (openToken !== swipeToken && !dragging && settleTarget != 0f) launchSettle(0f)
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
        settleTarget = 0f
        coordinator.closed(swipeToken)
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
        if (gestureStartSide > 0f && next < 0f) next = 0f
        if (gestureStartSide < 0f && next > 0f) next = 0f
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
            else -> roomCardRubberBand(next, revealWidthPx)
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
                .onSizeChanged { cardWidthPx = it.width.toFloat() }
                .onGloballyPositioned { coordinates ->
                    // 一覧をスクロールしたら開いているカードを閉じる。
                    val y = coordinates.positionInRoot().y
                    val previous = lastRootY
                    lastRootY = y
                    if (previous != null && abs(y - previous) > 1f && !dragging && settleTarget != 0f) launchSettle(0f)
                },
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
                            dragging = true
                            coordinator.open(swipeToken)
                            gestureStartSide = when {
                                offsetPx > 0.5f -> 1f
                                offsetPx < -0.5f -> -1f
                                else -> 0f
                            }
                            dragPositionPx = offsetPx
                            commitArmed = cardWidthPx > 0f && abs(dragPositionPx) >= commitThresholdPx
                        },
                        onDragStopped = { velocity ->
                            dragging = false
                            val release = resolveRoomCardSwipeRelease(
                                offsetPx = dragPositionPx,
                                velocityPx = velocity,
                                revealThresholdPx = roomCardRevealThreshold(revealWidthPx, startedOpen = gestureStartSide != 0f),
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
                    detailsAction = onDetailsClick,
                    hiddenAction = onHideClick?.let { { invokeMenuAction(SwipeSide.HIDDEN) } },
                    isFavorite = isFavorite,
                    isHidden = isHidden,
                    enabled = enabled || side != SwipeSide.NONE,
                    onClick = {
                        val other = coordinator.openToken
                        if (other != null && other !== swipeToken) {
                            // 他のカードが開いている間のタップは、そのカードを閉じるだけにする。
                            coordinator.closeAll()
                        } else if (side != SwipeSide.NONE) {
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
    val shape = RoundedCornerShape(16.dp)
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
                        if (isFavorite) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        if (isFavorite) "保存を解除" else "保存",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    Text(
                        if (isHidden) "非表示を解除" else "非表示",
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoomCardSurface(
    room: Room,
    enabled: Boolean,
    onClick: () -> Unit,
    actionsEnabled: Boolean,
    favoriteAction: (() -> Unit)?,
    hiddenAction: (() -> Unit)?,
    detailsAction: (() -> Unit)?,
    isFavorite: Boolean,
    isHidden: Boolean,
) {
    var menuOpen by remember(room.id, room.genreKey) { mutableStateOf(false) }
    val hasMenu = favoriteAction != null || hiddenAction != null || detailsAction != null
    val lovely = LocalLovelyColors.current
    val messageMaxLines = LocalRoomMessageMaxLines.current
    val statusColor = when (room.status) { RoomStatus.WAITING -> lovely.waiting; RoomStatus.PUBLIC_WAITING -> lovely.publicWaiting; RoomStatus.FULL -> lovely.full }
    val (genderColor, genderContainer) = when (room.gender) {
        Gender.FEMALE -> lovely.female to lovely.femaleContainer
        Gender.MALE -> lovely.male to lovely.maleContainer
        Gender.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val name = room.name?.trim()?.takeIf { it.isNotEmpty() }
    // 保存・非表示・詳細は長押しメニューとスワイプに集約し、TalkBack ではカスタムアクションとして出す。
    val a11yActions = buildList {
        detailsAction?.let { action -> add(CustomAccessibilityAction("部屋の詳細") { action(); true }) }
        if (actionsEnabled) {
            favoriteAction?.let { action -> add(CustomAccessibilityAction(if (isFavorite) "保存を解除" else "部屋を保存") { action(); true }) }
            hiddenAction?.let { action -> add(CustomAccessibilityAction(if (isHidden) "非表示を解除" else "非表示にする") { action(); true }) }
        }
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Box {
            Column(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        enabled = enabled || hasMenu,
                        onClick = onClick,
                        onLongClickLabel = if (hasMenu) "部屋の操作" else null,
                        onLongClick = if (hasMenu) ({ menuOpen = true }) else null,
                    )
                    .semantics { if (a11yActions.isNotEmpty()) customActions = a11yActions }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(genderContainer), contentAlignment = Alignment.Center) {
                        if (name != null) {
                            Text(name.substring(0, name.offsetByCodePoints(0, 1)), style = MaterialTheme.typography.titleSmall, color = genderColor)
                        } else {
                            GenderIcon(room.gender, genderColor, Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name ?: "会話中", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                color = if (name == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (isFavorite) {
                                Icon(Icons.Outlined.Bookmark, contentDescription = "保存済み", tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 4.dp).size(16.dp))
                            }
                        }
                        ProfileLine(room, genderColor)
                    }
                    room.elapsed?.let { elapsed ->
                        Text(cardElapsedLabel(room.status, elapsed), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            modifier = Modifier.align(Alignment.Top).padding(start = 8.dp))
                    }
                }
                if (room.message.isNotBlank()) {
                    Text(room.message, style = MaterialTheme.typography.bodyMedium,
                        color = if (room.isFull) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = messageMaxLines, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MetaChip(statusLabel(room.status), statusColor, filled = true)
                    MetaChip(
                        when {
                            room.isFull && room.action == RoomAction.PEEK -> "公開・覗ける"
                            room.isPublic -> "公開"
                            else -> "非公開"
                        },
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        filled = false,
                        icon = if (room.isPublic) Icons.Outlined.Visibility else Icons.Outlined.Lock,
                    )
                }
            }
            if (hasMenu) Box(Modifier.align(Alignment.TopEnd)) {
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    detailsAction?.let { action -> DropdownMenuItem(text = { Text("部屋の詳細") }, onClick = { menuOpen = false; action() }) }
                    favoriteAction?.let { action -> DropdownMenuItem(text = { Text(if (isFavorite) "保存を解除" else "部屋を保存") }, enabled = actionsEnabled, onClick = { menuOpen = false; action() }) }
                    hiddenAction?.let { action -> DropdownMenuItem(text = { Text(if (isHidden) "非表示を解除" else "非表示にする") }, enabled = actionsEnabled, onClick = { menuOpen = false; action() }) }
                }
            }
        }
    }
}

@Composable
private fun ProfileLine(room: Room, genderColor: Color) {
    val rest = listOfNotNull(room.age?.let { "${it}歳" }, room.area).joinToString("・")
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (room.gender != Gender.UNKNOWN) {
            GenderIcon(room.gender, genderColor, Modifier.size(14.dp))
            Text(if (room.gender == Gender.FEMALE) "女性" else "男性", style = MaterialTheme.typography.labelMedium, color = genderColor,
                modifier = Modifier.padding(start = 2.dp))
        }
        if (rest.isNotEmpty() || room.gender == Gender.UNKNOWN) {
            Text(rest.ifEmpty { "プロフィール非公開" }, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = if (room.gender != Gender.UNKNOWN) 6.dp else 0.dp))
        }
    }
}

@Composable
private fun GenderIcon(gender: Gender, tint: Color, modifier: Modifier = Modifier) {
    val icon = when (gender) {
        Gender.FEMALE -> Icons.Outlined.Female
        Gender.MALE -> Icons.Outlined.Male
        Gender.UNKNOWN -> Icons.Outlined.PersonOutline
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier)
}

/** 状態チップと公開設定を同じ高さ・中央揃えで並べ、ベースラインをそろえる。 */
@Composable
private fun MetaChip(text: String, color: Color, filled: Boolean, icon: ImageVector? = null) {
    Row(
        Modifier
            .heightIn(min = 22.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(if (filled) Modifier.background(color.copy(alpha = 0.12f)) else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(12.dp), tint = color) }
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

private fun statusLabel(status: RoomStatus): String = when (status) {
    RoomStatus.WAITING -> "待機中"
    RoomStatus.PUBLIC_WAITING -> "公開待機"
    RoomStatus.FULL -> "満室"
}

/** 一覧の経過時間は待機なら作成からの時間、満室なら会話の時間。 */
internal fun cardElapsedLabel(status: RoomStatus, d: Duration): String {
    val text = formatElapsed(d)
    return when {
        status == RoomStatus.FULL -> "会話 $text"
        d.inWholeMinutes < 1 -> text
        else -> "${text}前"
    }
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
private val SWIPE_VELOCITY_THRESHOLD = 1100.dp
private const val OPEN_FROM_CLOSED_FRACTION = 0.45f
private const val CLOSE_FROM_OPEN_FRACTION = 0.7f
private const val OVERDRAG_RESISTANCE = 0.55f
private const val SETTLE_STIFFNESS = 380f
private val SWIPE_EDGE_REMAINING = 64.dp
private const val MAX_REVEAL_FRACTION = 0.42f
private const val MIN_COMMIT_FRACTION = 0.72f
