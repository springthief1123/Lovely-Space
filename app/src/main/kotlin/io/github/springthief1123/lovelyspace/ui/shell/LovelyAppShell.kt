package io.github.springthief1123.lovelyspace.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import io.github.springthief1123.lovelyspace.ui.components.LovelyGlassFab
import io.github.springthief1123.lovelyspace.ui.components.LovelyGlassSurface
import io.github.springthief1123.lovelyspace.ui.components.ProvideLovelyHazeState
import io.github.springthief1123.lovelyspace.ui.theme.LovelyShapes
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpacing

data class MainDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

val MainDestinations = listOf(
    MainDestination("rooms", "部屋一覧", Icons.Outlined.Home),
    MainDestination("search", "さがす", Icons.Outlined.Search),
    MainDestination("favorites", "お気に入り", Icons.Outlined.FavoriteBorder),
    MainDestination("profile", "マイページ", Icons.Outlined.Person),
)

@Composable
fun LovelyAppShell(
    currentRoute: String?,
    showChrome: Boolean,
    onDestinationSelected: (MainDestination) -> Unit,
    onOpenSettings: () -> Unit,
    showCreateFab: Boolean,
    onCreateRoom: () -> Unit,
    content: @Composable () -> Unit,
) {
    val hazeState = remember { HazeState() }

    ProvideLovelyHazeState(hazeState) {
        Box(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                    content()
                }
            }

            if (showChrome) {
                LovelyTopBar(
                    currentRoute = currentRoute,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                LovelyBottomNavigation(
                    currentRoute = currentRoute,
                    onDestinationSelected = onDestinationSelected,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
                AnimatedVisibility(
                    visible = showCreateFab,
                    enter = fadeIn() + scaleIn(initialScale = 0.85f),
                    exit = fadeOut() + scaleOut(targetScale = 0.85f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 20.dp, bottom = 92.dp),
                ) {
                    LovelyGlassFab(onClick = onCreateRoom)
                }
            }
        }
    }
}

@Composable
private fun LovelyTopBar(
    currentRoute: String?,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var notificationsOpen by remember { mutableStateOf(false) }
    val section = MainDestinations.firstOrNull { it.route == currentRoute }?.label ?: "Lovely Space"

    LovelyGlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RectangleShape,
    ) {
        Row(
            Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .height(LovelySpacing.topBarHeight)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "Lovely Space",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    section,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                HeaderAction(
                    icon = Icons.Outlined.NotificationsNone,
                    description = "通知",
                    onClick = { notificationsOpen = true },
                )
                NotificationPanel(
                    expanded = notificationsOpen,
                    onDismiss = { notificationsOpen = false },
                )
            }
            HeaderAction(
                icon = Icons.Outlined.Settings,
                description = "設定",
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun NotificationPanel(expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(292.dp),
    ) {
        Text(
            "通知",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.padding(18.dp)) {
            Text(
                "新しい通知はありません",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "入室・新着メッセージ・巡回中の部屋の通知は、通知機能の実装後にここへまとめて表示します。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun HeaderAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(stiffness = 700f),
        label = "header-action-scale",
    )
    Box(
        Modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun LovelyBottomNavigation(
    currentRoute: String?,
    onDestinationSelected: (MainDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    LovelyGlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = LovelyShapes.bottomGlass,
    ) {
        BoxWithConstraints(
            Modifier
                .navigationBarsPadding()
                .fillMaxWidth()
                .height(76.dp)
                .padding(horizontal = 6.dp, vertical = 8.dp),
        ) {
            val itemWidth = maxWidth / MainDestinations.size.toFloat()
            val selectedIndex = MainDestinations.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
            val indicatorX by animateDpAsState(
                targetValue = itemWidth * selectedIndex.toFloat() + (itemWidth - 28.dp) / 2f,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 420f),
                label = "bottom-nav-indicator",
            )

            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = indicatorX)
                    .size(width = 28.dp, height = 3.dp)
                    .background(MaterialTheme.colorScheme.primary, LovelyShapes.control),
            )

            Row(
                Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MainDestinations.forEach { destination ->
                    BottomDestination(
                        destination = destination,
                        selected = currentRoute == destination.route,
                        onClick = { onDestinationSelected(destination) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomDestination(
    destination: MainDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val lift by animateDpAsState(
        targetValue = if (selected) (-3).dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 360f),
        label = "bottom-nav-lift",
    )
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.92f
            selected -> 1.05f
            else -> 1f
        },
        animationSpec = spring(stiffness = 520f),
        label = "bottom-nav-scale",
    )

    Column(
        modifier
            .offset(y = lift)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            destination.icon,
            contentDescription = destination.label,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(23.dp),
        )
        Text(
            destination.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
