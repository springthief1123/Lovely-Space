package io.github.springthief1123.lovelyspace.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import io.github.springthief1123.lovelyspace.settings.ThemeMode
import io.github.springthief1123.lovelyspace.ui.components.LovelyGlassFab
import io.github.springthief1123.lovelyspace.ui.components.LovelyGlassSurface
import io.github.springthief1123.lovelyspace.ui.components.ProvideLovelyHazeState
import io.github.springthief1123.lovelyspace.ui.theme.LovelyShapes

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
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
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
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
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
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    LovelyGlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RectangleShape,
    ) {
        Row(
            Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .height(58.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Lovely Space",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            HeaderAction(
                icon = if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                description = if (dark) "ライトモードに切り替える" else "ダークモードに切り替える",
                onClick = { onThemeModeChange(if (dark) ThemeMode.LIGHT else ThemeMode.DARK) },
            )
            HeaderAction(
                icon = Icons.Outlined.Settings,
                description = "設定",
                onClick = onOpenSettings,
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
    Box(
        Modifier
            .size(44.dp)
            .clickable(onClick = onClick),
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
        Row(
            Modifier
                .navigationBarsPadding()
                .fillMaxWidth()
                .height(76.dp)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MainDestinations.forEach { destination ->
                val selected = currentRoute == destination.route
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onDestinationSelected(destination) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
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
                    Box(
                        Modifier
                            .size(width = 22.dp, height = 2.dp)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                                shape = LovelyShapes.control,
                            ),
                    )
                }
            }
        }
    }
}
