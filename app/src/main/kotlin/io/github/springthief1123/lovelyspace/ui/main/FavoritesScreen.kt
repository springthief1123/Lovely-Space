package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import io.github.springthief1123.lovelyspace.ui.theme.lovelyMainContentTopPadding

private enum class SavedRoomSection { FAVORITES, HIDDEN }

@Composable
fun FavoritesScreen(onEnterRoom: (Room) -> Unit, onPeekRoom: (Room) -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val vm: RoomPreferenceViewModel = viewModel(
        factory = viewModelFactory { initializer { RoomPreferenceViewModel(app.roomPreferences) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(SavedRoomSection.FAVORITES) }
    val values = if (section == SavedRoomSection.FAVORITES) state.favorites else state.hidden

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = LovelySpacing.screenHorizontal,
            end = LovelySpacing.screenHorizontal,
            top = lovelyMainContentTopPadding(),
            bottom = LovelySpacing.bottomContentInset +
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("お気に入り", style = MaterialTheme.typography.titleLarge) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = section == SavedRoomSection.FAVORITES,
                    onClick = { section = SavedRoomSection.FAVORITES },
                    label = { Text("お気に入り ${state.favorites.size}") },
                )
                FilterChip(
                    selected = section == SavedRoomSection.HIDDEN,
                    onClick = { section = SavedRoomSection.HIDDEN },
                    label = { Text("非表示 ${state.hidden.size}") },
                )
            }
        }
        item {
            Text(
                if (section == SavedRoomSection.FAVORITES)
                    "一覧や検索で再び見つかった部屋は、保存した表示内容を自動更新します。"
                else
                    "非表示は端末内だけに保存され、本家への通信や相手への通知は行いません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.loading) item { CircularProgressIndicator() }
        state.error?.let { error -> item {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = vm::reload) { Text("もう一度読み込む") }
        } }

        if (!state.loading && state.error == null && values.isEmpty()) {
            item {
                Text(
                    if (section == SavedRoomSection.FAVORITES)
                        "部屋一覧や検索結果から「お気に入り」を追加できます。"
                    else
                        "非表示にした部屋はありません。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }

        items(values, key = { "${it.host}/${it.roomId}" }) { value ->
            SavedRoomRow(
                value = value,
                section = section,
                onEnterRoom = onEnterRoom,
                onPeekRoom = onPeekRoom,
                onFavoriteClear = { vm.clearFavorite(value) },
                onHiddenClear = { vm.clearHidden(value) },
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun SavedRoomRow(
    value: RoomPreference,
    section: SavedRoomSection,
    onEnterRoom: (Room) -> Unit,
    onPeekRoom: (Room) -> Unit,
    onFavoriteClear: () -> Unit,
    onHiddenClear: () -> Unit,
) {
    val room = value.toRoom()
    Column(Modifier.fillMaxWidth()) {
        Text(
            Genres[value.genreKey]?.label ?: value.genreKey,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (value.stale) {
            Text(
                "同じ部屋IDで異なるプロフィールを確認したため、自動では開きません。ID再利用の可能性があります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        RoomCard(
            room = room,
            enabled = section == SavedRoomSection.FAVORITES && !value.stale && room.action != RoomAction.NONE,
            onClick = {
                when (room.action) {
                    RoomAction.ENTER -> onEnterRoom(room)
                    RoomAction.PEEK -> onPeekRoom(room)
                    RoomAction.NONE -> Unit
                }
            },
            isFavorite = value.favorite,
            isHidden = value.hidden,
            onFavoriteClick = if (value.favorite) onFavoriteClear else null,
            onHideClick = if (value.hidden) onHiddenClear else null,
        )
    }
}
