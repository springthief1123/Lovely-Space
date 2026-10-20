@file:OptIn(ExperimentalLayoutApi::class)

package io.github.springthief1123.lovelyspace.ui.rooms

import androidx.compose.animation.core.animate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Female
import androidx.compose.material.icons.outlined.Male
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import kotlin.math.sign
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

/**
 * 離すと保存・非表示が確定する移動量。カード幅の半分を超えたら確定域に入る。
 * 操作幅より手前にはしない（狭いカードで、開くより先に確定しないように）。
 */
internal fun roomCardCommitThreshold(cardWidthPx: Float, revealWidthPx: Float): Float =
    max(cardWidthPx * COMMIT_FRACTION, revealWidthPx)

/** 背後の「保存」「非表示」の表示の出具合（0〜1）。操作幅まで動かすと出切る。 */
internal fun roomCardSwipeLabelProgress(offsetPx: Float, revealWidthPx: Float): Float =
    if (revealWidthPx <= 0f) 0f else (abs(offsetPx) / revealWidthPx).coerceIn(0f, 1f)

internal enum class RoomCardSwipeAxis { UNDECIDED, HORIZONTAL, VERTICAL }

/**
 * 指の動きを左右のスワイプとみなすか、一覧の縦スクロールに譲るかを決める。
 * 横の移動が触れの遊びを超え、かつ縦の移動の [SWIPE_HORIZONTAL_DOMINANCE] 倍以上（水平から約27°以内）のときだけ左右のスワイプにする。
 * 斜めの動きは縦が遊びを超えた時点でスクロールに譲り、一覧を流すつもりの指でカードが動かないようにする。
 */
internal fun resolveRoomCardSwipeAxis(dx: Float, dy: Float, touchSlopPx: Float): RoomCardSwipeAxis {
    val ax = abs(dx)
    val ay = abs(dy)
    return when {
        ax >= touchSlopPx && ax >= ay * SWIPE_HORIZONTAL_DOMINANCE -> RoomCardSwipeAxis.HORIZONTAL
        ay >= touchSlopPx -> RoomCardSwipeAxis.VERTICAL
        else -> RoomCardSwipeAxis.UNDECIDED
    }
}

/**
 * 操作を始めたときにカードがどちら側に開いているとみなすか（1 保存側・-1 非表示側・0 閉じている）。
 * 閉じる途中や確定後に戻る途中のカードは、止まる先で決める。動いている途中の位置で決めると、
 * 戻りきる前に反対側へ払い直したときに 0 で止められ、操作が取り消されたように見えるため。
 */
internal fun roomCardGestureStartSide(offsetPx: Float, settling: Boolean, settleTargetPx: Float): Float {
    val resting = if (settling) settleTargetPx else offsetPx
    return when {
        resting > 0.5f -> 1f
        resting < -0.5f -> -1f
        else -> 0f
    }
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
    flickMinOffsetPx: Float = 0f,
): RoomCardSwipeRelease {
    if (favoriteEnabled && offsetPx >= commitThresholdPx) return RoomCardSwipeRelease.COMMIT_FAVORITE
    if (hiddenEnabled && offsetPx <= -commitThresholdPx) return RoomCardSwipeRelease.COMMIT_HIDDEN

    // 弾く操作は、指がある程度カードを動かしているときだけ受け付ける。
    // 触れた直後の小さな弾きでカードが指より先へ飛ばないようにするため。
    if (abs(velocityPx) >= velocityThresholdPx && abs(offsetPx) >= flickMinOffsetPx) {
        return when {
            velocityPx > 0f && offsetPx > 0f && favoriteEnabled -> RoomCardSwipeRelease.REVEAL_FAVORITE
            velocityPx < 0f && offsetPx < 0f && hiddenEnabled -> RoomCardSwipeRelease.REVEAL_HIDDEN
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
    /** 長押しメニューで保存の次に並べる操作（追跡・順番待ちなど）。 */
    menuItems: List<RoomMenuItem> = emptyList(),
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
    // 確定後に戻り終わるまで、背後の文言を確定前（ピン／ピンを外すなど）のままにする。
    var commitLabels by remember(room.id, room.genreKey) { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    // 開いた状態から始めた操作では反対側へ通り抜けさせない（戻しすぎ防止）。
    var gestureStartSide by remember(room.id, room.genreKey) { mutableFloatStateOf(0f) }
    // 開いた時点の縦位置。ここから一定以上動いたらスクロールとみなして閉じる。
    var openedRootY by remember(room.id, room.genreKey) { mutableStateOf<Float?>(null) }
    val coordinator = LocalRoomCardSwipeCoordinator.current
    val swipeToken = remember(room.id, room.genreKey) { Any() }

    val revealWidthPx = min(
        with(density) { SWIPE_ACTION_WIDTH.toPx() },
        cardWidthPx * MAX_REVEAL_FRACTION,
    )
    val velocityThresholdPx = with(density) { SWIPE_VELOCITY_THRESHOLD.toPx() }
    val flickMinOffsetPx = with(density) { SWIPE_FLICK_MIN_OFFSET.toPx() }
    val scrollCloseDistancePx = with(density) { SCROLL_CLOSE_DISTANCE.toPx() }
    val commitThresholdPx = roomCardCommitThreshold(cardWidthPx, revealWidthPx)

    suspend fun animateOffsetTo(
        target: Float,
        // 跳ね返りのない減衰で落ち着かせ、戻りすぎ・行きすぎに見えないようにする。
        animationSpec: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = SETTLE_STIFFNESS),
    ) {
        animate(
            initialValue = offsetPx,
            targetValue = target,
            animationSpec = animationSpec,
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
        commitLabels = isFavorite to isHidden
        commitArmed = true
        settleJob = scope.launch {
            var acted = false
            try {
                // 端へ短く払い切ってすぐ戻す。ばねで落ち着かせると端で止まって見える時間が長くなるため、時間を決めて動かす。
                animateOffsetTo(target, tween(COMMIT_OUT_MILLIS, easing = LinearOutSlowInEasing))
                action()
                acted = true
                commitArmed = false
                animateOffsetTo(0f, tween(COMMIT_RETURN_MILLIS, easing = FastOutSlowInEasing))
            } finally {
                // 戻る途中で掴み直されたり一覧から外れたりしても、確定した操作は取り消さない。
                if (!acted) action()
                commitLabels = null
            }
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
        } else if (!nowArmed && commitArmed) {
            // 指を戻して確定域を抜けたら、取り消せたことを軽い振動で知らせる。
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }

        // カードは常に指の位置どおりに動かす。確定域に入ったことは触覚と背景色で知らせ、
        // 指を戻して確定域を抜ければそのまま取り消せる。
        offsetPx = next
        commitArmed = nowArmed
    }

    val swipeEnabled = favoriteEnabled || hiddenEnabled
    // 指の操作は pointerInput(Unit) の中で続けて扱うので、途中で保存中などに変わっても操作を打ち切らないよう最新の値を読む。
    val currentSwipeEnabled by rememberUpdatedState(swipeEnabled)
    val onSwipeStarted by rememberUpdatedState {
        val settling = settleJob?.isActive == true
        settleJob?.cancel()
        dragging = true
        coordinator.open(swipeToken)
        gestureStartSide = roomCardGestureStartSide(offsetPx, settling, settleTarget)
        dragPositionPx = offsetPx
        commitArmed = cardWidthPx > 0f && abs(dragPositionPx) >= commitThresholdPx
    }
    val onSwipeStopped by rememberUpdatedState { velocity: Float ->
        dragging = false
        val release = resolveRoomCardSwipeRelease(
            offsetPx = dragPositionPx,
            velocityPx = velocity,
            revealThresholdPx = roomCardRevealThreshold(revealWidthPx, startedOpen = gestureStartSide != 0f),
            commitThresholdPx = commitThresholdPx,
            velocityThresholdPx = velocityThresholdPx,
            favoriteEnabled = favoriteEnabled,
            hiddenEnabled = hiddenEnabled,
            flickMinOffsetPx = flickMinOffsetPx,
        )
        commitArmed = false
        when (release) {
            RoomCardSwipeRelease.CLOSED -> launchSettle(0f)
            RoomCardSwipeRelease.REVEAL_FAVORITE -> launchSettle(revealWidthPx)
            RoomCardSwipeRelease.REVEAL_HIDDEN -> launchSettle(-revealWidthPx)
            RoomCardSwipeRelease.COMMIT_FAVORITE -> commitFullSwipe(SwipeSide.FAVORITE)
            RoomCardSwipeRelease.COMMIT_HIDDEN -> commitFullSwipe(SwipeSide.HIDDEN)
        }
    }

    // 指の動きのたびにカード全体を組み立て直さないよう、どちら側が出ているかが変わったときだけ読み直す。
    // 位置そのものは offset と graphicsLayer が配置・描画のときに読む。
    val side by remember(room.id, room.genreKey) { derivedStateOf {
        when {
            offsetPx > 0.5f -> SwipeSide.FAVORITE
            offsetPx < -0.5f -> SwipeSide.HIDDEN
            else -> SwipeSide.NONE
        }
    } }

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
                    // 一覧をスクロールしたら開いているカードを閉じる。閉じているカードでは位置を求めない（スクロール中に毎回呼ばれるため）。
                    if (dragging || settleTarget == 0f) {
                        if (openedRootY != null) openedRootY = null
                    } else {
                        val y = coordinates.positionInRoot().y
                        val anchor = openedRootY ?: y.also { openedRootY = it }
                        if (abs(y - anchor) > scrollCloseDistancePx) launchSettle(0f)
                    }
                },
        ) {
            SwipeActionBackground(
                side = side,
                armed = commitArmed,
                isFavorite = commitLabels?.first ?: isFavorite,
                isHidden = commitLabels?.second ?: isHidden,
                enabled = side != SwipeSide.NONE,
                labelProgress = { roomCardSwipeLabelProgress(offsetPx, revealWidthPx) },
                onClick = { invokeMenuAction(side) },
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(offsetPx.roundToInt(), 0) }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (!currentSwipeEnabled) return@awaitEachGesture
                            val slop = viewConfiguration.touchSlop
                            val velocityTracker = VelocityTracker()
                            velocityTracker.addPosition(down.uptimeMillis, down.position)
                            var total = Offset.Zero
                            // 縦横が決まるまでは何も消費せず、タップ・長押し・一覧のスクロールに任せる。
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                    ?: return@awaitEachGesture
                                if (!change.pressed || change.isConsumed) return@awaitEachGesture
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                total += change.positionChange()
                                val axis = resolveRoomCardSwipeAxis(total.x, total.y, slop)
                                if (axis == RoomCardSwipeAxis.VERTICAL) return@awaitEachGesture
                                if (axis == RoomCardSwipeAxis.HORIZONTAL) {
                                    change.consume()
                                    break
                                }
                            }
                            onSwipeStarted()
                            // 遊びを超えた分だけ動かし始める（触れた位置からカードが跳ばないように）。
                            dragState.dispatchRawDelta(total.x - sign(total.x) * slop)
                            var velocity = 0f
                            try {
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    // 他の操作に取られたら、その位置で離したのと同じに扱う。
                                    if (change.isConsumed) break
                                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                                    if (!change.pressed) {
                                        velocity = velocityTracker.calculateVelocity().x
                                        break
                                    }
                                    val dx = change.positionChange().x
                                    change.consume()
                                    if (dx != 0f) dragState.dispatchRawDelta(dx)
                                }
                            } finally {
                                onSwipeStopped(velocity)
                            }
                        }
                    },
            ) {
                RoomCardSurface(
                    room = room,
                    actionsEnabled = actionsEnabled,
                    favoriteAction = onFavoriteClick?.let { { invokeMenuAction(SwipeSide.FAVORITE) } },
                    detailsAction = onDetailsClick,
                    extraMenuItems = menuItems,
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
    armed: Boolean,
    isFavorite: Boolean,
    isHidden: Boolean,
    enabled: Boolean,
    labelProgress: () -> Float,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val isFavoriteSide = side == SwipeSide.FAVORITE
    val scheme = MaterialTheme.colorScheme
    // 離すと確定する位置まで来たら、背景を濃い色に切り替えて知らせる。
    val container = when {
        isFavoriteSide && armed -> scheme.primary
        isFavoriteSide -> scheme.primaryContainer
        armed -> scheme.inverseSurface
        else -> scheme.surfaceContainerHigh
    }
    val content = when {
        isFavoriteSide && armed -> scheme.onPrimary
        isFavoriteSide -> scheme.onPrimaryContainer
        armed -> scheme.inverseOnSurface
        else -> scheme.onSurface
    }
    Box(
        Modifier
            .matchParentSize()
            .clip(shape)
            .background(container)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = if (isFavoriteSide) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        if (enabled) {
            Row(
                // スワイプ量に合わせて、カード端から開く方向へ滑りながら現れる。
                // 位置は描画時に読み、指の動きごとに組み立て直さない。
                Modifier.graphicsLayer {
                    val progress = labelProgress()
                    alpha = progress
                    val slide = (1f - progress) * SWIPE_LABEL_SLIDE.toPx()
                    translationX = if (isFavoriteSide) -slide else slide
                },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isFavoriteSide) {
                    Icon(
                        if (isFavorite) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = null,
                        tint = if (armed) content else scheme.primary,
                    )
                    Text(
                        if (isFavorite) "ピンを外す" else "ピン",
                        style = MaterialTheme.typography.labelLarge,
                        color = content,
                    )
                } else {
                    Text(
                        if (isHidden) "非表示を解除" else "非表示",
                        style = MaterialTheme.typography.labelLarge,
                        color = content,
                    )
                    Icon(
                        Icons.Outlined.VisibilityOff,
                        contentDescription = null,
                        tint = if (armed) content else scheme.onSurfaceVariant,
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
    extraMenuItems: List<RoomMenuItem>,
    isFavorite: Boolean,
    isHidden: Boolean,
) {
    var menuOpen by remember(room.id, room.genreKey) { mutableStateOf(false) }
    // 長押しした指の位置。メニューをそこへ出す。
    var pressAt by remember(room.id, room.genreKey) { mutableStateOf(Offset.Zero) }
    val menuItems = buildList {
        detailsAction?.let { add(RoomMenuItem("部屋の詳細", Icons.Outlined.Info, onClick = it)) }
        favoriteAction?.let {
            add(RoomMenuItem(if (isFavorite) "ピンを外す" else "ピンする",
                if (isFavorite) Icons.Filled.PushPin else Icons.Outlined.PushPin, enabled = actionsEnabled, onClick = it))
        }
        addAll(extraMenuItems)
        hiddenAction?.let {
            add(RoomMenuItem(if (isHidden) "非表示を解除" else "非表示にする",
                if (isHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, enabled = actionsEnabled, separated = true, onClick = it))
        }
    }
    val hasMenu = menuItems.isNotEmpty()
    val lovely = LocalLovelyColors.current
    val messageMaxLines = LocalRoomMessageMaxLines.current
    val statusColor = when (room.status) { RoomStatus.WAITING -> lovely.waiting; RoomStatus.PUBLIC_WAITING -> lovely.publicWaiting; RoomStatus.FULL -> lovely.full }
    val (genderColor, genderContainer) = when (room.gender) {
        Gender.FEMALE -> lovely.female to lovely.femaleContainer
        Gender.MALE -> lovely.male to lovely.maleContainer
        Gender.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val name = room.name?.trim()?.takeIf { it.isNotEmpty() }
    // 部屋の操作は長押しメニューとスワイプに集約し、TalkBack ではカスタムアクションとして出す。
    val a11yActions = menuItems.filter { it.enabled }.map { item -> CustomAccessibilityAction(item.label) { item.onClick(); true } }
    // 満室の部屋は面を背景と同じ色に沈め、空き枠のある部屋だけが浮いて見えるようにする。
    // 背後のスワイプの面が透けないよう、透明にはせず背景色で塗る。性別の色は満室でも残す。
    Surface(shape = RoundedCornerShape(16.dp),
        color = if (room.isFull) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Box {
            Column(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        awaitEachGesture { pressAt = awaitFirstDown(requireUnconsumed = false).position }
                    }
                    .combinedClickable(
                        enabled = enabled || hasMenu,
                        onClick = onClick,
                        onLongClickLabel = if (hasMenu) "部屋の操作" else null,
                        onLongClick = if (hasMenu) ({ menuOpen = true }) else null,
                    )
                    .semantics {
                        if (a11yActions.isNotEmpty()) customActions = a11yActions
                        if (isFavorite) stateDescription = "ピン済み"
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // ピンの印と重ならないよう、見出しの行はその幅だけ内側で終える。
                Row(Modifier.padding(end = if (isFavorite) SAVED_RIBBON_CLEARANCE else 0.dp),
                    verticalAlignment = Alignment.CenterVertically) {
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
                // 文字を大きくして 1 行に収まらないときは、動きの表示を次の行へ送る（切れないように）。
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    // 押したときの動き（入室する・覗く）を角丸のチップで先に示す。満室で非公開の部屋は詳細が開くだけなので付けない。
                    cardActionLabel(room.action)?.let { label ->
                        Box(Modifier.weight(1f).align(Alignment.CenterVertically), contentAlignment = Alignment.CenterEnd) {
                            ActionChip(label, emphasized = room.action == RoomAction.ENTER)
                        }
                    }
                }
            }
            if (hasMenu) RoomActionMenu(
                visible = menuOpen,
                touch = pressAt,
                title = name ?: "会話中の部屋",
                subtitle = listOfNotNull(
                    statusLabel(room.status),
                    when (room.gender) { Gender.FEMALE -> "女性"; Gender.MALE -> "男性"; Gender.UNKNOWN -> null },
                    room.age?.let { "${it}歳" },
                    room.area,
                ).joinToString(" · "),
                items = menuItems,
                onDismiss = { menuOpen = false },
            )
            if (isFavorite) {
                SavedRibbon(Modifier.align(Alignment.TopEnd).padding(end = SAVED_RIBBON_END))
            }
        }
    }
}

/** ピンした部屋の右上に下げるしおり。読み上げはカード本体の状態（ピン済み）で伝える。 */
@Composable
private fun SavedRibbon(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = SAVED_RIBBON_WIDTH, height = SAVED_RIBBON_HEIGHT)
            .clip(SavedRibbonShape)
            .background(MaterialTheme.colorScheme.primary),
    )
}

/** 上辺から下がり、下端が V 字に切れたしおりの形。 */
private val SavedRibbonShape = GenericShape { size, _ ->
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height)
    lineTo(size.width / 2f, size.height * 0.74f)
    lineTo(0f, size.height)
    close()
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

/**
 * カードを押したときの動きを示す角丸のチップ。押す先はカード全体なので、チップ自体は押せない表示にとどめる。
 * 入れる部屋は `primary` で塗り、覗くだけの部屋は枠だけにして、遠目でも入れるかどうかを分ける。
 */
@Composable
private fun ActionChip(text: String, emphasized: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .heightIn(min = 30.dp)
            .clip(CircleShape)
            .then(if (emphasized) Modifier.background(scheme.primary) else Modifier.border(1.dp, scheme.outline, CircleShape))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Medium,
            color = if (emphasized) scheme.onPrimary else scheme.onSurfaceVariant, maxLines = 1)
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
private const val OPEN_FROM_CLOSED_FRACTION = 0.35f
private const val CLOSE_FROM_OPEN_FRACTION = 0.7f
private val SWIPE_FLICK_MIN_OFFSET = 24.dp
// 横の移動が縦の何倍以上なら左右のスワイプとみなすか（2 で水平から約27°以内）。
private const val SWIPE_HORIZONTAL_DOMINANCE = 2f
// 確定したカードを端へ払い切る時間と、そこから戻す時間。
private const val COMMIT_OUT_MILLIS = 110
private const val COMMIT_RETURN_MILLIS = 200
private val SCROLL_CLOSE_DISTANCE = 12.dp
private const val SETTLE_STIFFNESS = 380f
private const val MAX_REVEAL_FRACTION = 0.42f
private const val COMMIT_FRACTION = 0.5f
private val SWIPE_LABEL_SLIDE = 32.dp
private val SAVED_RIBBON_WIDTH = 14.dp
private val SAVED_RIBBON_HEIGHT = 20.dp
private val SAVED_RIBBON_END = 14.dp
private val SAVED_RIBBON_CLEARANCE = SAVED_RIBBON_WIDTH + 6.dp

/** カードを押したときの動きの短い表示。詳細が開くだけの部屋は null。 */
internal fun cardActionLabel(action: RoomAction): String? = when (action) {
    RoomAction.ENTER -> "入室する"
    RoomAction.PEEK -> "覗く"
    RoomAction.NONE -> null
}
