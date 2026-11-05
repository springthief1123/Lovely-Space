package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.ui.components.QuietCheckboxRow
import io.github.springthief1123.lovelyspace.ui.components.QuietFilterChip
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.ui.components.QuietDropdownMenu
import io.github.springthief1123.lovelyspace.ui.components.QuietDialog
import io.github.springthief1123.lovelyspace.ui.components.QuietAssistChip
import io.github.springthief1123.lovelyspace.ui.components.QuietMenuRow
import io.github.springthief1123.lovelyspace.ui.theme.LovelyShapes
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpacing
import io.github.springthief1123.lovelyspace.ui.theme.lovelyMainContentBottomInset

/**
 * 「見つける」の検索シート。トップバーの検索ボタンで開き、トップバーのすぐ下から降りてくる。
 * 上に検索欄・保存した条件・よく使う絞り込み、その下に詳しい条件を 2 つのタブ（年齢・地域／語の設定）に分けて出す。
 * タブの中身は高いほうに高さをそろえ、タブを替えても「リセット」「保存」「N件を表示」の位置が動かないようにする。
 * 画面の下（ボトムナビ）は覆わない。条件は変えた時点で取得済みの一覧に反映する。
 */
@Composable
internal fun SearchSheet(
    visible: Boolean,
    state: SearchUiState,
    vm: SearchViewModel,
    resultCount: Int,
    tab: SearchSheetTab,
    onTab: (SearchSheetTab) -> Unit,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    ng: NgSummary? = null,
) {
    BackHandler(enabled = visible, onBack = onDismiss)
    val scheme = MaterialTheme.colorScheme
    val saved = savedSearchViewModel()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + LovelySpacing.topBarHeight
    // キーボードが出ているときはその上まで、出ていないときはボトムナビの上までに収める。
    val bottom = maxOf(
        WindowInsets.ime.asPaddingValues().calculateBottomPadding(),
        lovelyMainContentBottomInset() + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
    )
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(160)), exit = fadeOut(tween(140))) {
            Box(Modifier.fillMaxSize().background(scheme.scrim.copy(alpha = 0.32f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClickLabel = "検索を閉じる", onClick = onDismiss))
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = top, bottom = bottom), contentAlignment = Alignment.TopCenter) {
            AnimatedVisibility(
                visible,
                enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { -it / 3 },
                exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { -it / 4 },
            ) {
                // トップバーの下端につなげるので、角丸は下側だけ。背景は透かさない。
                val shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                Column(
                    Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxWidth()
                        .heightIn(max = maxHeight)
                        .shadow(12.dp, shape, clip = false)
                        .clip(shape)
                        .background(scheme.surfaceContainerLow)
                        .border(BorderStroke(1.dp, scheme.outlineVariant), shape),
                ) {
                    // 文字を大きくしているときなどに収まらなければ、下端のボタンは残して中だけをスクロールする。
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                            .padding(horizontal = LovelySpacing.screenHorizontal).padding(top = 12.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SearchField(state.criteria.keyword(state.keywordScope), "検索する語", vm::keyword,
                            scope = state.keywordScope, onScope = vm::keywordScope)
                        SavedSearchRow(state, saved, vm::applyPreset)
                        QuickFilterRow(state.criteria, onChange = vm::criteria)
                        SearchSheetTabs(tab, marked = { sheetTabHasFilters(it, state.criteria, state.keywordScope) }, onSelect = onTab)
                        EqualHeightPages(selected = tab.ordinal, pages = listOf(
                            { AgeAreaConditions(state, vm) },
                            { KeywordConditions(state, vm, ng) },
                        ))
                    }
                    HorizontalDivider(color = scheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onReset) { Text("リセット") }
                        Spacer(Modifier.weight(1f))
                        SaveSearchButton(state, saved)
                        Spacer(Modifier.width(4.dp))
                        Button(onClick = onDismiss) { Text(if (state.page == 0) "閉じる" else "${resultCount}件を表示") }
                    }
                }
            }
        }
    }
}

/** 検索シートのタブ。保存した条件は検索欄の下に出すので、タブは詳しい条件だけ。 */
internal enum class SearchSheetTab(val label: String) {
    AGE_AREA("年齢・地域"),
    KEYWORD("語の設定"),
}

/** タブの中で条件を指定しているか。タブの名前の横に点を付ける。 */
internal fun sheetTabHasFilters(tab: SearchSheetTab, c: RoomSearchCriteria, scope: KeywordScope): Boolean = when (tab) {
    SearchSheetTab.AGE_AREA -> c.minAge != null || c.maxAge != null || !c.includeUnknownAge || c.area != null
    SearchSheetTab.KEYWORD -> c.excluded.isNotBlank() || c.keywordMode == KeywordMode.ANY || hiddenKeywords(c, scope).isNotEmpty()
}

/** 下線で選択を示すタブの並び。Material の TabRow は色と高さが Quiet Rose から浮くので、同じ役割を小さく作る。 */
@Composable
private fun SearchSheetTabs(selected: SearchSheetTab, marked: (SearchSheetTab) -> Boolean, onSelect: (SearchSheetTab) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column {
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            SearchSheetTab.entries.forEach { tab ->
                val on = tab == selected
                val hasFilters = marked(tab)
                Column(
                    Modifier.weight(1f).heightIn(min = 44.dp)
                        .selectable(selected = on, role = Role.Tab, onClick = { onSelect(tab) })
                        .semantics { if (hasFilters) stateDescription = "条件あり" },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Row(Modifier.padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tab.label, style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (on) scheme.primary else scheme.onSurfaceVariant)
                        if (hasFilters) Box(Modifier.padding(start = 5.dp).size(6.dp).background(scheme.primary, CircleShape))
                    }
                    Box(Modifier.fillMaxWidth(0.7f).height(2.dp).background(if (on) scheme.primary else scheme.surfaceContainerLow, LovelyShapes.control))
                }
            }
        }
        HorizontalDivider(color = scheme.outlineVariant)
    }
}

/**
 * タブの中身。どのページも測り、いちばん高いページの高さを取ったうえで、選んだページだけを置く。
 * タブを替えてもシートの高さが変わらず、下端のボタンが上下に動かない。置かないページは描かれず、押せず、読み上げられない。
 */
@Composable
private fun EqualHeightPages(selected: Int, pages: List<@Composable () -> Unit>) {
    Layout(contents = pages, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0)
        val placed = measurables.map { page -> page.map { it.measure(loose) } }
        val height = placed.maxOf { page -> page.maxOfOrNull { it.height } ?: 0 }
        layout(constraints.maxWidth, height.coerceAtLeast(constraints.minHeight)) {
            placed[selected].forEach { it.placeRelative(0, 0) }
        }
    }
}

@Composable
private fun AgeAreaConditions(state: SearchUiState, vm: SearchViewModel) {
    val c = state.criteria
    val validAges = state.validAges
    val bands = if (validAges) ageBandSelection(c.minAge, c.maxAge) else null
    var exact by rememberSaveable { mutableStateOf(false) }
    // 年代にそろっていない年齢や、直している途中の入力は、数字の欄のまま見せる。
    val showFields = exact || bands == null
    var picking by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 見出しの行に今の年齢と「数字で指定」をまとめ、チップとの間を空けすぎない。
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConditionLabel("年齢")
            Text(if (validAges) ageSummary(c.minAge, c.maxAge) else "入力を確かめてください",
                style = MaterialTheme.typography.bodySmall,
                color = if (validAges) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 8.dp).weight(1f))
            if (bands != null) TextButton(onClick = { exact = !exact }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (exact) "数字の欄を閉じる" else "数字で指定")
            }
        }
        // 5 つの年代は横に並べきる。標準のチップは左右の余白が大きく、文字を大きくすると「20…」と切れたので、
        // 余白の少ない小さな選択枠にして文字を中央に置く。
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AgeBand.entries.forEach { band ->
                AgeBandChip(band.label, selected = band in bands.orEmpty(), modifier = Modifier.weight(1f)) {
                    val (min, max) = tapAgeBand(c.minAge.takeIf { validAges }, c.maxAge.takeIf { validAges }, band)
                    vm.ageRange(min, max)
                }
            }
        }
        if (showFields) {
            // 縦に 2 段だとシートが伸びて地域が隠れるので、いつも 1 行に並べる。
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(state.minAgeInput, vm::minAge,
                    label = { Text("最低", maxLines = 1) }, suffix = { Text("歳") }, singleLine = true, isError = !validAges,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                Text("〜", Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(state.maxAgeInput, vm::maxAge,
                    label = { Text("最高", maxLines = 1) }, suffix = { Text("歳") }, singleLine = true, isError = !validAges,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            }
            if (!validAges) Text(AGE_RANGE_ERROR, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        QuietCheckboxRow(c.includeUnknownAge, { vm.criteria(c.copy(includeUnknownAge = it)) }, "年齢が秘密の部屋も含める")
        // 検索の未指定は「すべて」。プロフィール側の秘密とは別の意味。
        ConditionLabel("地域")
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { QuietFilterChip(selected = c.area == null, onClick = { vm.area(null) }, label = "すべて") }
            items(areaShortcuts(c.area, state.recentAreas)) { area ->
                QuietFilterChip(selected = area == c.area, onClick = { vm.area(if (area == c.area) null else area) }, label = area)
            }
            item { QuietAssistChip(onClick = { picking = true }, label = "地方から選ぶ", icon = Icons.Outlined.Map) }
        }
    }
    if (picking) AreaPickerDialog(c.area, onSelect = { vm.area(it); picking = false }, onDismiss = { picking = false })
}

/** 年代の選択枠。見た目は [QuietFilterChip] と同じ（選択中は淡いローズの面と primary の縁取り）で、左右の余白だけ小さい。 */
@Composable
private fun AgeBandChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(LovelyShapes.control)
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .border(BorderStroke(1.dp, if (selected) scheme.primary else scheme.outlineVariant), LovelyShapes.control)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false,
            color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant)
    }
}

/** 条件の小見出し。 */
@Composable
private fun ConditionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { heading() })
}

/** 地方ごとに都道府県を並べて 1 つ選ぶ。48 項目の長いメニューの代わり。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AreaPickerDialog(selected: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    QuietDialog(title = "地域を選ぶ", onDismissRequest = onDismiss, confirmLabel = "すべての地域", onConfirm = { onSelect(null) },
        dismissLabel = "やめる", onDismiss = onDismiss) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrefectureRegions.forEach { (region, areas) ->
                ConditionLabel(region)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    areas.forEach { area -> QuietFilterChip(selected = area == selected, onClick = { onSelect(area) }, label = area) }
                }
            }
        }
    }
}

/** 語は上の検索欄 1 つで入れ（探す場所は検索欄の右端で選ぶ）、ここでは一致のしかたと除外する語を選ぶ。 */
@Composable
private fun KeywordConditions(state: SearchUiState, vm: SearchViewModel, ng: NgSummary?) {
    val c = state.criteria
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceRow("複数の語", c.keywordMode, listOf(KeywordMode.ALL to "すべて含む", KeywordMode.ANY to "どれかを含む")) { vm.criteria(c.copy(keywordMode = it)) }
        HiddenKeywords(hiddenKeywords(c, state.keywordScope), vm::clearHiddenKeywords)
        OutlinedTextField(c.excluded, { vm.criteria(c.copy(excluded = it)) }, label = { Text("除外する語（スペース区切り）") },
            supportingText = { Text("検索欄に「-語」と書いても除外できます") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        // この検索だけの「除外する語」と、常に効く NG ワードの関係が分かるよう、すぐ下に NG ワードの状態を出す。
        ng?.let { NgSummaryRow(it) }
    }
}

/**
 * 一覧の上に常に出す並び順（新着順・おすすめ・年齢）。並び替えは絞り込みより頻繁に変えるので、検索シートを開かずに 1 タップで切り替える。
 * 新着順と年齢は、選んでいるチップをもう一度押すと降順・昇順を替える。
 * おすすめ順は保存・非表示にした部屋から学んだ好みで並べる（PreferenceModel）。
 */
@Composable
internal fun SortRow(selected: RoomSort, onSelect: (RoomSort) -> Unit) {
    val current = sortChoiceOf(selected)
    Row(Modifier.fillMaxWidth().semantics { stateDescription = "並び順：" + sortDescription(selected) },
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(Icons.Outlined.SwapVert, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("並び", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SortChoice.entries.forEach { choice ->
            val on = choice == current
            // 選んでいるチップに向きの矢印（↓ 降順・↑ 昇順）を付け、もう一度押すと向きを替える。
            QuietFilterChip(
                selected = on,
                onClick = { onSelect(tapSort(selected, choice)) },
                label = choice.label,
                trailingIcon = if (on && choice.reversible) ({
                    Icon(if (selected == choice.descending) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward, null, Modifier.size(16.dp))
                }) else null,
                modifier = Modifier.semantics {
                    if (on) stateDescription = sortDescription(selected) + if (choice.reversible) "。押すと向きを替えます" else ""
                },
            )
        }
    }
}

/** 検索欄。[onScope] があれば、右端で探す場所（名前と待機メッセージ／名前／待機メッセージ）を選べる。 */
@Composable
internal fun SearchField(text: String, placeholder: String, onChange: (String) -> Unit,
    scope: KeywordScope? = null, onScope: ((KeywordScope) -> Unit)? = null) {
    OutlinedTextField(text, onChange, placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = if (text.isNotEmpty() || (scope != null && onScope != null)) ({
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (text.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, "検索語を消す") }
                if (scope != null && onScope != null) KeywordScopeButton(scope, onScope)
            }
        }) else null,
        // 下に並ぶ入力欄・チップと同じ角丸（以前は検索欄だけ 18dp）。
        singleLine = true, shape = LovelyShapes.control, modifier = Modifier.fillMaxWidth())
}

/** 検索欄の右端の「名前＋待機 ▾」。押すと探す場所のメニューを開く。 */
@Composable
private fun KeywordScopeButton(scope: KeywordScope, onScope: (KeywordScope) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(start = 8.dp, end = 4.dp),
            modifier = Modifier.semantics { contentDescription = "探す場所：${scope.label}" }) {
            Text(scope.short, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp))
        }
        QuietDropdownMenu(open, { open = false }) {
            KeywordScope.entries.forEach { value ->
                QuietMenuRow(value.label, onClick = { onScope(value); open = false }, selected = value == scope)
            }
        }
    }
}

/** 性別は 1 タップで切り替え、利用状況と公開設定は押すと「すべて／片方／もう片方」のメニューから選ぶ。それ以外の条件はタブに置く。 */
/**
 * 性別・利用状況・公開設定。どれも押すとメニューから選び、指定中はチップの名前が選んだ値になって塗りになる。
 * 3 つを折り返さずに 1 行に並べる（以前の女性・男性の 2 つのチップと、押すたびに回る切り替えはやめた）。
 */
@Composable
private fun QuickFilterRow(criteria: RoomSearchCriteria, onChange: (RoomSearchCriteria) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MenuChip(criteria.gender, "性別", listOf(null to "指定なし", Gender.FEMALE to "女性", Gender.MALE to "男性")) {
            onChange(criteria.copy(gender = it))
        }
        MenuChip(criteria.waitingOnly, "利用状況", listOf(null to "指定なし", true to "待機中", false to "満室")) {
            onChange(criteria.copy(waitingOnly = it))
        }
        MenuChip(criteria.publicOnly, "公開設定", listOf(null to "指定なし", true to "公開", false to "非公開")) {
            onChange(criteria.copy(publicOnly = it))
        }
    }
}

/** 押すとメニューで [options] から 1 つ選ぶチップ。未指定（null）のときは [label]、指定中は選んだ値の名前を出す。 */
@Composable
private fun <T> MenuChip(value: T?, label: String, options: List<Pair<T?, String>>, onChange: (T?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val chosen = options.firstOrNull { it.first == value }?.second ?: options.first().second
    Box {
        QuietFilterChip(value != null, { open = true }, label = if (value == null) label else chosen,
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp)) },
            modifier = Modifier.semantics { stateDescription = "$label：$chosen" })
        QuietDropdownMenu(open, { open = false }) {
            options.forEach { (option, text) ->
                QuietMenuRow(text, onClick = { onChange(option); open = false }, selected = option == value)
            }
        }
    }
}

/** 検索欄に出していない欄の語（以前に名前・待機メッセージを別々に指定して保存した条件など）。黙って絞り込まないよう示し、外せるようにする。 */
@Composable
internal fun HiddenKeywords(words: List<Pair<KeywordScope, String>>, onClear: () -> Unit) {
    if (words.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("ほかに " + words.joinToString("・") { (scope, word) -> "${scope.label}「$word」" } + " でも絞り込んでいます",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = onClear) { Text("外す") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(title: String, selected: T, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 10.dp))
        // FlowRowでフォント拡大時にも選択肢を折り返す。
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, label) -> QuietFilterChip(selected = value == selected, onClick = { onSelect(value) }, label = label) }
        }
    }
}
