package io.github.springthief1123.lovelyspace.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import io.github.springthief1123.lovelyspace.data.toRoom
import io.github.springthief1123.lovelyspace.ui.rooms.RoomCard
import io.github.springthief1123.lovelyspace.ui.rooms.RoomPreferenceViewModel

@Composable
fun HiddenRoomsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val vm: RoomPreferenceViewModel = viewModel(
        factory = viewModelFactory { initializer { RoomPreferenceViewModel(app.roomPreferences) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SettingsPageHeader(title = "非表示にした部屋", onBack = onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 14.dp,
                bottom = 20.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                Text(
                    "左へスワイプすると非表示を解除できます。非表示設定はこの端末内だけに保存されます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                )
            }

            if (state.loading) item { CircularProgressIndicator() }
            state.error?.let { error -> item {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = vm::reload) { Text("もう一度読み込む") }
            } }

            if (!state.loading && state.error == null && state.hidden.isEmpty()) {
                item {
                    Text(
                        "非表示にした部屋はありません。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }

            items(state.hidden, key = { "${it.host}/${it.roomId}" }) { value ->
                Column {
                    Text(
                        Genres[value.genreKey]?.label ?: value.genreKey,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                    RoomCard(
                        room = value.toRoom(),
                        enabled = false,
                        onClick = {},
                        isHidden = true,
                        onHideClick = { vm.clearHidden(value) },
                    )
                }
            }
        }
    }
}
