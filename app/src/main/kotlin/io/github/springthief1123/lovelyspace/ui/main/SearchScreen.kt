package io.github.springthief1123.lovelyspace.ui.main

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
    var showFilters by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
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
        item { SearchField(c.text) { vm.criteria(c.copy(text = it)) } }
        item {
            QuickFilterRow(
                criteria = c,
                advancedCount = advancedFilterCount(c),
                onChange = vm::criteria,
                onOpenFilters = { showFilters = true },
            )
        }
        item {
            SearchStatusRow(
                text = searchStatusText(state.page, state.lastPage, if (validAges) visibleResults.size else 0, state.loading || !state.initialized,
                    state.pageTimes.values.minOrNull()?.let { io.github.springthief1123.lovelyspace.data.formatObservationTime(it) }),
                automatic = state.automatic,
                loading = state.loading,
                onAutomaticChange = vm::automatic,
                onRefresh = { vm.refresh() },
                refreshEnabled = state.initialized && !state.loading,
            )
        }
        if (newCount > 0) item {
            AssistChip(onClick = { scope.launch { listState.scrollToItem(0); vm.clearNewRooms() } },
                label = { Text("新着 ${newCount}件・先頭へ") },
                leadingIcon = { Icon(Icons.Outlined.ArrowUpward, null, Modifier.size(16.dp)) })
        }
        state.preferenceError?.let { error -> item { Text("設定を保存できませんでした：$error", color = MaterialTheme.colorScheme.error) } }
        preferences.error?.let { error -> item {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = preferencesVm::clearError) { Text("閉じる") }
        } }
        state.error?.let { error -> item {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { if (state.errorOnMore) vm.more() else vm.refresh() }, enabled = state.initialized && !state.loading) { Text("もう一度読み込む") }
        } }
        if (validAges) {
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
            if (state.page > 0 && visibleResults.isEmpty() && !state.loading) item {
                Text(if (state.results.isEmpty()) "取得済みの一覧に、条件に合う部屋はありません。" else "条件に合う部屋はすべて非表示です。")
            }
        }
    }
    }
    // 検索欄が上に流れて見えなくなったら、上部のバーの下に小さなバーを出して検索・絞り込み・更新にすぐ届くようにする。
    val searchFieldIndex = if (state.initialized) 1 else 0
    val controlsHidden by remember(searchFieldIndex) { derivedStateOf { listState.firstVisibleItemIndex > searchFieldIndex } }
    androidx.compose.animation.AnimatedVisibility(
        controlsHidden,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it / 2 },
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { -it / 2 },
        modifier = Modifier.align(Alignment.TopCenter)
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + LovelySpacing.topBarHeight + 8.dp)
            .padding(horizontal = 12.dp),
    ) {
        CompactSearchBar(
            query = c.text,
            advancedCount = advancedFilterCount(c),
            loading = state.loading,
            refreshEnabled = state.initialized && !state.loading,
            onSearch = { showSearch = true },
            onFilters = { showFilters = true },
            onRefresh = { vm.refresh() },
            onTop = { scope.launch { listState.animateScrollToItem(0) } },
        )
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = lovelyMainContentBottomInset()))
    }
    selectedRoom?.let { room -> RoomDetailsSheet(room, preferences.isFavorite(room), preferences.canEdit(room),
        onDismiss = { selectedRoom = null }, onFavorite = { preferencesVm.toggleFavorite(room) },
        onHide = { preferencesVm.hide(room); selectedRoom = null }, onEnter = onEnterRoom, onPeek = onPeekRoom) }
    // 一覧の途中から開く検索。閉じても一覧の位置はそのまま。
    if (showSearch) ModalBottomSheet(onDismissRequest = { showSearch = false }) {
        Column(Modifier.fillMaxWidth().imePadding().padding(LovelySpacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("検索", style = MaterialTheme.typography.titleLarge)
            SearchField(c.text) { vm.criteria(c.copy(text = it)) }
            QuickFilterRow(
                criteria = c,
                advancedCount = advancedFilterCount(c),
                onChange = vm::criteria,
                onOpenFilters = { showSearch = false; showFilters = true },
            )
            Text(searchStatusText(state.page, state.lastPage, if (validAges) visibleResults.size else 0, state.loading || !state.initialized, null),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }
    }
    if (showFilters) ModalBottomSheet(onDismissRequest = { showFilters = false }) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(LovelySpacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("検索条件", style = MaterialTheme.typography.titleLarge)
            SavedSearchControls(state, vm::applyPreset)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(c.name, { vm.criteria(c.copy(name = it)) }, label = { Text("名前のキーワード") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(c.message, { vm.criteria(c.copy(message = it)) }, label = { Text("待機メッセージのキーワード") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ChoiceRow("語句の一致", c.keywordMode, listOf(KeywordMode.ALL to "すべて", KeywordMode.ANY to "いずれか")) { vm.criteria(c.copy(keywordMode = it)) }
                Text("複数の語句はスペースで区切ります。名前とメッセージの条件は両方を満たす部屋を表示します。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(c.excluded, { vm.criteria(c.copy(excluded = it)) }, label = { Text("除外する語句") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ChoiceRow("性別", c.gender, listOf(null to "すべて", Gender.FEMALE to "女性", Gender.MALE to "男性")) { vm.criteria(c.copy(gender = it)) }
                QuietFieldPair(first = { fieldModifier ->
                    OutlinedTextField(state.minAgeInput, vm::minAge,
                        label = { Text("最低年齢") }, singleLine = true, isError = !validAges,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = fieldModifier)
                }, second = { fieldModifier ->
                    OutlinedTextField(state.maxAgeInput, vm::maxAge,
                        label = { Text("最高年齢") }, singleLine = true, isError = !validAges,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = fieldModifier)
                })
                if (!validAges) Text("年齢は18〜99で、最高年齢が最低年齢以上になる範囲を指定してください。", color = MaterialTheme.colorScheme.error)
                Row { Checkbox(c.includeUnknownAge, { vm.criteria(c.copy(includeUnknownAge = it)) }); Text("年齢が秘密の部屋も含める", Modifier.weight(1f).padding(top = 12.dp)) }
                // 検索の未指定は「すべて」。プロフィール側の秘密とは別の意味。
                AreaFilter(c.area) { vm.criteria(c.copy(area = it)) }
                ChoiceRow("利用状況", c.waitingOnly, listOf(null to "すべて", true to "待機中", false to "満室")) { vm.criteria(c.copy(waitingOnly = it)) }
                ChoiceRow("公開設定", c.publicOnly, listOf(null to "すべて", true to "公開", false to "非公開")) { vm.criteria(c.copy(publicOnly = it)) }
                ChoiceRow("並び順", c.sort, listOf(RoomSort.SITE to "一覧順", RoomSort.NAME to "名前", RoomSort.AGE to "年齢", RoomSort.ELAPSED to "経過")) { vm.criteria(c.copy(sort = it)) }
                Button(onClick = vm::refresh, enabled = !state.loading && validAges, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.page == 0) "このジャンルを検索" else "一覧を更新")
                }
            }

            Button(onClick = { showFilters = false }, modifier = Modifier.fillMaxWidth()) { Text("結果を見る") }
            TextButton(onClick = { vm.criteria(RoomSearchCriteria()); vm.minAge(""); vm.maxAge("") }) { Text("条件をリセット") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(title: String, selected: T, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelMedium)
        // FlowRowでフォント拡大時にも選択肢を折り返す。
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, label) -> FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AreaFilter(selected: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(selected ?: "すべて", {}, label = { Text("地域") }, readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("すべて") }, onClick = { onSelect(null); expanded = false })
            Prefectures.names.forEach { area -> DropdownMenuItem(text = { Text(area) }, onClick = { onSelect(area); expanded = false }) }
        }
    }
}

/** 性別・待機中・公開は一覧の上で1タップで切り替える。それ以外の条件はシートに置き、件数だけ示す。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickFilterRow(
    criteria: RoomSearchCriteria,
    advancedCount: Int,
    onChange: (RoomSearchCriteria) -> Unit,
    onOpenFilters: () -> Unit,
) {
    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            FilterChip(criteria.gender == Gender.FEMALE,
                { onChange(criteria.copy(gender = if (criteria.gender == Gender.FEMALE) null else Gender.FEMALE)) },
                label = { Text("女性") })
        }
        item {
            FilterChip(criteria.gender == Gender.MALE,
                { onChange(criteria.copy(gender = if (criteria.gender == Gender.MALE) null else Gender.MALE)) },
                label = { Text("男性") })
        }
        item {
            FilterChip(criteria.waitingOnly == true,
                { onChange(criteria.copy(waitingOnly = if (criteria.waitingOnly == true) null else true)) },
                label = { Text("待機中") })
        }
        item {
            FilterChip(criteria.publicOnly == true,
                { onChange(criteria.copy(publicOnly = if (criteria.publicOnly == true) null else true)) },
                label = { Text("公開") })
        }
        item {
            FilterChip(advancedCount > 0, onOpenFilters,
                label = { Text(if (advancedCount > 0) "絞り込み $advancedCount" else "絞り込み") },
                leadingIcon = { Icon(Icons.Outlined.Tune, null, Modifier.size(16.dp)) })
        }
    }
}

@Composable
private fun SearchField(text: String, onChange: (String) -> Unit) {
    OutlinedTextField(text, onChange, placeholder = { Text("名前・待機メッセージを検索") },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = if (text.isNotEmpty()) ({ IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, "検索語を消す") } }) else null,
        singleLine = true, shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth())
}

/** 一覧をスクロールしたときに上部に出す、検索・絞り込み・更新・先頭へのボタン。 */
@Composable
private fun CompactSearchBar(
    query: String,
    advancedCount: Int,
    loading: Boolean,
    refreshEnabled: Boolean,
    onSearch: () -> Unit,
    onFilters: () -> Unit,
    onRefresh: () -> Unit,
    onTop: () -> Unit,
) {
    io.github.springthief1123.lovelyspace.ui.components.LovelyGlassSurface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // 狭い画面でも右の操作ボタンの幅を先に確保し、検索語の部分だけを縮める。
            TextButton(onClick = onSearch, modifier = Modifier.weight(1f, fill = false).widthIn(max = 180.dp)) {
                Icon(Icons.Outlined.Search, null, Modifier.size(18.dp))
                Text(query.ifEmpty { "検索" }, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp))
            }
            IconButton(onClick = onFilters) {
                BadgedBox(badge = { if (advancedCount > 0) Badge { Text("$advancedCount") } }) {
                    Icon(Icons.Outlined.Tune, if (advancedCount > 0) "絞り込み（${advancedCount}件）" else "絞り込み")
                }
            }
            if (loading) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
            } else {
                IconButton(onClick = onRefresh, enabled = refreshEnabled) { Icon(Icons.Outlined.Refresh, "今すぐ更新") }
            }
            IconButton(onClick = onTop) { Icon(Icons.Outlined.ArrowUpward, "一覧の先頭へ") }
        }
    }
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

/** 一覧の状態を1行にまとめる。例: 「41件・3/5ページ・12:04 確認」。 */
internal fun searchStatusText(page: Int, lastPage: Int, shown: Int, loading: Boolean, oldestCheck: String?): String = when {
    page == 0 && loading -> "一覧を読み込んでいます"
    page == 0 -> "下に引いて一覧を取得"
    else -> listOfNotNull("${shown}件", "${page}/${lastPage}ページ", oldestCheck?.let { "$it 確認" }).joinToString("・")
}

/** シートで指定した、一覧上部のチップ以外の条件の数。 */
internal fun advancedFilterCount(c: RoomSearchCriteria): Int = listOf(
    c.name.isNotBlank(),
    c.message.isNotBlank(),
    c.excluded.isNotBlank(),
    c.minAge != null || c.maxAge != null,
    !c.includeUnknownAge,
    c.area != null,
    c.waitingOnly == false,
    c.publicOnly == false,
    c.sort != RoomSort.SITE,
).count { it }
