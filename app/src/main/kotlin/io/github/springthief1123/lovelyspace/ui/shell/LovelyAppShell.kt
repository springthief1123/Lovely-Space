package io.github.springthief1123.lovelyspace.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.roundToIntRect
import io.github.springthief1123.lovelyspace.ui.theme.LocalLovelyBottomContentInset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.notify.unreadCount
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
    MainDestination(Routes.FAVORITES, "保存", Icons.Outlined.BookmarkBorder),
    MainDestination(Routes.PROFILE, "マイルーム", Icons.Outlined.PersonOutline),
)

/** FAB や「会話に戻る」の帯がナビゲーションの上に並ぶときは、その分だけ一覧の下を空ける。 */
internal fun lovelyBottomContentInset(navigationHeight: Dp, showCreateFab: Boolean, showResumeBar: Boolean = false) =
    maxOf(LovelySpacing.bottomContentInset, navigationHeight + if (showCreateFab || showResumeBar) 80.dp else 0.dp)

@Composable
fun LovelyAppShell(
    currentRoute: String?, showChrome: Boolean,
    onDestinationSelected: (MainDestination) -> Unit,
    showCreateFab: Boolean, onCreateRoom: () -> Unit,
    resumeBar: (@Composable () -> Unit)? = null,
    onOpenNotificationSettings: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val hazeState = remember { HazeState() }
    val density = LocalDensity.current
    var navigationHeight by remember { mutableStateOf(100.dp) }
    val bottomInset = lovelyBottomContentInset(navigationHeight, showCreateFab, showChrome && resumeBar != null)
    val shellState = remember { LovelyShellState() }
    ProvideLovelyHazeState(hazeState) {
        Box(Modifier.fillMaxSize()) {
            Surface(Modifier.fillMaxSize().hazeSource(state = hazeState),
                color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                CompositionLocalProvider(LocalLovelyBottomContentInset provides bottomInset, LocalLovelyShellState provides shellState) { content() }
            }
            if (showChrome) {
                LovelyTopBar(onOpenNotificationSettings, shellState.searchButton, Modifier.align(Alignment.TopCenter))
                LovelyBottomNavigation(if (currentRoute == Routes.SEARCH) Routes.ROOMS else currentRoute,
                    onDestinationSelected, Modifier.align(Alignment.BottomCenter), onHeight = { navigationHeight = with(density) { it.toDp() } })
                if (resumeBar != null) Box(Modifier.align(Alignment.BottomStart).navigationBarsPadding()
                    .padding(start = 16.dp, end = if (showCreateFab) 92.dp else 16.dp, bottom = navigationHeight + 12.dp)) { resumeBar() }
                // 「トップへ戻る」はボトムナビの上の中央に浮かせる。外枠に置くので後ろの一覧がぼける。
                // 消えるアニメーションの間も文言を保つため、最後に出した内容を覚えておく。
                val scrollToTop = shellState.scrollToTop
                val lastScrollToTop = remember { arrayOfNulls<ShellScrollToTop>(1) }
                if (scrollToTop != null) lastScrollToTop[0] = scrollToTop
                AnimatedVisibility(scrollToTop != null, enter = fadeIn() + scaleIn(initialScale = 0.9f),
                    exit = fadeOut() + scaleOut(targetScale = 0.9f),
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                        .padding(bottom = navigationHeight + 12.dp + if (resumeBar != null) 72.dp else 0.dp)) {
                    val newCount = lastScrollToTop[0]?.newCount ?: 0
                    LovelyGlassPillButton(if (newCount > 0) "新着 ${newCount}件" else "トップへ戻る", Icons.Outlined.ArrowUpward,
                        onClick = { scrollToTop?.onClick?.invoke() })
                }
                AnimatedVisibility(showCreateFab, enter = fadeIn() + scaleIn(initialScale = 0.9f),
                    exit = fadeOut() + scaleOut(targetScale = 0.9f),
                    modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 20.dp, bottom = navigationHeight + 12.dp)) {
                    LovelyGlassFab(onClick = onCreateRoom)
                }
            }
        }
    }
}

@Composable
private fun LovelyTopBar(onOpenNotificationSettings: () -> Unit, searchButton: ShellSearchButton?, modifier: Modifier = Modifier) {
    var notificationsOpen by remember { mutableStateOf(false) }
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val notifications by app.notificationInbox.entries.collectAsStateWithLifecycle()
    val unread = notifications.unreadCount
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
            // 検索パネルが一覧の上に隠れているときだけ、ベルの左隣に検索ボタンを出す。
            var searchBounds by remember { mutableStateOf(IntRect.Zero) }
            AnimatedVisibility(searchButton != null, enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut(targetScale = 0.8f)) {
                val active = searchButton?.activeCount ?: 0
                IconButton(onClick = { searchButton?.onClick?.invoke(searchBounds) },
                    modifier = Modifier.onGloballyPositioned { searchBounds = it.boundsInWindow().roundToIntRect() }) {
                    BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) {
                        Icon(Icons.Outlined.Search, contentDescription = if (active > 0) "検索（条件${active}件）" else "検索")
                    }
                }
            }
            Box {
                IconButton(onClick = { notificationsOpen = true }) {
                    BadgedBox(badge = { if (unread > 0) Badge { Text(if (unread > 99) "99+" else "$unread") } }) {
                        Icon(if (unread > 0) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsNone,
                            contentDescription = if (unread > 0) "お知らせ（未読${unread}件）" else "お知らせ")
                    }
                }
                QuietDropdownMenu(notificationsOpen, { notificationsOpen = false }) {
                    NotificationPanel(
                        entries = notifications,
                        onOpen = { notificationsOpen = false; app.notifier.open(it.id) },
                        onMarkAllRead = { app.notificationInbox.markAllRead() },
                        onOpenSettings = { notificationsOpen = false; onOpenNotificationSettings() },
                    )
                }
            }
        }
    }
}

@Composable
internal fun LovelyBottomNavigation(currentRoute: String?, onSelect: (MainDestination) -> Unit, modifier: Modifier = Modifier, onHeight: (Int) -> Unit = {}) {
    Box(modifier.widthIn(max = 720.dp).fillMaxWidth().navigationBarsPadding().onSizeChanged { onHeight(it.height) }.padding(horizontal = 12.dp, vertical = 12.dp)) {
        LovelyGlassSurface(Modifier.fillMaxWidth(), RoundedCornerShape(24.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(6.dp)) {
                var rowHeight by remember { mutableStateOf(64.dp) }
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
                        Column(Modifier.weight(1f).fillMaxHeight().heightIn(min = 64.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                            .selectable(selected, interactionSource = interaction, indication = null, role = Role.Tab,
                                onClick = { onSelect(destination) }).padding(vertical = 10.dp, horizontal = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
