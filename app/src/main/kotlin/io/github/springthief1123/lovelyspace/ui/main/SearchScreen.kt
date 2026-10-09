package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.ui.components.QuietAssistChip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SyncDisabled
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import io.github.springthief1123.lovelyspace.ui.components.QuietPage
import io.github.springthief1123.lovelyspace.ui.components.QuietNotice
import io.github.springthief1123.lovelyspace.ui.components.QuietFieldPair
import io.github.springthief1123.lovelyspace.data.SearchPreset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.ui.rooms.GenreBar
import io.github.springthief1123.lovelyspace.ui.shell.LocalLovelyShellState
import io.github.springthief1123.lovelyspace.ui.shell.ShellSearchButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.IntRect
import io.github.springthief1123.lovelyspace.ui.rooms.RoomCard
import io.github.springthief1123.lovelyspace.ui.rooms.RoomPreferenceViewModel
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpacing
import io.github.springthief1123.lovelyspace.ui.theme.lovelyMainContentBottomInset
import io.github.springthief1123.lovelyspace.ui.theme.lovelyMainContentTopPadding

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(onEnterRoom: (Room) -> Unit, onPeekRoom: (Room) -> Unit, refreshKey: Int = 0, onGenreChanged: (Genre) -> Unit = {}, preset: SearchPreset? = null, onPresetConsumed: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val vm: SearchViewModel = viewModel(factory = viewModelFactory { initializer { SearchViewModel(app.roomLists, app.settings) } })
    val preferencesVm: RoomPreferenceViewModel = viewModel(
        factory = viewModelFactory { initializer { RoomPreferenceViewModel(app.roomPreferences) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val preferences by preferencesVm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.rooms) { preferencesVm.observe(state.rooms) }
    val visibleResults = state.results.filterNot(preferences::isHidden)
    io.github.springthief1123.lovelyspace.ui.components.ForegroundPolling(state.automatic, state.genre.key, vm::stopRefresh) { vm.monitor() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    val c = state.criteria
    val validAges = state.validAges
    var panelExpanded by rememberSaveable { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchAnchor by remember { mutableStateOf<IntRect?>(null) }
    var selectedRoom by remember { mutableStateOf<Room?>(null) }
    LaunchedEffect(state.initialized, refreshKey) { if (state.initialized) vm.onRefreshKey(refreshKey) }
    LaunchedEffect(state.genre) { onGenreChanged(state.genre) }
    LaunchedEffect(state.initialized, preset?.id) {
        if (state.initialized && preset != null) { vm.applyPreset(preset); onPresetConsumed() }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(preferencesVm) {
        preferencesVm.hiddenRooms.collect { room ->
            if (snackbar.showSnackbar("部屋を非表示にしました", actionLabel = "元に戻す", withDismissAction = true) == SnackbarResult.ActionPerformed) preferencesVm.unhide(room)
        }
    }
    // 条件のリセットは詳しい条件まで一度に消えるので、「元に戻す」を出す。
    val resetSearch = remember(vm, snackbar, scope) { {
        vm.resetCriteria()?.let { draft ->
            scope.launch {
                if (snackbar.showSnackbar("条件をリセットしました", actionLabel = "元に戻す", withDismissAction = true) == SnackbarResult.ActionPerformed) {
                    vm.restoreCriteria(draft)
                }
            }
        }
        Unit
    } }
    val quickActions = rememberRoomQuickActions(
        onNotice = { message -> scope.launch { snackbar.showSnackbar(message, withDismissAction = true) } },
    )
    // 引っ張って更新したときだけインジケーターを出す。自動取得のたびには出さない。
    var userRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading) { if (!state.loading) userRefreshing = false }
    val newCount = visibleResults.count { roomIdentity(it) in state.newRoomIds }
    QuietPage {
    val pullState = rememberPullToRefreshState()
    val pullRefreshing = userRefreshing && state.loading
    val indicatorTop = lovelyMainContentTopPadding()
    PullToRefreshBox(
        isRefreshing = pullRefreshing,
        onRefresh = { if (state.initialized && !state.loading) { userRefreshing = true; vm.refresh() } },
        modifier = Modifier.fillMaxSize(),
        state = pullState,
        // 上部のガラスのバーに隠れないよう、一覧の先頭の位置に出す。
        indicator = {
            PullToRefreshDefaults.Indicator(state = pullState, isRefreshing = pullRefreshing,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = indicatorTop))
        },
    ) {
    LazyColumn(Modifier.fillMaxSize(), state = listState,
        contentPadding = PaddingValues(start = LovelySpacing.screenHorizontal, end = LovelySpacing.screenHorizontal,
            top = lovelyMainContentTopPadding(), bottom = lovelyMainContentBottomInset() + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ジャンル名が画面の見出しを兼ねる。
        if (state.initialized) item { GenreBar(state.genre, emptyList(), emptyMap(), { vm.genre(it); vm.refresh() }) }
        item { SearchPanel(state, vm, panelExpanded, onExpandedChange = { panelExpanded = it }, onReset = resetSearch) }
        item {
            SearchStatusRow(
                text = searchStatusText(state.page, state.lastPage, visibleResults.size, state.loading || !state.initialized,
                    state.pageTimes.values.minOrNull()?.let { io.github.springthief1123.lovelyspace.data.formatObservationTime(it) }),
                automatic = state.automatic,
                loading = state.loading,
                onAutomaticChange = vm::automatic,
                onRefresh = { vm.refresh() },
                refreshEnabled = state.initialized && !state.loading,
            )
        }
        if (newCount > 0) item {
            QuietAssistChip(onClick = { scope.launch { listState.scrollToItem(0); vm.clearNewRooms() } },
                label = "新着 ${newCount}件・先頭へ", icon = Icons.Outlined.ArrowUpward)
        }
        if (!validAges) item {
            QuietNotice("年齢の条件が正しくないため、年齢では絞り込んでいません。")
        }
        state.preferenceError?.let { error -> item {
            QuietNotice("設定を保存できませんでした：$error", error = true, onDismiss = vm::clearPreferenceError)
        } }
        preferences.error?.let { error -> item {
            QuietNotice(error, error = true, onDismiss = preferencesVm::clearError)
        } }
        state.error?.let { error -> item {
            QuietNotice(error, error = true, actionLabel = "もう一度読み込む",
                onAction = { if (state.errorOnMore) vm.more() else vm.refresh() }, actionEnabled = state.initialized && !state.loading)
        } }
        items(visibleResults, key = ::roomIdentity) { room ->
            RoomCard(
                room = room,
                onClick = { when (room.action) { RoomAction.ENTER -> onEnterRoom(room); RoomAction.PEEK -> onPeekRoom(room); RoomAction.NONE -> selectedRoom = room } },
                onDetailsClick = { selectedRoom = room },
                isFavorite = preferences.isFavorite(room),
                actionsEnabled = preferences.canEdit(room),
                onFavoriteClick = { preferencesVm.toggleFavorite(room) },
                onHideClick = { preferencesVm.hide(room) },
                menuItems = quickActions(room, null),
            )
        }
        val canReadNext = state.page in 1 until state.lastPage && state.error == null
        if (state.page > 0 && visibleResults.isEmpty() && !state.loading) item {
            SearchEmpty(
                text = when {
                    state.results.isNotEmpty() -> "条件に合う部屋はすべて非表示です。"
                    canReadNext -> "読み込んだ${state.page}ページには、条件に合う部屋はありません。"
                    else -> "条件に合う部屋はありません。"
                },
                onReset = if (activeFilterCount(c) > 0) resetSearch else null,
            )
        }
        // 次のページは利用者が押したときだけ 1 ページ読む。自動更新の巡回の間隔は変えない。
        if (canReadNext) item(key = "next-page") {
            OutlinedButton(onClick = vm::more, enabled = state.canLoadMore, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.loading) "読み込んでいます…" else "次のページを読み込む（${state.page + 1}/${state.lastPage}）")
            }
        }
    }
    }
    // 検索パネルが上に隠れたらトップバーに検索ボタンを、少しでも下へ進んだら「トップへ戻る」を出す。
    // どちらも外枠に置くので、トップバー・ボトムナビと同じく後ろの一覧がぼける。
    val panelIndex = if (state.initialized) 1 else 0
    val panelHidden by remember(panelIndex) { derivedStateOf { listState.firstVisibleItemIndex > panelIndex } }
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }
    val shell = LocalLovelyShellState.current
    val owner = remember { Any() }
    val openSearch = remember { { anchor: IntRect -> searchAnchor = anchor; searchOpen = true } }
    val scrollToTop = remember(scope, listState) { { scope.launch { listState.animateScrollToItem(0) }; Unit } }
    val activeCount = activeFilterCount(c)
    SideEffect {
        shell.publish(owner,
            searchButton = if (panelHidden) ShellSearchButton(activeCount, openSearch) else null,
            scrollToTop = if (scrolled) scrollToTop else null)
    }
    DisposableEffect(shell, owner) { onDispose { shell.release(owner) } }
    // パネルが見えるところまで戻ったら、上から開いたポップオーバーは閉じる。
    LaunchedEffect(panelHidden) { if (!panelHidden) searchOpen = false }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = lovelyMainContentBottomInset()))
    }
    selectedRoom?.let { room -> RoomDetailsSheet(room, preferences.isFavorite(room), preferences.canEdit(room),
        onDismiss = { selectedRoom = null }, onFavorite = { preferencesVm.toggleFavorite(room) },
        onHide = { preferencesVm.hide(room); selectedRoom = null }, onEnter = onEnterRoom, onPeek = onPeekRoom) }
    SearchPopover(
        visible = searchOpen,
        anchor = searchAnchor,
        state = state,
        vm = vm,
        statusText = searchStatusText(state.page, state.lastPage, visibleResults.size, state.loading || !state.initialized, null),
        onDismiss = { searchOpen = false },
        onReset = resetSearch,
    )
}

@Composable
private fun SearchStatusRow(
    text: String,
    automatic: Boolean,
    loading: Boolean,
    onAutomaticChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    refreshEnabled: Boolean,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        IconButton(onClick = { onAutomaticChange(!automatic) }) {
            Icon(if (automatic) Icons.Outlined.Sync else Icons.Outlined.SyncDisabled,
                contentDescription = if (automatic) "自動更新を一時停止" else "自動更新を再開",
                tint = if (automatic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (loading) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
        } else {
            IconButton(onClick = onRefresh, enabled = refreshEnabled) { Icon(Icons.Outlined.Refresh, "今すぐ更新") }
        }
    }
}

/** 一覧に合う部屋が無いとき。条件を指定していれば、その場でリセットできるようにする。 */
@Composable
private fun SearchEmpty(text: String, onReset: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onReset != null) TextButton(onClick = onReset) { Text("条件をリセット") }
    }
}

/** 一覧の状態を1行にまとめる。例: 「41件・3/5ページ・12:04 確認」。 */
internal fun searchStatusText(page: Int, lastPage: Int, shown: Int, loading: Boolean, oldestCheck: String?): String = when {
    page == 0 && loading -> "一覧を読み込んでいます"
    page == 0 -> "下に引いて一覧を取得"
    else -> listOfNotNull("${shown}件", "${page}/${lastPage}ページ", oldestCheck?.let { "$it 確認" }).joinToString("・")
}

/** 「詳しい条件」で指定した、チップ以外の条件の数。 */
internal fun advancedFilterCount(c: RoomSearchCriteria): Int = listOf(
    c.name.isNotBlank(),
    c.message.isNotBlank(),
    c.excluded.isNotBlank(),
    c.minAge != null || c.maxAge != null,
    !c.includeUnknownAge,
    c.area != null,
    c.sort != RoomSort.SITE,
).count { it }

/** 指定中の条件の数。検索語・チップ・詳しい条件をすべて数え、トップバーの検索ボタンのバッジに出す。 */
internal fun activeFilterCount(c: RoomSearchCriteria): Int = advancedFilterCount(c) + listOf(
    c.text.isNotBlank(),
    c.gender != null,
    c.waitingOnly != null,
    c.publicOnly != null,
).count { it }
