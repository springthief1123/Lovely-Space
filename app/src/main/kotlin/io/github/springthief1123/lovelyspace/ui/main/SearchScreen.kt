package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.foundation.layout.*
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
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpacing
import io.github.springthief1123.lovelyspace.ui.theme.lovelyMainContentTopPadding

@Composable
fun SearchScreen(onEnterRoom: (Room) -> Unit, onPeekRoom: (Room) -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val vm: SearchViewModel = viewModel(factory = viewModelFactory { initializer { SearchViewModel(app.roomLists) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val c = state.criteria
    val validAges = state.validAges
    var fullNotice by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = LovelySpacing.screenHorizontal, end = LovelySpacing.screenHorizontal,
            top = lovelyMainContentTopPadding(), bottom = LovelySpacing.bottomContentInset + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("さがす", style = MaterialTheme.typography.titleLarge) }
        item { SavedSearchControls(state, vm::applyPreset) }
        item { GenreBar(state.genre, emptyList(), emptyMap(), vm::genre) }
        item {
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
        }
        item {
            Text(if (state.page == 0) "検索ボタンで一覧を取得します。" else "取得済み ${state.page}/${state.lastPage}ページ・${state.rooms.size}部屋から ${if (validAges) state.results.size else 0}件表示",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("条件の変更は取得済み一覧に反映します。全ページを探すには「次のページも検索」を押してください。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.error?.let { error -> item {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { if (state.errorOnMore) vm.more() else vm.refresh() }, enabled = !state.loading) { Text("もう一度読み込む") }
        } }
        if (validAges) {
            items(state.results, key = ::roomIdentity) { room ->
                RoomCard(room, onClick = {
                    when (room.action) {
                        RoomAction.ENTER -> onEnterRoom(room)
                        RoomAction.PEEK -> onPeekRoom(room)
                        RoomAction.NONE -> fullNotice = true
                    }
                })
            }
            if (state.page > 0 && state.results.isEmpty() && !state.loading) item { Text("取得済みの一覧に、条件に合う部屋はありません。") }
        }
        if (state.loading) item { CircularProgressIndicator(Modifier.size(28.dp)) }
        if (state.canLoadMore) item { OutlinedButton(onClick = vm::more, modifier = Modifier.fillMaxWidth()) { Text("次のページも検索") } }
    }
    if (fullNotice) AlertDialog(onDismissRequest = { fullNotice = false }, text = { Text("満室の非公開ルームには入れません。") }, confirmButton = { TextButton(onClick = { fullNotice = false }) { Text("閉じる") } })
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
