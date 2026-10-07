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
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
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
        item { QuietTabs(listOf(0 to "部屋 ${values.size}", 1 to "検索条件 ${presets.size}"), section) { section = it } }
        if (section == 1) {
            if (presets.isEmpty()) item { FavoritesEmpty("検索条件はまだありません。「見つける」の絞り込みから保存できます。") }
            else item { FavoritesNote("名前の変更・更新・削除は「見つける」の保存した条件から、巡回の選択はレーダーから行えます。") }
            items(presets, key = { "search/${it.id}" }) { preset -> QuietListPanel(onClick = { onApplyPreset(preset) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(preset.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(Genres[preset.genreKey]?.label ?: preset.genreKey,
                            listOf(preset.criteria.text, preset.criteria.name, preset.criteria.message).filter { it.isNotBlank() }.joinToString("・").ifBlank { "キーワード指定なし" },
                        ).joinToString("・"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { onApplyPreset(preset) }) { Text("この条件で探す") }
                }
            } }
        }

        if (section == 0 && state.loading) item { CircularProgressIndicator() }
        state.error?.let { error -> item {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = vm::reload) { Text("もう一度読み込む") }
        } }

        if (section == 0 && !state.loading && state.error == null && values.isEmpty()) {
            item { FavoritesEmpty("保存した部屋はまだありません。部屋カードを右へスワイプするか、長押しのメニューから保存できます。") }
        }

        if (section == 0 && values.isNotEmpty()) {
            item { FavoritesNote("保存した時点の一覧情報です。いまの状態は入室前に確認してください。") }
            values.groupBy { it.genreKey }.forEach { (genreKey, group) ->
                item(key = "genre/$genreKey") {
                    Text("${Genres[genreKey]?.label ?: genreKey}　${group.size}",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                }
                items(group, key = { "${it.host}/${it.roomId}" }) { value ->
                    FavoriteRoomRow(
                        value = value,
                        actionsEnabled = state.canEdit(value.host, value.roomId),
                        onEnterRoom = onEnterRoom,
                        onPeekRoom = onPeekRoom,
                        onFavoriteClear = { vm.clearFavorite(value) },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
    }
}

@Composable
private fun FavoritesNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FavoritesEmpty(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (value.stale) {
            Text(
                "同じ部屋IDで異なるプロフィールを確認したため、自動では開きません。ID再利用の可能性があります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp),
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
