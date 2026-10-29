package io.github.springthief1123.lovelyspace.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import io.github.springthief1123.lovelyspace.ui.theme.LocalLovelyBottomContentInset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.notify.unreadCount
import io.github.springthief1123.lovelyspace.notify.AppNotification
import io.github.springthief1123.lovelyspace.lock.LocalAppLocked
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import io.github.springthief1123.lovelyspace.Routes
import io.github.springthief1123.lovelyspace.ui.components.*
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpacing

data class MainDestination(val route: String, val label: String, val icon: ImageVector)

val MainDestinations = listOf(
    MainDestination(Routes.ROOMS, "見つける", Icons.Outlined.Explore),
    MainDestination(Routes.RADAR, "レーダー", Icons.Outlined.Radar),
    MainDestination(Routes.PROFILE, "マイルーム", Icons.Outlined.PersonOutline),
)

/** 「会話に戻る」の帯がナビゲーションの上にあるときは、その分だけ一覧の下を空ける。 */
internal fun lovelyBottomContentInset(navigationHeight: Dp, showResumeBar: Boolean = false) =
    maxOf(LovelySpacing.bottomContentInset, navigationHeight + if (showResumeBar) 80.dp else 0.dp)

/** 「見つける」を表示中の再タップだけ、タブ遷移せず一覧の先頭へ戻す。 */
internal fun shouldScrollFindTabToTop(currentRoute: String?, destinationRoute: String): Boolean =
    destinationRoute == Routes.ROOMS && (currentRoute == Routes.ROOMS || currentRoute == Routes.SEARCH)

/** 新着件数は「見つける」の上だけに出し、検索シート中は重ねない。 */
internal fun shouldShowNewRoomsBadge(currentRoute: String?, newCount: Int, searchOpen: Boolean): Boolean =
    (currentRoute == Routes.ROOMS || currentRoute == Routes.SEARCH) && newCount > 0 && !searchOpen

/** サンプルは実際の表示位置を確かめる「見つける」でのみ表示する。 */
internal fun shouldShowFloatingPreview(currentRoute: String?, count: Int): Boolean =
    (currentRoute == Routes.ROOMS || currentRoute == Routes.SEARCH) && count > 0

/** 会話では実測入力欄の高さ（システムナビ領域を含む）を使い、増えた行や復旧操作を覆わない。 */
internal fun floatingNoticeBottomOffset(
    route: String?, showChrome: Boolean, navigationHeight: Dp, showResumeBar: Boolean,
    chatBottomBarHeight: Dp = 0.dp,
): Dp =
    if (showChrome) navigationHeight + 8.dp + if (showResumeBar) 72.dp else 0.dp
    else if (route == Routes.CHAT) chatBottomBarHeight + 8.dp
    else if (route == Routes.CREATE || route == Routes.ENTRY) 80.dp
    else 16.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LovelyAppShell(
    currentRoute: String?, showChrome: Boolean,
    onDestinationSelected: (MainDestination) -> Unit,
    resumeBar: (@Composable () -> Unit)? = null,
    onOpenNotificationSettings: () -> Unit = {},
    floatingPreviewNotices: List<AppNotification> = emptyList(),
    onDismissFloatingPreview: (AppNotification) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val hazeState = remember { HazeState() }
    val density = LocalDensity.current
    var navigationHeight by remember { mutableStateOf(100.dp) }
    val bottomInset = lovelyBottomContentInset(navigationHeight, showChrome && resumeBar != null)
    val shellState = remember { LovelyShellState() }
    var notificationsOpen by remember { mutableStateOf(false) }
    // 外枠を出さない画面（チャットなど）へ移ったら閉じる。
    LaunchedEffect(showChrome) { if (!showChrome) notificationsOpen = false }
    val panelOpen = showChrome && notificationsOpen
    ProvideLovelyHazeState(hazeState) {
        Box(Modifier.fillMaxSize()) {
            // お知らせを開いている間は、後ろの画面・上のバー・下のナビへキーボード・D-pad のフォーカスも読み上げも入らないようにする
            // （パネルの外の面はタッチしか止めない。見つける画面の検索シートと同じ）。
            Box(Modifier.fillMaxSize().then(if (panelOpen) Modifier.clearAndSetSemantics {}.focusProperties { enter = { FocusRequester.Cancel } }.focusGroup() else Modifier)) {
            Surface(Modifier.fillMaxSize().hazeSource(state = hazeState),
                color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                CompositionLocalProvider(LocalLovelyBottomContentInset provides bottomInset, LocalLovelyShellState provides shellState) { content() }
            }
            if (showChrome) {
                LovelyTopBar(onOpenBell = { notificationsOpen = !notificationsOpen }, shellState.searchButton, Modifier.align(Alignment.TopCenter))
                LovelyBottomNavigation(if (currentRoute == Routes.SEARCH) Routes.ROOMS else currentRoute,
                    onSelect = { destination ->
                        if (shouldScrollFindTabToTop(currentRoute, destination.route)) shellState.scrollToTop?.onClick?.invoke()
                        else onDestinationSelected(destination)
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                    onHeight = { navigationHeight = with(density) { it.toDp() } })
                if (resumeBar != null) Box(Modifier.align(Alignment.BottomStart).navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = navigationHeight + 12.dp)) { resumeBar() }
                // 新着件数だけトップバー直下へ固定表示する。検索シートを開いている間は隠す。
                // 消えるアニメーション中の表示内容は、最後に表示した件数を使う。
                val scrollToTop = shellState.scrollToTop
                val lastNewRooms = remember { arrayOfNulls<ShellScrollToTop>(1) }
                val showNewRooms = shouldShowNewRoomsBadge(currentRoute, scrollToTop?.newCount ?: 0,
                    shellState.searchButton?.open == true)
                if (showNewRooms) lastNewRooms[0] = scrollToTop
                AnimatedVisibility(showNewRooms, enter = fadeIn() + scaleIn(initialScale = 0.9f),
                    exit = fadeOut() + scaleOut(targetScale = 0.9f),
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding()
                        .padding(top = LovelySpacing.topBarHeight + 8.dp)) {
                    val newCount = lastNewRooms[0]?.newCount ?: 0
                    LovelyGlassPillButton("新着 ${newCount}件", Icons.Outlined.ArrowUpward,
                        onClick = { shellState.scrollToTop?.onClick?.invoke() })
                }
            }
            }
            if (currentRoute != null) {
                // 実際の空き通知はメインタブだけでなく設定・会話・作成など全ページの外枠に重ねる。
                val app = LocalContext.current.applicationContext as LovelySpaceApp
                val notifications by app.notificationInbox.entries.collectAsStateWithLifecycle()
                val pins by app.pins.pins.collectAsStateWithLifecycle()
                val locked = LocalAppLocked.current
                // 起動前の履歴は再表示せず、新着の空きだけを最大5件まで前面に保つ。
                // フリックで消すのは表示だけ。通知ベルの履歴には触れない。
                val toastStartedAt = remember { System.currentTimeMillis() }
                var handledToastIds by remember { mutableStateOf(emptySet<String>()) }
                var floatingIds by remember { mutableStateOf(emptyList<String>()) }
                var stackExpanded by remember { mutableStateOf(false) }
                var previewExpanded by remember { mutableStateOf(false) }
                LaunchedEffect(notifications, notificationsOpen, locked) {
                    val now = System.currentTimeMillis()
                    val fresh = notifications.filter {
                        isPinVacancyNotice(it) && !it.read && it.id !in handledToastIds &&
                            it.at >= toastStartedAt && it.at <= now && now - it.at <= 10_000L
                    }
                    if (fresh.isNotEmpty()) {
                        handledToastIds = handledToastIds + fresh.map { it.id }
                        if (!notificationsOpen && !locked) {
                            floatingIds = (fresh.map { it.id } + floatingIds).distinct()
                                .take(MAX_FLOATING_PIN_NOTICES)
                        }
                    }
                    if (notificationsOpen || locked) {
                        floatingIds = emptyList()
                        stackExpanded = false
                    }
                }
                val floatingNotices = floatingPinNotices(floatingIds, notifications)
                LaunchedEffect(floatingIds, stackExpanded) {
                    // 広げて操作している間は自動で消さない。畳むと15秒で片付ける。
                    if (floatingIds.isNotEmpty() && !stackExpanded) {
                        kotlinx.coroutines.delay(15_000L)
                        floatingIds = emptyList()
                    }
                }
                if (floatingNotices.size <= 1 && stackExpanded) {
                    LaunchedEffect(floatingNotices.size) { stackExpanded = false }
                }
                if (floatingNotices.isNotEmpty() && !notificationsOpen && !locked) {
                    PinVacancyToastStack(
                        notices = floatingNotices,
                        pins = pins,
                        expanded = stackExpanded,
                        onExpandedChange = { stackExpanded = it },
                        onEnter = { notice ->
                            floatingIds = floatingIds - notice.id
                            app.notifier.open(notice.id, enter = true)
                        },
                        onReview = { notice ->
                            floatingIds = floatingIds - notice.id
                            app.notifier.open(notice.id)
                        },
                        onDismiss = { notice ->
                            floatingIds = floatingIds - notice.id
                            if (floatingIds.size <= 1) stackExpanded = false
                        },
                        // 各ページの下部UIとIMEを避けながら中央に表示する。
                        // 会話入力欄の実測高にはnavigationBarsPaddingが含まれるため、ここでは二重に足さない。
                        modifier = Modifier.align(Alignment.BottomCenter).imePadding()
                            .then(if (currentRoute == Routes.CHAT) Modifier else Modifier.navigationBarsPadding())
                            .padding(start = 16.dp, end = 16.dp,
                                bottom = floatingNoticeBottomOffset(currentRoute, showChrome, navigationHeight,
                                    resumeBar != null, shellState.chatBottomBarHeight)),
                    )
                } else if (shouldShowFloatingPreview(currentRoute, floatingPreviewNotices.size) && !notificationsOpen && !locked) {
                    // 設定で積んだサンプルを見つける画面だけで描く。履歴・通信・実際の入室には接続しない。
                    PinVacancyToastStack(
                        notices = floatingPreviewNotices,
                        pins = emptyList(),
                        expanded = previewExpanded,
                        onExpandedChange = { previewExpanded = it },
                        onEnter = { android.widget.Toast.makeText(app, "サンプル表示です。実際の入室は行いません", android.widget.Toast.LENGTH_SHORT).show() },
                        onReview = { android.widget.Toast.makeText(app, "サンプル表示です。実際の部屋は開きません", android.widget.Toast.LENGTH_SHORT).show() },
                        onDismiss = { notice ->
                            onDismissFloatingPreview(notice)
                            if (floatingPreviewNotices.size <= 2) previewExpanded = false
                        },
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                            .padding(start = 16.dp, end = 16.dp, bottom = navigationHeight + 8.dp),
                        preview = true,
                    )
                }
                NotificationOverlay(
                    visible = showChrome && notificationsOpen,
                    entries = notifications,
                    pins = pins,
                    onDismiss = { notificationsOpen = false },
                    onOpen = { notificationsOpen = false; app.notifier.open(it.id) },
                    onEnter = { notificationsOpen = false; app.notifier.open(it.id, enter = true) },
                    onRemove = { app.notifier.dismiss(it.id) },
                    onMarkAllRead = { app.notificationInbox.markAllRead() },
                    onOpenSettings = { notificationsOpen = false; onOpenNotificationSettings() },
                )
            }
        }
    }
}

@Composable
private fun LovelyTopBar(onOpenBell: () -> Unit, searchButton: ShellSearchButton?, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val notifications by app.notificationInbox.entries.collectAsStateWithLifecycle()
    val unread = notifications.unreadCount
    // 時間がたった空き枠・一致のお知らせは、画面を見ている間も 1 分ごとに片付ける（未読の数にも残さない）。
    LaunchedEffect(Unit) {
        while (true) {
            app.notificationInbox.removeExpired()
            kotlinx.coroutines.delay(60_000)
        }
    }
    val rose = MaterialTheme.colorScheme.primary
    LovelyGlassSurface(modifier.fillMaxWidth(), RectangleShape) {
        Row(Modifier.statusBarsPadding().fillMaxWidth().height(LovelySpacing.topBarHeight).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(28.dp, 32.dp)) {
                val stroke = Stroke(1.2.dp.toPx())
                drawOval(rose, topLeft = Offset(0f, size.height * 0.14f), size = Size(size.width * 0.64f, size.height * 0.72f), style = stroke)
                drawOval(rose, topLeft = Offset(size.width * 0.34f, size.height * 0.14f), size = Size(size.width * 0.64f, size.height * 0.72f), style = stroke)
            }
            Text("Lovely Space", modifier = Modifier.weight(1f).padding(start = 10.dp),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // 「見つける」を開いている間はいつも、ベルの左隣に検索ボタンを出す。押すと検索シートがこのバーの下から降りてくる。
            AnimatedVisibility(searchButton != null, enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut(targetScale = 0.8f)) {
                val active = searchButton?.activeCount ?: 0
                val open = searchButton?.open == true
                IconButton(onClick = { searchButton?.onClick?.invoke() },
                    colors = if (open) IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.primary) else IconButtonDefaults.iconButtonColors()) {
                    BadgedBox(badge = { if (active > 0 && !open) Badge { Text("$active") } }) {
                        Icon(if (open) Icons.Outlined.Close else Icons.Outlined.Search,
                            contentDescription = when {
                                open -> "検索を閉じる"
                                active > 0 -> "検索（条件${active}件）"
                                else -> "検索"
                            })
                    }
                }
            }
            IconButton(onClick = { app.notificationInbox.removeExpired(); onOpenBell() }) {
                BadgedBox(badge = { if (unread > 0) Badge { Text(if (unread > 99) "99+" else "$unread") } }) {
                    RingingBell(unread)
                }
            }
        }
    }
}

@Composable
internal fun LovelyBottomNavigation(currentRoute: String?, onSelect: (MainDestination) -> Unit, modifier: Modifier = Modifier, onHeight: (Int) -> Unit = {}) {
    Box(modifier.widthIn(max = 720.dp).fillMaxWidth().navigationBarsPadding().onSizeChanged { onHeight(it.height) }.padding(horizontal = 12.dp, vertical = 10.dp)) {
        LovelyGlassSurface(Modifier.fillMaxWidth(), RoundedCornerShape(24.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(6.dp)) {
                var rowHeight by remember { mutableStateOf(56.dp) }
                val density = LocalDensity.current
                val itemWidth = maxWidth / MainDestinations.size
                val index = MainDestinations.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
                val x by animateDpAsState(itemWidth * index + 3.dp,
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = 420f), label = "nav-selection")
                Box(Modifier.offset(x = x).width(itemWidth - 6.dp).height(rowHeight)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(18.dp)))
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).onSizeChanged { rowHeight = with(density) { it.height.toDp() } }) {
                    MainDestinations.forEach { destination ->
                        val selected = currentRoute == destination.route
                        val interaction = remember { MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "nav-press")
                        Column(Modifier.weight(1f).fillMaxHeight().heightIn(min = 56.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                            .selectable(selected, interactionSource = interaction, indication = null, role = Role.Tab,
                                onClick = { onSelect(destination) }).padding(vertical = 7.dp, horizontal = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            Icon(destination.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                            Text(destination.label, style = MaterialTheme.typography.labelSmall, color = color)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 通知ベルのアイコン。未読があればベルを塗りつぶし、未読が増えたとき（と、未読のある画面を開いたとき）に左右に揺らす。
 * 揺れは上の付け根を軸にして、本物のベルのように鳴らす。
 */
@Composable
private fun RingingBell(unread: Int) {
    val angle = remember { Animatable(0f) }
    var previous by remember { mutableIntStateOf(0) }
    LaunchedEffect(unread) {
        val grew = unread > previous
        previous = unread
        if (!grew) return@LaunchedEffect
        angle.snapTo(0f)
        angle.animateTo(0f, keyframes {
            durationMillis = BELL_RING_MS
            18f at 80
            -16f at 200
            12f at 320
            -9f at 440
            5f at 560
            -2f at 660
        })
    }
    Icon(if (unread > 0) Icons.Filled.Notifications else Icons.Outlined.NotificationsNone,
        contentDescription = if (unread > 0) "お知らせ（未読${unread}件）" else "お知らせ",
        modifier = Modifier.graphicsLayer {
            rotationZ = angle.value
            transformOrigin = TransformOrigin(0.5f, 0.12f)
        })
}

private const val BELL_RING_MS = 760
