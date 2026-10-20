package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SyncDisabled
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import io.github.springthief1123.lovelyspace.ui.components.QuietPage
import io.github.springthief1123.lovelyspace.data.NgLoadState
import io.github.springthief1123.lovelyspace.ui.components.QuietNotice
import io.github.springthief1123.lovelyspace.ui.components.QuietFieldPair
import io.github.springthief1123.lovelyspace.data.SearchPreset
import io.github.springthief1123.lovelyspace.data.toRoom
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import io.github.springthief1123.lovelyspace.ui.shell.ShellScrollToTop
import io.github.springthief1123.lovelyspace.ui.shell.ShellSearchButton
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.springthief1123.lovelyspace.ui.rooms.RoomCard
import io.github.springthief1123.lovelyspace.ui.rooms.RoomPreferenceViewModel
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpacing
import io.github.springthief1123.lovelyspace.ui.theme.lovelyMainContentBottomInset
import io.github.springthief1123.lovelyspace.ui.theme.lovelyMainContentTopPadding

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(onEnterRoom: (Room) -> Unit, onPeekRoom: (Room) -> Unit, onQuickEnterRoom: (Room) -> Unit = onEnterRoom, refreshKey: Int = 0, onGenreChanged: (Genre) -> Unit = {}, preset: SearchPreset? = null, onPresetConsumed: () -> Unit = {}, onOpenNgWords: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val vm: SearchViewModel = viewModel(factory = viewModelFactory { initializer { SearchViewModel(app.roomLists, app.settings, pacing = { app.pacing.value }, hot = app.hotPages) } })
    val preferencesVm: RoomPreferenceViewModel = viewModel(
        factory = viewModelFactory { initializer { RoomPreferenceViewModel(app.roomPreferences) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val preferences by preferencesVm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.rooms) { preferencesVm.observe(state.rooms) }
    // 絞り込みと非表示の除外は、一覧か条件か保存・非表示が変わったときだけやり直す。
    // 読み込み中の印などで画面を組み立て直すたびに全ページを絞り直すと、スクロールが引っかかる。
    // 全ページの絞り込みは部屋ごとに文字をそろえるので、自動更新で一覧が届くたびにメインスレッドで行うとスクロールが詰まる。
    // 自動更新で一覧だけが変わったときは、裏で計算し終わるまで前の結果を出す。画面を開いたときと、
    // ジャンル・条件を変えたときは、前のジャンル・条件の結果を出さないよう、その場で計算する（SearchResults.resolve）。
    val effectiveCriteria = state.effectiveCriteria
    val rooms = state.rooms
    val genre = state.genre
    // NG ワードは条件で絞った後に外す。「今だけ外す」は保存せず、画面を離れると元に戻る。
    val ngFilter by app.ngWords.filter.collectAsStateWithLifecycle()
    val ngLoad by app.ngWords.state.collectAsStateWithLifecycle()
    var ngSuspended by remember { mutableStateOf(false) }
    val ng = if (ngSuspended) NgWordFilter.NONE else ngFilter
    var computed by remember { mutableStateOf<SearchResults?>(null) }
    val shown = computed.resolve(genre, rooms, effectiveCriteria, ng)
    if (shown !== computed) SideEffect { computed = shown }
    LaunchedEffect(genre, rooms, effectiveCriteria, ng) {
        if (!shown.isFor(genre, rooms, effectiveCriteria, ng)) {
            computed = withContext(Dispatchers.Default) { SearchResults.of(genre, rooms, effectiveCriteria, ng) }
        }
    }
    val results = shown.results
    val filteredResults = remember(results, preferences) { results.filterNot(preferences::isHidden) }
    // 非表示にした部屋は、NG ワードで外した数にも数えない。
    val ngExcluded = remember(shown.excluded, preferences) { shown.excluded.filterNot { preferences.isHidden(it.room) } }
    var ngSheetOpen by remember { mutableStateOf(false) }
    val ngSummary = NgSummary(ngFilter.setCount, ngSuspended, onManage = onOpenNgWords, failed = ngLoad is NgLoadState.Failed)
    // おすすめ順は、保存・非表示にした部屋と今の一覧から好みを学んで並べる。足りなければ一覧順のまま。
    // 学習と並べ替えは全ページの部屋の文字を数えるので、スクロールやスワイプが詰まらないよう裏で行う。
    // 入力が変わったら前の計算は取り消す（LaunchedEffect のキー）。
    val recommending = state.criteria.sort == RoomSort.RECOMMENDED
    var recommended by remember { mutableStateOf<Recommended?>(null) }
    LaunchedEffect(recommending, filteredResults, preferences.preferences, rooms) {
        if (!recommending) { recommended = null; return@LaunchedEffect }
        val favorites = preferences.favorites
        val hidden = preferences.hidden
        recommended = withContext(Dispatchers.Default) {
            val model = PreferenceModel.train(favorites.map { it.toRoom() }, hidden.map { it.toRoom() }, rooms)
            Recommended(filteredResults, model?.rank(filteredResults), trained = model != null)
        }
    }
    val visibleResults = remember(recommending, filteredResults, recommended) {
        val r = recommended
        val ranked = r?.ranked
        when {
            !recommending || r == null || ranked == null -> filteredResults
            r.input === filteredResults -> ranked
            // 計算し直している間は、今の一覧の部屋を前の並びの順に出す（新しい部屋は後ろ）。並びが一瞬本家の順に戻らないようにする。
            else -> ranked.withIndex().associate { (i, room) -> roomIdentity(room) to i }
                .let { order -> filteredResults.sortedBy { order[roomIdentity(it)] ?: Int.MAX_VALUE } }
        }
    }
    io.github.springthief1123.lovelyspace.ui.components.ForegroundPolling(state.automatic, state.genre.key, vm::stopRefresh) { vm.monitor() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    val c = state.criteria
    val validAges = state.validAges
    var searchOpen by rememberSaveable { mutableStateOf(false) }
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
    // 押した取り直しは state.refreshing で分かる。最初の読み込み（まだ 1 ページも無いとき）だけは loading で見る。
    var userRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading, state.refreshing) { if (!state.loading && !state.refreshing) userRefreshing = false }
    val newCount = remember(visibleResults, state.newRoomIds) { visibleResults.count { roomIdentity(it) in state.newRoomIds } }
    // 検索シートの暗い面は、幅を 720dp に抑えた一覧の外（広い画面の左右の余白）まで覆う。
    Box(Modifier.fillMaxSize()) {
    // 検索シートを開いている間は、後ろの一覧へキーボード・D-pad のフォーカスも読み上げも入らないようにする（暗い面はタッチしか止めない）。
    Box(Modifier.fillMaxSize().then(if (searchOpen) Modifier.clearAndSetSemantics {}.focusProperties { enter = { FocusRequester.Cancel } }.focusGroup() else Modifier)) {
    QuietPage {
    val pullState = rememberPullToRefreshState()
    val pullRefreshing = userRefreshing && (state.refreshing || state.loading)
    val indicatorTop = lovelyMainContentTopPadding()
    PullToRefreshBox(
        isRefreshing = pullRefreshing,
        // 本家の「最新更新」と同じく、読み込み中でも引けば取り直す（通信は ShaloveClient が 1 本ずつ通す）。
        onRefresh = { if (state.initialized) { userRefreshing = true; vm.refresh() } },
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
        item {
            SearchStatusRow(
                text = searchStatusText(state.page, state.lastPage, visibleResults.size, state.loading || !state.initialized,
                    state.pageTimes.values.minOrNull()?.let { io.github.springthief1123.lovelyspace.data.formatObservationTime(it) }),
                automatic = state.automatic,
                refreshing = state.refreshing || (state.page == 0 && state.loading),
                refreshedCount = state.refreshedCount,
                onAutomaticChange = vm::automatic,
                onRefresh = { vm.refresh() },
                refreshEnabled = state.initialized,
            )
        }
        item { SortRow(c.sort) { vm.criteria(c.copy(sort = it)) } }
        if (ngSuspended || ngExcluded.isNotEmpty()) item {
            NgStatusRow(ngExcluded.size, ngSuspended, onShow = { ngSheetOpen = true }, onResume = { ngSuspended = false })
        }
        (ngLoad as? NgLoadState.Failed)?.let { failed -> item {
            QuietNotice("NGワードを読み込めませんでした。除外せずに表示しています：${failed.message}", error = true,
                actionLabel = "もう一度読み込む", onAction = app.ngWords::retry)
        } }
        if (!validAges) item {
            QuietNotice("年齢の条件が正しくないため、年齢では絞り込んでいません。")
        }
        if (recommending && recommended?.trained == false && !preferences.loading) item {
            QuietNotice("ピンした部屋（非表示にしたものを除く）が${PreferenceModel.MIN_LIKED}件以上になると、好みに近い順に並びます。今は一覧順です。")
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
                menuItems = remember(room, quickActions) { quickActions(room, null) },
                onEnterChipClick = { onQuickEnterRoom(room) },
            )
        }
        val canReadNext = state.page in 1 until state.lastPage && state.error == null
        if (state.page > 0 && visibleResults.isEmpty() && !state.loading) item {
            SearchEmpty(
                text = when {
                    results.isNotEmpty() -> "条件に合う部屋はすべて非表示です。"
                    ngExcluded.isNotEmpty() -> "条件に合う部屋は、すべて NG ワードで除外しています。"
                    canReadNext && state.automatic -> "条件に合う部屋はまだ見つかっていません。"
                    canReadNext -> "読み込んだ${state.page}ページには、条件に合う部屋はありません。"
                    else -> "条件に合う部屋はありません。"
                },
                onReset = if (activeFilterCount(c) > 0) resetSearch else null,
            )
        }
        // 自動更新中は残りのページを自動で読み切る。自動更新を止めているときは、利用者が押したときだけ 1 ページ読む。
        if (canReadNext && !state.automatic) item(key = "next-page") {
            OutlinedButton(onClick = vm::more, enabled = state.canLoadMore, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.loading) "読み込んでいます…" else "次のページを読み込む（${state.page + 1}/${state.lastPage}）")
            }
        }
    }
    }
    // トップバーの検索ボタンはいつも出し、少しでも下へ進んだら「トップへ戻る」を出す。
    // どちらも外枠に置くので、トップバー・ボトムナビと同じく後ろの一覧がぼける。
    // 新着は一覧の上に足されるので、下へ進んでいる間は「トップへ戻る」を「新着 N件」にして知らせる。
    // 先頭が見えている間に届いた新着はその場で見えるので、数えない。
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }
    val atTop = !scrolled
    LaunchedEffect(atTop, newCount) { if (atTop && newCount > 0) vm.clearNewRooms() }
    val shell = LocalLovelyShellState.current
    val owner = remember { Any() }
    val toggleSearch = remember { { searchOpen = !searchOpen } }
    val scrollToTop = remember(scope, listState) { { scope.launch { listState.animateScrollToItem(0) }; Unit } }
    val activeCount = activeFilterCount(c)
    // SideEffect の中で読んだ状態は再コンポーズのきっかけにならない。先頭へ戻ったのにボタンが残らないよう、ここで読む。
    val searchButton = ShellSearchButton(activeCount, searchOpen, toggleSearch)
    val scrollButton = if (scrolled) ShellScrollToTop(newCount, scrollToTop) else null
    SideEffect { shell.publish(owner, searchButton = searchButton, scrollToTop = scrollButton) }
    DisposableEffect(shell, owner) { onDispose { shell.release(owner) } }
    }
    }
    SearchSheet(
        visible = searchOpen,
        state = state,
        vm = vm,
        resultCount = visibleResults.size,
        onDismiss = { searchOpen = false },
        onReset = resetSearch,
        ng = ngSummary.copy(onManage = { searchOpen = false; ngSummary.onManage() }),
    )
    // シートの上に出す（リセットの「元に戻す」をシートを開いたまま押せるように）。
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = lovelyMainContentBottomInset()))
    }
    selectedRoom?.let { room -> RoomDetailsSheet(room, preferences.isFavorite(room), preferences.canEdit(room),
        onDismiss = { selectedRoom = null }, onFavorite = { preferencesVm.toggleFavorite(room) },
        onHide = { preferencesVm.hide(room); selectedRoom = null }, onEnter = onEnterRoom, onPeek = onPeekRoom) }
    if (ngSheetOpen) NgExcludedSheet(
        excluded = ngExcluded,
        onDismiss = { ngSheetOpen = false },
        onSuspend = { ngSuspended = true; ngSheetOpen = false },
        onManage = { ngSheetOpen = false; onOpenNgWords() },
        onOpenRoom = { ngSheetOpen = false; selectedRoom = it },
    )
}

@Composable
private fun SearchStatusRow(
    text: String,
    automatic: Boolean,
    refreshing: Boolean,
    refreshedCount: Int,
    onAutomaticChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    refreshEnabled: Boolean,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // 自動更新の状態をアイコンの形と色だけに頼らない。止めているときは文でも示し、ボタンの読み上げにも状態を付ける。
        Text(if (automatic) text else "$text・自動更新を停止中", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        IconButton(onClick = { onAutomaticChange(!automatic) },
            modifier = Modifier.semantics { stateDescription = if (automatic) "自動更新中" else "自動更新を停止中" }) {
            Icon(if (automatic) Icons.Outlined.Sync else Icons.Outlined.SyncDisabled,
                contentDescription = if (automatic) "自動更新を一時停止" else "自動更新を再開",
                tint = if (automatic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RefreshButton(refreshing, refreshedCount, onRefresh, refreshEnabled)
    }
}

/**
 * 「今すぐ更新」（本家の「最新更新」）。取り直し中も押せるよう、ボタンは残したまま周りに回転する輪を重ねる。
 * 輪は利用者が押した取り直しの間だけ回し、自動の取得では回さない。通信がすぐ終わっても一瞬で消えないよう最低 [MIN_SPIN_MS] は回し、
 * 終わったらチェックの印を [DONE_MS] 出して、更新が済んだことを伝える（読み上げにも伝える）。
 */
@Composable
private fun RefreshButton(refreshing: Boolean, refreshedCount: Int, onRefresh: () -> Unit, enabled: Boolean) {
    var spinning by remember { mutableStateOf(refreshing) }
    // 押したとき・取り直しが始まったときから数えて、最低 [MIN_SPIN_MS] は回す。連打中は押すたびに延びるので、輪は途切れずに回り続ける。
    var spinStartedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(refreshing, spinStartedAt) {
        if (refreshing) {
            if (!spinning) spinStartedAt = System.currentTimeMillis()
            spinning = true
        } else if (spinning) {
            kotlinx.coroutines.delay((spinStartedAt + MIN_SPIN_MS - System.currentTimeMillis()).coerceAtLeast(0))
            spinning = false
        }
    }
    // 回数が増えて輪が止まったときだけ印を出す。ジャンルを変えて 0 に戻ったときは出さない。
    // 印を出している途中で押されたり次の取り直しが済んだりしても、必ず消してから次を出す（出たままにしない）。
    var seenCount by remember { mutableIntStateOf(refreshedCount) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(refreshedCount, spinning) {
        if (refreshedCount < seenCount) seenCount = refreshedCount
        if (spinning || refreshedCount == seenCount) return@LaunchedEffect
        seenCount = refreshedCount
        done = true
        try { kotlinx.coroutines.delay(DONE_MS) } finally { done = false }
    }
    val description = when {
        spinning -> "更新中。押すともう一度取り直します"
        done -> "更新しました。押すともう一度取り直します"
        else -> "今すぐ更新"
    }
    IconButton(onClick = { done = false; spinStartedAt = System.currentTimeMillis(); spinning = true; onRefresh() }, enabled = enabled,
        modifier = Modifier.semantics { contentDescription = description; liveRegion = LiveRegionMode.Polite }) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (spinning) CircularProgressIndicator(Modifier.matchParentSize(), strokeWidth = 2.dp)
            if (done) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            else Icon(Icons.Outlined.Refresh, null, Modifier.size(20.dp))
        }
    }
}

/** 押した取り直しの輪を回し続ける最短の時間（ミリ秒）。通信がすぐ終わっても回ったことが分かるように。 */
private const val MIN_SPIN_MS = 600L
/** 取り直しが済んだ印を出す時間（ミリ秒）。 */
private const val DONE_MS = 1_500L

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

/** 検索シートのタブで指定した、チップ以外の条件の数。並び順は一覧の上に出しているので数えない。 */
internal fun advancedFilterCount(c: RoomSearchCriteria): Int = listOf(
    c.name.isNotBlank(),
    c.message.isNotBlank(),
    c.excluded.isNotBlank(),
    c.minAge != null || c.maxAge != null,
    !c.includeUnknownAge,
    c.area != null,
).count { it }

/** 指定中の条件の数。検索語・チップ・詳しい条件をすべて数え、トップバーの検索ボタンのバッジに出す。 */
internal fun activeFilterCount(c: RoomSearchCriteria): Int = advancedFilterCount(c) + listOf(
    c.text.isNotBlank(),
    c.gender != null,
    c.waitingOnly != null,
    c.publicOnly != null,
).count { it }

/** おすすめ順の計算結果。[input] は並べた一覧（非表示を除いた絞り込みの結果）、[ranked] は学習できなかったとき null。 */
private class Recommended(val input: List<Room>, val ranked: List<Room>?, val trained: Boolean)
