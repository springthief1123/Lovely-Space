package io.github.springthief1123.lovelyspace.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.core.PinState
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.data.PinView
import io.github.springthief1123.lovelyspace.notify.AppNotification
import io.github.springthief1123.lovelyspace.notify.NotificationKind
import io.github.springthief1123.lovelyspace.notify.NotificationSamples
import io.github.springthief1123.lovelyspace.notify.NotificationTarget
import io.github.springthief1123.lovelyspace.ui.components.LovelyGlassSurface
import io.github.springthief1123.lovelyspace.ui.rooms.ActionChip
import io.github.springthief1123.lovelyspace.ui.theme.LocalLovelyColors
import io.github.springthief1123.lovelyspace.ui.theme.LovelyShapes

/** 新着のピン空きだけ画面上へ浮かせる。履歴上の「似た部屋」や条件の一致は対象外。 */
internal fun isPinVacancyNotice(notice: AppNotification): Boolean =
    notice.kind == NotificationKind.WAITLIST && notice.id.startsWith("pin-") && notice.target is NotificationTarget.Room

/**
 * 空き通知の部屋が「いま」どうなっているか。ピンの見張りが一覧を読み直すたびに変わり、浮遊カードとベルにそのまま出す。
 * 通信は増やさず、取得済みのピンの状態だけで決める（Vivaldi 版の通知カードと同じく、巡回ごとに描き直す）。
 */
internal enum class PinVacancyStatus(val label: String, val badge: String) {
    /** 待機中で、待機メッセージも通知したときと同じ。ワンタップで入れる。 */
    OPEN("入室できます", "空き"),
    /** 空いているが、待機メッセージが登録時・通知時と違う。入室前画面で確かめる。 */
    CHANGED("待機メッセージが変わりました", "要確認"),
    /** 最新の一覧でまだ確かめていない。 */
    UNCHECKED("空きを確認しています", "確認中"),
    FILLED("埋まりました", "満室"),
    CLOSED("部屋が閉じました", "終了"),
    /** ピンを外した・立て直しで別の部屋に移ったなど、ピンの中に見当たらない。 */
    GONE("一覧で見つかりません", "終了");

    val vacant: Boolean get() = this == OPEN || this == CHANGED
    /** もう入れない。少しだけ状態を見せてから浮遊表示を片付ける。 */
    val stale: Boolean get() = this == FILLED || this == CLOSED || this == GONE
}

/**
 * 過去の通知だけを根拠に入室を促さない。保存時の同一性確認に加え、いま取得済みの一覧に
 * 同じ部屋が入室可能として載っている場合だけワンタップ入室を許可する。通信は増やさない。
 */
internal fun pinVacancyStatus(notice: AppNotification, pins: List<PinView>): PinVacancyStatus {
    val target = notice.target as? NotificationTarget.Room ?: return PinVacancyStatus.GONE
    if (!isPinVacancyNotice(notice)) return PinVacancyStatus.GONE
    val pin = pins.firstOrNull {
        it.preference.host == target.host && it.room.genreKey == target.genreKey && it.room.id == target.roomId
    } ?: return PinVacancyStatus.GONE
    return when {
        pin.state == PinState.CLOSED -> PinVacancyStatus.CLOSED
        pin.state == PinState.FULL -> PinVacancyStatus.FILLED
        !pin.listed || pin.state == PinState.UNSEEN -> PinVacancyStatus.UNCHECKED
        pin.room.action != RoomAction.ENTER -> PinVacancyStatus.FILLED
        !target.verified || notice.room?.message != pin.room.message -> PinVacancyStatus.CHANGED
        else -> PinVacancyStatus.OPEN
    }
}

internal enum class PinVacancyAction { ENTER, REVIEW, UNAVAILABLE }

internal fun PinVacancyStatus.action(): PinVacancyAction = when (this) {
    PinVacancyStatus.OPEN -> PinVacancyAction.ENTER
    PinVacancyStatus.CHANGED -> PinVacancyAction.REVIEW
    else -> PinVacancyAction.UNAVAILABLE
}

internal fun pinVacancyAction(notice: AppNotification, pins: List<PinView>): PinVacancyAction =
    pinVacancyStatus(notice, pins).action()

// 速報なので最新3件だけ。展開してもスクロールせずに収まる数にする。
internal const val MAX_FLOATING_PIN_NOTICES = 3
/** 埋まった・閉じたと分かってから、その状態を見せておく時間。過ぎたら浮遊表示から外す。 */
internal const val STALE_LINGER_MS = 6_000L
/** 空いたままでも、この時間を過ぎたカードは片付ける（Vivaldi 版の通知カードと同じ 5 分）。 */
internal const val VACANT_TTL_MS = 5 * 60_000L
/** 設定の「テスト」のサンプルのうち、埋まる様子を見せるものが埋まるまでの時間。 */
internal const val PREVIEW_FILL_MS = 8_000L

/** 最新の空きから最大3件。ベルの記録そのものは削除・変更しない。 */
internal fun floatingPinNotices(ids: List<String>, entries: List<AppNotification>): List<AppNotification> =
    ids.distinct().take(MAX_FLOATING_PIN_NOTICES).mapNotNull { id ->
        entries.firstOrNull { it.id == id && !it.read && isPinVacancyNotice(it) }
    }

/**
 * 浮かせている通知の並び（新しい順）と、表示し始めた時刻・入れなくなった時刻。
 * 同じ部屋がまた空いたら古いカードを残さず新しい通知に置き換え、状態は [tick] で毎秒見直す。
 */
internal data class FloatingNoticeState(
    val ids: List<String> = emptyList(),
    val shownAt: Map<String, Long> = emptyMap(),
    val staleSince: Map<String, Long> = emptyMap(),
) {
    /** 新しい空き通知を前に足す。同じ部屋（[NotificationTarget.Room]）の古いカードは外す。 */
    fun arrive(fresh: List<AppNotification>, targetOf: (String) -> NotificationTarget?, now: Long): FloatingNoticeState {
        if (fresh.isEmpty()) return this
        // verified（通知時に待機メッセージが一致したか）は部屋の識別に使わない。同じ部屋が別の結果で空き直しても置き換える。
        fun NotificationTarget.Room.roomKey() = Triple(host, genreKey, roomId)
        val freshRooms = fresh.mapNotNull { (it.target as? NotificationTarget.Room)?.roomKey() }.toSet()
        val kept = ids.filter { id -> targetOf(id).let { it !is NotificationTarget.Room || it.roomKey() !in freshRooms } }
        val next = (fresh.map { it.id } + kept).distinct().take(MAX_FLOATING_PIN_NOTICES)
        return FloatingNoticeState(next,
            next.associateWith { id -> if (fresh.any { it.id == id }) now else shownAt[id] ?: now },
            staleSince.filterKeys { it in next && it !in fresh.map(AppNotification::id) })
    }

    /** 外から並びが決まる（設定のサンプル）ときに合わせる。初めて見えた時刻を記録する。 */
    fun sync(next: List<String>, now: Long): FloatingNoticeState =
        FloatingNoticeState(next, next.associateWith { shownAt[it] ?: now }, staleSince.filterKeys { it in next })

    fun dismiss(id: String): FloatingNoticeState =
        FloatingNoticeState(ids - id, shownAt - id, staleSince - id)

    /**
     * 状態の変化を記録し、入れなくなって [STALE_LINGER_MS] 経ったもの・空いたまま [VACANT_TTL_MS] 経ったものを外す。
     * [statuses] に無い（既読になった・ベルから消えた）通知も外す。広げて操作している間は指の下の並びを変えない。
     */
    fun tick(statuses: Map<String, PinVacancyStatus>, now: Long, expanded: Boolean): FloatingNoticeState {
        val stale = ids.mapNotNull { id ->
            if (statuses[id]?.stale == true) id to (staleSince[id] ?: now) else null
        }.toMap()
        val next = ids.filter { id ->
            statuses[id] != null && (expanded || run {
                val since = stale[id]
                (since == null || now - since < STALE_LINGER_MS) && now - (shownAt[id] ?: now) < VACANT_TTL_MS
            })
        }
        return FloatingNoticeState(next, shownAt.filterKeys { it in next }, stale.filterKeys { it in next })
    }
}

/** 設定の「テスト」のサンプル。偶数件目は表示してから [PREVIEW_FILL_MS] で埋まり、状態が切り替わる様子を見せる。 */
internal fun previewVacancyStatus(notice: AppNotification, shownAt: Long?, now: Long): PinVacancyStatus {
    val fills = NotificationSamples.floatingPreviewFills(notice)
    return if (fills && shownAt != null && now - shownAt >= PREVIEW_FILL_MS) PinVacancyStatus.FILLED
        else PinVacancyStatus.OPEN
}

/** 「空いてから◯秒」。速報なので秒単位で出す。 */
internal fun vacancyAgeLabel(at: Long, now: Long): String {
    val seconds = ((now - at) / 1000).coerceAtLeast(0)
    return when {
        seconds < 5 -> "いま空いた"
        seconds < 60 -> "${seconds}秒前に空いた"
        else -> "${seconds / 60}分前に空いた"
    }
}

/**
 * ふだんは新着1枚を前に出し、後ろの通知も中身ごと少しずらして控えさせる（前をスワイプすると後ろが見える）。
 * カードの上部（前のカードの見出し・後ろのカードがのぞく縁）を押すと、古い通知が上になるよう広げる。
 * 広げたときだけカードの上に「まとめる」を出す。最大3件なのでスクロールしない。
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
    now: Long = System.currentTimeMillis(),
    statusOf: (AppNotification) -> PinVacancyStatus = { notice ->
        if (preview && NotificationSamples.isSample(notice)) PinVacancyStatus.OPEN else pinVacancyStatus(notice, pins)
    },
) {
    val visible = notices.take(MAX_FLOATING_PIN_NOTICES)
    if (visible.isEmpty()) return
    if (expanded && visible.size > 1) {
        Column(modifier.widthIn(max = 440.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CollapseButton(onClick = { onExpandedChange(false) }, modifier = Modifier.align(Alignment.End))
            // 新着が一番下（ボトムナビに近い位置）。3件までなので全部そのまま並べる。
            for (notice in visible.asReversed()) {
                key(notice.id) {
                    PinVacancyToast(notice, pins, onEnter = { onEnter(notice) },
                        onReview = { onReview(notice) }, onDismiss = { onDismiss(notice) },
                        status = statusOf(notice), now = now)
                }
            }
        }
    } else {
        val depthCount = visible.size
        val expand = if (visible.size > 1) ({ onExpandedChange(true) }) else null
        Box(modifier.widthIn(max = 440.dp).fillMaxWidth().padding(top = if (visible.size > 1) 14.dp else 0.dp)) {
            // 奥から順に描く。後ろのカードも中身を描いておき、前を外したときに白い面が出ないようにする。
            // 同じ通知は key で同じ部品のまま前へ繰り上がり、ずれと縮みをアニメーションで戻す。
            for (depth in depthCount - 1 downTo 0) {
                val notice = visible[depth]
                key(notice.id) {
                    val animatedDepth by animateFloatAsState(depth.toFloat(), label = "floating-stack-depth")
                    val layer = Modifier.graphicsLayer {
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        translationY = -animatedDepth * 7.dp.toPx()
                        val scale = 1f - animatedDepth * 0.05f
                        scaleX = scale
                        scaleY = scale
                    }
                    if (depth == 0) {
                        PinVacancyToast(notice, pins,
                            onEnter = { onEnter(notice) }, onReview = { onReview(notice) },
                            onDismiss = { onDismiss(notice) },
                            modifier = layer,
                            onExpand = expand,
                            extraCount = visible.size - 1,
                            status = statusOf(notice), now = now,
                        )
                    } else {
                        // 控えのカードは見せるだけ。のぞいている縁を押すと広げる。読み上げは前のカードに任せる。
                        PinVacancyCardFace(notice, statusOf(notice), now,
                            modifier = layer.clearAndSetSemantics {}
                                .then(if (expand != null) Modifier.clickable(interactionSource = null, indication = null, onClick = expand) else Modifier),
                            onEnter = {}, onReview = {}, interactive = false)
                    }
                }
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
    status: PinVacancyStatus = if (preview && NotificationSamples.isSample(notice)) PinVacancyStatus.OPEN
        else pinVacancyStatus(notice, pins),
    now: Long = System.currentTimeMillis(),
) {
    val dismissAction by rememberUpdatedState(onDismiss)
    // 指を離してしきい値を越えていたら、その場で外す。端まで滑り切るのを待つと、
    // 毎秒の描き直しと重なってカードが画面端に引っかかったまま残ることがあった。
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value != SwipeToDismissBoxValue.Settled) dismissAction()
        true
    })
    // カード全体はタップに反応させない（押した跡がスワイプ中のカードに残って崩れて見えたため）。
    // 押せるのは「入室する」と、重ねているときの見出し（広げる）だけ。透明度も変えず横に滑らせる。
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier.fillMaxWidth().testTag("pin-vacancy-toast")
            .semantics {
                customActions = listOf(CustomAccessibilityAction("浮いている空き通知を消す") {
                    dismissAction()
                    true
                })
            },
        backgroundContent = {},
    ) {
        PinVacancyCardFace(notice, status, now, onEnter = onEnter, onReview = onReview,
            onExpand = onExpand, extraCount = extraCount)
    }
}

/** 状態ごとの文字色。空きは Quiet Rose の primary、要確認はエラー色、入れなくなったら満室の灰色。 */
@Composable
private fun PinVacancyStatus.accent(): Color {
    val lovely = LocalLovelyColors.current
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        PinVacancyStatus.OPEN -> scheme.primary
        PinVacancyStatus.CHANGED -> scheme.error
        PinVacancyStatus.UNCHECKED -> scheme.onSurfaceVariant
        PinVacancyStatus.FILLED, PinVacancyStatus.CLOSED, PinVacancyStatus.GONE -> lovely.full
    }
}

/**
 * 浮遊カードの中身。トップバー・ボトムナビと同じ背景ブラーの Glass に、Quiet Rose の淡い薄紫（`noticeGlassTint`）を
 * 重ねて、背後の白い部屋カードと区別する。状態は小さなバッジと文言の色だけで示し、変わるとその場で切り替わる。
 */
@Composable
internal fun PinVacancyCardFace(
    notice: AppNotification,
    status: PinVacancyStatus,
    now: Long,
    onEnter: () -> Unit,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
    onExpand: (() -> Unit)? = null,
    extraCount: Int = 0,
    interactive: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val lovely = LocalLovelyColors.current
    val accent by animateColorAsState(status.accent(), label = "floating-status-color")
    val action = status.action()
    LovelyGlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = LovelyShapes.menu,
        contentAlignment = Alignment.CenterStart,
        tint = lovely.noticeGlassTint,
        border = BorderStroke(1.dp, lovely.noticeGlassBorder),
        elevation = 10.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // 重ねているときは、この見出しの行（カードの上部）を押すと広げる。
            val expand = onExpand?.takeIf { interactive && extraCount > 0 }
            Row(Modifier.fillMaxWidth()
                .then(if (expand != null) Modifier.clickable(interactionSource = null, indication = null,
                    onClickLabel = "空き通知をすべて表示", role = Role.Button, onClick = expand) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusBadge(status.badge, accent)
                val name = notice.room?.name?.takeIf { it.isNotBlank() } ?: "名前の見えない部屋"
                Text(name, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = scheme.onSurface)
                if (extraCount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ほか${extraCount}件", style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant)
                        if (expand != null) {
                            Icon(Icons.Outlined.ExpandLess, contentDescription = null,
                                modifier = Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Text(notice.room?.message?.takeIf { it.isNotBlank() } ?: notice.text,
                style = MaterialTheme.typography.labelSmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = scheme.onSurfaceVariant.copy(alpha = if (status.stale) 0.6f else 1f))
            // ボタンの有無で高さが変わると重ねたカードがずれるので、行の高さは押せる大きさで固定する。
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${status.label}・${vacancyAgeLabel(notice.at, now)}",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = accent)
                if (action != PinVacancyAction.UNAVAILABLE) {
                    ActionChip(if (action == PinVacancyAction.ENTER) "入室する" else "内容を確認",
                        emphasized = action == PinVacancyAction.ENTER,
                        onClick = if (!interactive) null else if (action == PinVacancyAction.ENTER) onEnter else onReview,
                        modifier = Modifier.minimumInteractiveComponentSize())
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

/** 広げているときだけカードの上に出す「まとめる」。カードと同じ Glass の小さな丸いボタン。 */
@Composable
private fun CollapseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val lovely = LocalLovelyColors.current
    val scheme = MaterialTheme.colorScheme
    LovelyGlassSurface(
        modifier = modifier.minimumInteractiveComponentSize()
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "空き通知をまとめる", onClick = onClick),
        shape = CircleShape,
        tint = lovely.noticeGlassTint,
        border = BorderStroke(1.dp, lovely.noticeGlassBorder),
    ) {
        Row(Modifier.heightIn(min = 32.dp).padding(start = 14.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("まとめる", style = MaterialTheme.typography.labelMedium, color = scheme.onSurface, maxLines = 1)
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp),
                tint = scheme.onSurfaceVariant)
        }
    }
}
