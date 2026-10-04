@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.springthief1123.lovelyspace.ui.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Genre
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.settings.ThemeMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun RoomListScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val vm: RoomListViewModel = viewModel(factory = viewModelFactory { initializer { RoomListViewModel(app.client) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Lovely Space", fontWeight = FontWeight.SemiBold) },
                actions = { ThemeMenu(themeMode, onThemeModeChange) },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            GenreChips(
                selected = state.genre,
                counts = state.genreCounts,
                onSelect = vm::selectGenre,
            )
            SexFilter(selected = state.sex, onSelect = vm::selectSex)
            SummaryLine(state)
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { vm.refresh(force = true) },
                modifier = Modifier.fillMaxSize(),
            ) {
                RoomList(
                    state = state,
                    onRoomClick = { room ->
                        scope.launch {
                            snackbar.currentSnackbarData?.dismiss()
                            snackbar.showSnackbar("${room.name ?: "この部屋"}への入室は次のフェーズで対応します")
                        }
                    },
                    onLoadMore = vm::loadMore,
                    onRetry = { vm.refresh(force = true) },
                )
            }
        }
    }
}

@Composable
private fun ThemeMenu(current: ThemeMode, onChange: (ThemeMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(themeIcon(current), contentDescription = "テーマ")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ThemeMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            mode.label,
                            fontWeight = if (mode == current) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    leadingIcon = { Icon(themeIcon(mode), contentDescription = null) },
                    onClick = {
                        open = false
                        onChange(mode)
                    },
                )
            }
        }
    }
}

private fun themeIcon(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> Icons.Outlined.Contrast
    ThemeMode.LIGHT -> Icons.Outlined.LightMode
    ThemeMode.DARK -> Icons.Outlined.DarkMode
}

@Composable
private fun GenreChips(selected: Genre, counts: Map<String, Int>, onSelect: (Genre) -> Unit) {
    val listState = rememberLazyListState()
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(Genres.all, key = { it.key }) { genre ->
            val count = counts[genre.key]
            FilterChip(
                selected = genre == selected,
                onClick = { onSelect(genre) },
                label = { Text(if (count != null) "${genre.label} $count" else genre.label) },
            )
        }
    }
}

@Composable
private fun SexFilter(selected: Gender?, onSelect: (Gender?) -> Unit) {
    val options = listOf<Pair<Gender?, String>>(null to "すべて", Gender.FEMALE to "女性", Gender.MALE to "男性")
    SingleChoiceSegmentedButtonRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        options.forEachIndexed { index, (sex, label) ->
            SegmentedButton(
                selected = sex == selected,
                onClick = { onSelect(sex) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) { Text(label) }
        }
    }
}

@Composable
private fun SummaryLine(state: RoomListUiState) {
    val parts = buildList {
        state.waitingCount?.let { add("待機中 $it") }
        state.fullCount?.let { add("満室 $it") }
        if (state.page > 0) add("${state.page}/${state.lastPage}ページ")
    }
    if (parts.isEmpty()) return
    Text(
        parts.joinToString("・"),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun RoomList(
    state: RoomListUiState,
    onRoomClick: (Room) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    val listState = rememberLazyListState()

    // 末尾に近づいたら次のページを読む。
    LaunchedEffect(listState, state.canLoadMore) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 3
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd && state.canLoadMore) onLoadMore() }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.error != null && state.rooms.isEmpty()) {
            item { MessageBlock(state.error, actionLabel = "再読み込み", onAction = onRetry) }
        } else if (state.rooms.isEmpty() && !state.isRefreshing && state.page > 0) {
            item { MessageBlock("この条件の部屋はありません", actionLabel = null, onAction = {}) }
        }
        items(state.rooms, key = { it.id }) { room ->
            RoomCard(room = room, onClick = { onRoomClick(room) })
        }
        if (state.isLoadingMore) {
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
        } else if (state.error != null && state.rooms.isNotEmpty()) {
            item { MessageBlock(state.error, actionLabel = "もう一度読む", onAction = onLoadMore) }
        }
    }
}

@Composable
private fun MessageBlock(text: String, actionLabel: String?, onAction: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

