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
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Explore
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
import io.github.springthief1123.lovelyspace.data.formatObservationTime
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

@Composable
fun LovelyAppShell(
    currentRoute: String?, showChrome: Boolean,
    onDestinationSelected: (MainDestination) -> Unit,
    showCreateFab: Boolean, onCreateRoom: () -> Unit,
    content: @Composable () -> Unit,
) {
    val hazeState = remember { HazeState() }
    val density = LocalDensity.current
    var navigationHeight by remember { mutableStateOf(100.dp) }
    val bottomInset = maxOf(LovelySpacing.bottomContentInset, navigationHeight + 80.dp)
    ProvideLovelyHazeState(hazeState) {
        Box(Modifier.fillMaxSize()) {
            Surface(Modifier.fillMaxSize().hazeSource(state = hazeState),
                color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                CompositionLocalProvider(LocalLovelyBottomContentInset provides bottomInset) { content() }
            }
            if (showChrome) {
                LovelyTopBar(Modifier.align(Alignment.TopCenter))
                LovelyBottomNavigation(if (currentRoute == Routes.SEARCH) Routes.ROOMS else currentRoute,
                    onDestinationSelected, Modifier.align(Alignment.BottomCenter), onHeight = { navigationHeight = with(density) { it.toDp() } })
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
private fun LovelyTopBar(modifier: Modifier = Modifier) {
    var notificationsOpen by remember { mutableStateOf(false) }
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val radar by app.radar.state.collectAsStateWithLifecycle()
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
            Box {
                IconButton(onClick = { notificationsOpen = true }) {
                    Icon(Icons.Outlined.NotificationsNone, contentDescription = "お知らせ")
                }
                DropdownMenu(notificationsOpen, { notificationsOpen = false }) {
                    Column(Modifier.widthIn(max = 280.dp).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("お知らせ", style = MaterialTheme.typography.titleSmall)
                        if (radar.events.isEmpty()) Text("変化の履歴はまだありません", style = MaterialTheme.typography.bodyMedium)
                        radar.events.take(3).forEach { event ->
                            Text(event.text, style = MaterialTheme.typography.bodySmall)
                            Text(formatObservationTime(event.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
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
