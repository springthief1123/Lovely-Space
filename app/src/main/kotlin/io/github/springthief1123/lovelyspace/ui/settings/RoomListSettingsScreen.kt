package io.github.springthief1123.lovelyspace.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.settings.RoomListPreferences
import io.github.springthief1123.lovelyspace.settings.RoomListStartMode
import io.github.springthief1123.lovelyspace.ui.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun RoomListSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val preferences by app.settings.roomListPreferences.collectAsStateWithLifecycle(
        initialValue = RoomListPreferences(),
    )
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val bottomContentPadding = 14.dp +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    fun saveSetting(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar("設定の保存に失敗しました：${describeError(e)}")
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            SettingsPageHeader(title = "部屋一覧", onBack = onBack)
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 0.dp,
                    top = 14.dp,
                    end = 0.dp,
                    bottom = bottomContentPadding,
                ),
            ) {
            item {
                Text(
                    "起動時に最初に表示するカテゴリ",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 6.dp),
                )
            }
            items(RoomListStartMode.entries.size) { index ->
                val mode = RoomListStartMode.entries[index]
                RoomListModeRow(
                    mode = mode,
                    selected = preferences.startMode == mode,
                    description = when (mode) {
                        RoomListStartMode.LAST_USED -> {
                            val label = Genres[preferences.lastGenreKey]?.label ?: Genres.default.label
                            "前回見ていた「$label」から再開"
                        }
                        RoomListStartMode.DEFAULT -> {
                            val label = Genres[preferences.defaultGenreKey]?.label ?: Genres.default.label
                            "毎回「$label」から開始"
                        }
                    },
                    onClick = { saveSetting { app.settings.setRoomListStartMode(mode) } },
                )
            }
            item {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
            item {
                DefaultGenrePicker(
                    selectedKey = preferences.defaultGenreKey,
                    enabled = preferences.startMode == RoomListStartMode.DEFAULT,
                    onSelect = { key -> saveSetting { app.settings.setDefaultRoomGenre(key) } },
                )
            }
        }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun RoomListModeRow(
    mode: RoomListStartMode,
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(mode.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun DefaultGenrePicker(
    selectedKey: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = Genres[selectedKey] ?: Genres.default
    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "指定カテゴリ",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (enabled) "「指定したカテゴリ」を選んだ場合に使います。" else "開始方法を「指定したカテゴリ」にすると有効になります。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
            ) {
                Text(selected.label)
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                Genres.all.forEach { genre ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(genre.label)
                                Text(
                                    genre.group.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        trailingIcon = {
                            if (genre.key == selected.key) {
                                Icon(Icons.Outlined.Check, contentDescription = null)
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(genre.key)
                        },
                    )
                }
            }
        }
    }
}
