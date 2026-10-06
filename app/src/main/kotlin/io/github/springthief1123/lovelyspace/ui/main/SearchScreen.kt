package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import io.github.springthief1123.lovelyspace.ui.components.QuietHeading
import io.github.springthief1123.lovelyspace.data.SearchPreset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import io.github.springthief1123.lovelyspace.ui.theme.lovelyMainContentTopPadding

@OptIn(ExperimentalMaterial3Api::class)
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
    val c = state.criteria
    val validAges = state.validAges
    var showFilters by remember { mutableStateOf(false) }
    var selectedRoom by remember { mutableStateOf<Room?>(null) }
    LaunchedEffect(state.initialized, refreshKey) { if (state.initialized) vm.onRefreshKey(refreshKey) }
    LaunchedEffect(state.genre) { onGenreChanged(state.genre) }
    LaunchedEffect(state.initialized, preset?.id) {
        if (state.initialized && preset != null) { vm.applyPreset(preset); onPresetConsumed() }
    }
    LazyColumn(Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = LovelySpacing.screenHorizontal, end = LovelySpacing.screenHorizontal,
            top = lovelyMainContentTopPadding(), bottom = LovelySpacing.bottomContentInset + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { QuietHeading("FIND YOUR MOMENT", "いま、話したい人と。", "気になる言葉から、心地よい場所を見つけよう。") }
        item {
            OutlinedTextField(c.text, { vm.criteria(c.copy(text = it)) }, placeholder = { Text("名前・募集文を検索") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { IconButton(onClick = { showFilters = true }) { Icon(Icons.Outlined.Tune, "検索条件") } },
                singleLine = true, shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth())
        }
        item { GenreBar(state.genre, emptyList(), emptyMap(), { vm.genre(it); vm.refresh() }) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FilterChip(c.waitingOnly == true, { vm.criteria(c.copy(waitingOnly = if (c.waitingOnly == true) null else true)) }, label = { Text("待機中") })
                TextButton(onClick = { showFilters = true }) { Text("絞り込み・条件保存") }
            }
        }
        item {
            Text(if (state.page == 0) (if (state.loading || !state.initialized) "一覧を読み込んでいます。" else "「一覧を更新」でこのジャンルを取得します。") else "取得済み ${state.page}/${state.lastPage}ページ・${state.rooms.size}部屋から ${if (validAges) visibleResults.size else 0}件表示",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.pageTimes.values.minOrNull()?.let { at -> Text("表示範囲の最も古い確認 ${io.github.springthief1123.lovelyspace.data.formatObservationTime(at)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("条件の変更は取得済み一覧に反映します。「続きを読み込む」で検索範囲を広げられます。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { TextButton(onClick = vm::refresh, enabled = !state.loading) { Text("一覧を更新") } }
        state.preferenceError?.let { error -> item { Text("設定を保存できませんでした：$error", color = MaterialTheme.colorScheme.error) } }
        preferences.error?.let { error -> item {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = preferencesVm::clearError) { Text("閉じる") }
        } }
        state.error?.let { error -> item {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { if (state.errorOnMore) vm.more() else vm.refresh() }, enabled = !state.loading) { Text("もう一度読み込む") }
        } }
        if (validAges) {
            items(visibleResults, key = ::roomIdentity) { room ->
                RoomCard(
                    room = room,
                    onClick = { selectedRoom = room },
                    isFavorite = preferences.isFavorite(room),
                    actionsEnabled = preferences.canEdit(room),
                    onFavoriteClick = { preferencesVm.toggleFavorite(room) },
                    onHideClick = { preferencesVm.hide(room) },
                )
            }
            if (state.page > 0 && visibleResults.isEmpty() && !state.loading) item {
                Text(if (state.results.isEmpty()) "取得済みの一覧に、条件に合う部屋はありません。" else "条件に合う部屋はすべて非表示です。")
            }
        }
        if (state.loading) item { CircularProgressIndicator(Modifier.size(28.dp)) }
        if (state.canLoadMore) item { OutlinedButton(onClick = vm::more, modifier = Modifier.fillMaxWidth()) { Text("続きを読み込む") } }
    }
    selectedRoom?.let { room -> RoomDetailsSheet(room, preferences.isFavorite(room), preferences.canEdit(room),
        onDismiss = { selectedRoom = null }, onFavorite = { preferencesVm.toggleFavorite(room) },
        onHide = { preferencesVm.hide(room); selectedRoom = null }, onEnter = onEnterRoom, onPeek = onPeekRoom) }
    if (showFilters) ModalBottomSheet(onDismissRequest = { showFilters = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("検索条件", style = MaterialTheme.typography.titleLarge)
            SavedSearchControls(state, vm::applyPreset)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(c.name, { vm.criteria(c.copy(name = it)) }, label = { Text("名前のキーワード") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(c.message, { vm.criteria(c.copy(message = it)) }, label = { Text("待機メッセージのキーワード") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ChoiceRow("語句の一致", c.keywordMode, listOf(KeywordMode.ALL to "すべて", KeywordMode.ANY to "いずれか")) { vm.criteria(c.copy(keywordMode = it)) }
                Text("複数の語句はスペースで区切ります。名前とメッセージの条件は両方を満たす部屋を表示します。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(c.excluded, { vm.criteria(c.copy(excluded = it)) }, label = { Text("除外する語句") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ChoiceRow("性別", c.gender, listOf(null to "すべて", Gender.FEMALE to "女性", Gender.MALE to "男性")) { vm.criteria(c.copy(gender = it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(state.minAgeInput, vm::minAge,
                        label = { Text("最低年齢") }, singleLine = true, isError = !validAges,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(state.maxAgeInput, vm::maxAge,
                        label = { Text("最高年齢") }, singleLine = true, isError = !validAges,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
                if (!validAges) Text("年齢は18〜99で、最高年齢が最低年齢以上になる範囲を指定してください。", color = MaterialTheme.colorScheme.error)
                Row { Checkbox(c.includeUnknownAge, { vm.criteria(c.copy(includeUnknownAge = it)) }); Text("年齢が秘密の部屋も含める", Modifier.padding(top = 12.dp)) }
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
