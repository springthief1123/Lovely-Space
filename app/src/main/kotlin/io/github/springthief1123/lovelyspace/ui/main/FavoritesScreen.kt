package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.springthief1123.lovelyspace.ui.components.*
import io.github.springthief1123.lovelyspace.data.SearchPreset
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.data.RoomPreference
import io.github.springthief1123.lovelyspace.data.toRoom
import io.github.springthief1123.lovelyspace.ui.rooms.RoomCard
import io.github.springthief1123.lovelyspace.ui.rooms.RoomPreferenceViewModel
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpacing
import io.github.springthief1123.lovelyspace.ui.theme.lovelyMainContentBottomInset
import io.github.springthief1123.lovelyspace.ui.theme.lovelyMainContentTopPadding

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FavoritesScreen(onEnterRoom: (Room) -> Unit, onPeekRoom: (Room) -> Unit, onApplyPreset: (SearchPreset) -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val vm: RoomPreferenceViewModel = viewModel(
        factory = viewModelFactory { initializer { RoomPreferenceViewModel(app.roomPreferences) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val values = state.favorites
    val presets by app.searchPresets.presets.collectAsStateWithLifecycle(initialValue = emptyList())
    var section by rememberSaveable { mutableIntStateOf(0) }

    QuietPage {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = LovelySpacing.screenHorizontal,
            end = LovelySpacing.screenHorizontal,
            top = lovelyMainContentTopPadding(),
            bottom = lovelyMainContentBottomInset() +
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(LovelySpacing.item),
    ) {
        item { QuietHeading("保存") }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(section == 0, { section = 0 }, label = { Text("部屋 ${values.size}") })
            FilterChip(section == 1, { section = 1 }, label = { Text("検索条件 ${presets.size}") })
        } }
        if (section == 1) {
            if (presets.isEmpty()) item { QuietPanel { Text("検索条件はまだありません。「見つける」の絞り込みから保存できます。") } }
            items(presets, key = { "search/${it.id}" }) { preset -> QuietPanel {
                Text(preset.label, style = MaterialTheme.typography.titleSmall)
                Text(Genres[preset.genreKey]?.label ?: preset.genreKey, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(listOf(preset.criteria.text, preset.criteria.name, preset.criteria.message).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "キーワード指定なし" }, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { onApplyPreset(preset) }) { Text("この条件で探す") }
            } }
            item { Text("名前変更・更新・削除は「見つける」の保存した条件から。巡回の選択はレーダーで行えます。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        if (section == 0 && state.loading) item { CircularProgressIndicator() }
        state.error?.let { error -> item {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = vm::reload) { Text("もう一度読み込む") }
        } }

        if (section == 0 && !state.loading && state.error == null && values.isEmpty()) {
            item {
                Text(
                    "お気に入りはまだありません。部屋カードの保存ボタンから追加できます。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }

        if (section == 0) items(values, key = { "${it.host}/${it.roomId}" }) { value ->
            FavoriteRoomRow(
                value = value,
                actionsEnabled = state.canEdit(value.host, value.roomId),
                onEnterRoom = onEnterRoom,
                onPeekRoom = onPeekRoom,
                onFavoriteClear = { vm.clearFavorite(value) },
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
    }
}

@Composable
private fun FavoriteRoomRow(
    value: RoomPreference,
    actionsEnabled: Boolean,
    onEnterRoom: (Room) -> Unit,
    onPeekRoom: (Room) -> Unit,
    onFavoriteClear: () -> Unit,
) {
    val room = value.toRoom()
    var selected by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            Genres[value.genreKey]?.label ?: value.genreKey,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
        Text("保存した一覧情報です。現在の状態は入室前に確認してください。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (value.stale) {
            Text(
                "同じ部屋IDで異なるプロフィールを確認したため、自動では開きません。ID再利用の可能性があります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
        RoomCard(
            room = room,
            enabled = true,
            onClick = { if (value.stale || room.action == io.github.springthief1123.lovelyspace.core.RoomAction.NONE) selected = true else if (room.action == io.github.springthief1123.lovelyspace.core.RoomAction.PEEK) onPeekRoom(room) else onEnterRoom(room) },
            onDetailsClick = { selected = true },
            isFavorite = true,
            onFavoriteClick = onFavoriteClear,
            actionsEnabled = actionsEnabled,
        )
    }
    if (selected) RoomDetailsSheet(room, true, actionsEnabled,
        onDismiss = { selected = false }, onFavorite = onFavoriteClear,
        onEnter = onEnterRoom, onPeek = onPeekRoom, allowEntry = !value.stale)

}
