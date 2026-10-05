@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.springthief1123.lovelyspace.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpacing
import io.github.springthief1123.lovelyspace.ui.theme.lovelyMainContentTopPadding
import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Genre
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomAction
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun RoomListScreen(
    onEnterRoom: (Room) -> Unit,
    onPeekRoom: (Room) -> Unit,
    onGenreChanged: (Genre) -> Unit,
    /** 値が変わるたびに一覧を取り直す（チャットから戻ったときなど）。0 は何もしない。 */
    refreshKey: Int = 0,
) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val vm: RoomListViewModel = viewModel(factory = viewModelFactory { initializer { RoomListViewModel(app.roomLists) } })
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(refreshKey) { vm.onRefreshKey(refreshKey) }
    LaunchedEffect(state.genre) { onGenreChanged(state.genre) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { vm.refresh(force = true) },
            modifier = Modifier.fillMaxSize(),
        ) {
            RoomList(
                state = state,
                onGenreSelect = vm::selectGenre,
                onSexSelect = vm::selectSex,
                onRoomClick = { room ->
                    when (room.action) {
                        RoomAction.ENTER -> onEnterRoom(room)
                        RoomAction.PEEK -> onPeekRoom(room)
                        RoomAction.NONE -> scope.launch {
                            snackbar.currentSnackbarData?.dismiss()
                            snackbar.showSnackbar("満室の非公開ルームには入れません")
                        }
                    }
                },
                onLoadMore = vm::loadMore,
                onRetry = { vm.refresh(force = true) },
            )
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = LovelySpacing.snackbarBottomInset),
        )

    }
}

@Composable
private fun SexFilter(selected: Gender?, onSelect: (Gender?) -> Unit) {
    val options = listOf<Pair<Gender?, String>>(null to "すべて", Gender.FEMALE to "女性", Gender.MALE to "男性")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        options.forEach { (sex, label) ->
            val active = sex == selected
            Column(
                Modifier
                    .clickable { onSelect(sex) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .size(width = 22.dp, height = 2.dp)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                            RoundedCornerShape(50),
                        ),
                )
            }
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
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun RoomList(
    state: RoomListUiState,
    onGenreSelect: (Genre) -> Unit,
    onSexSelect: (Gender?) -> Unit,
    onRoomClick: (Room) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    val listState = rememberLazyListState()
    val topContentPadding = lovelyMainContentTopPadding()

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
        // 下端は「部屋を作る」ボタンに隠れないよう空ける。
        contentPadding = PaddingValues(
            start = LovelySpacing.screenHorizontal,
            end = LovelySpacing.screenHorizontal,
            top = topContentPadding,
            bottom = LovelySpacing.snackbarBottomInset +
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            GenreBar(
                selected = state.genre,
                recent = state.recentGenres,
                counts = state.genreCounts,
                onSelect = onGenreSelect,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
            )
        }
        item { SexFilter(selected = state.sex, onSelect = onSexSelect) }
        item { SummaryLine(state) }
        item { Spacer(Modifier.height(6.dp)) }

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
            item {
                MessageBlock(
                    state.error,
                    actionLabel = "もう一度読む",
                    onAction = if (state.errorOnLoadMore) onLoadMore else onRetry,
                )
            }
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
