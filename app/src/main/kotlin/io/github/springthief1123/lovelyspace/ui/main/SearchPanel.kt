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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.ui.components.QuietFieldPair
import io.github.springthief1123.lovelyspace.ui.components.QuietExposedMenu
import io.github.springthief1123.lovelyspace.ui.components.QuietMenuRow
import io.github.springthief1123.lovelyspace.ui.theme.LovelyShapes
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpacing
import io.github.springthief1123.lovelyspace.ui.theme.lovelyMainContentBottomInset

/**
 * 「見つける」の検索シート。トップバーの検索ボタンで開き、トップバーのすぐ下から降りてくる。
 * 上に検索欄とよく使う絞り込み、その下に詳しい条件を 3 つのタブ（年齢・地域／語の設定／保存した条件）に分けて出す。
 * 1 つのタブの中身を短くしてスクロールせずに済むようにし、「リセット」「N件を表示」は下端に固定する。
 * 画面の下（ボトムナビ）は覆わない。条件は変えた時点で取得済みの一覧に反映する。
 */
@Composable
internal fun SearchSheet(
    visible: Boolean,
    state: SearchUiState,
    vm: SearchViewModel,
    resultCount: Int,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    ng: NgSummary? = null,
) {
    // 閉じても最後に見ていたタブを覚えておく（中身は閉じると破棄されるので外に置く）。
    var tab by rememberSaveable { mutableStateOf(SearchSheetTab.AGE_AREA) }
    BackHandler(enabled = visible, onBack = onDismiss)
    val scheme = MaterialTheme.colorScheme
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
                        SearchField(state.criteria.keyword(state.keywordScope), state.keywordScope.placeholder, vm::keyword)
                        QuickFilterRow(state.criteria, onChange = vm::criteria)
                        SearchSheetTabs(tab) { tab = it }
                        when (tab) {
                            SearchSheetTab.AGE_AREA -> AgeAreaConditions(state, vm)
                            SearchSheetTab.KEYWORD -> KeywordConditions(state, vm, ng)
                            SearchSheetTab.SAVED -> SavedSearchControls(state, vm::applyPreset)
                        }
                    }
                    HorizontalDivider(color = scheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onReset) { Text("リセット") }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = onDismiss) { Text(if (state.page == 0) "閉じる" else "${resultCount}件を表示") }
                    }
                }
            }
        }
    }
}

/** 検索シートのタブ。どのタブも中身を数行に収める。 */
internal enum class SearchSheetTab(val label: String) {
    AGE_AREA("年齢・地域"),
    KEYWORD("語の設定"),
    SAVED("保存した条件"),
}

/** 下線で選択を示すタブの並び。Material の TabRow は色と高さが Quiet Rose から浮くので、同じ役割を小さく作る。 */
@Composable
private fun SearchSheetTabs(selected: SearchSheetTab, onSelect: (SearchSheetTab) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column {
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            SearchSheetTab.entries.forEach { tab ->
                val on = tab == selected
                Column(
                    Modifier.weight(1f).heightIn(min = 44.dp)
                        .selectable(selected = on, role = Role.Tab, onClick = { onSelect(tab) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(tab.label, style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) scheme.primary else scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp))
                    Box(Modifier.fillMaxWidth(0.7f).height(2.dp).background(if (on) scheme.primary else scheme.surfaceContainerLow, LovelyShapes.control))
                }
            }
        }
        HorizontalDivider(color = scheme.outlineVariant)
    }
}

@Composable
private fun AgeAreaConditions(state: SearchUiState, vm: SearchViewModel) {
    val c = state.criteria
    val validAges = state.validAges
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        QuietFieldPair(first = { fieldModifier ->
            OutlinedTextField(state.minAgeInput, vm::minAge,
                label = { Text("最低年齢") }, singleLine = true, isError = !validAges,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = fieldModifier)
        }, second = { fieldModifier ->
            OutlinedTextField(state.maxAgeInput, vm::maxAge,
                label = { Text("最高年齢") }, singleLine = true, isError = !validAges,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = fieldModifier)
        })
        if (!validAges) Text(AGE_RANGE_ERROR, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        QuietCheckboxRow(c.includeUnknownAge, { vm.criteria(c.copy(includeUnknownAge = it)) }, "年齢が秘密の部屋も含める")
        // 検索の未指定は「すべて」。プロフィール側の秘密とは別の意味。
        AreaFilter(c.area) { vm.criteria(c.copy(area = it)) }
    }
}

/** 語は上の検索欄 1 つで入れ、ここでは探す場所・一致のしかた・除外する語を選ぶ。 */
@Composable
private fun KeywordConditions(state: SearchUiState, vm: SearchViewModel, ng: NgSummary?) {
    val c = state.criteria
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceRow("探す場所", state.keywordScope, KeywordScope.entries.map { it to it.label }, vm::keywordScope)
        ChoiceRow("複数の語", c.keywordMode, listOf(KeywordMode.ALL to "すべて含む", KeywordMode.ANY to "どれかを含む")) { vm.criteria(c.copy(keywordMode = it)) }
        HiddenKeywords(hiddenKeywords(c, state.keywordScope), vm::clearHiddenKeywords)
        OutlinedTextField(c.excluded, { vm.criteria(c.copy(excluded = it)) }, label = { Text("除外する語（スペース区切り）") },
            supportingText = { Text("検索欄に「-語」と書いても除外できます") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        // この検索だけの「除外する語」と、常に効く NG ワードの関係が分かるよう、すぐ下に NG ワードの状態を出す。
        ng?.let { NgSummaryRow(it) }
    }
}

/**
 * 一覧の上に常に出す並び順。並び替えは絞り込みより頻繁に変えるので、検索シートを開かずに 1 タップで切り替える。
 * おすすめ順は保存・非表示にした部屋から学んだ好みで並べる（PreferenceModel）。
 */
@Composable
internal fun SortRow(selected: RoomSort, onSelect: (RoomSort) -> Unit) {
    val options = listOf(RoomSort.SITE to "一覧順", RoomSort.RECOMMENDED to "おすすめ", RoomSort.NAME to "名前", RoomSort.AGE to "年齢", RoomSort.ELAPSED to "経過")
    LazyRow(Modifier.fillMaxWidth().semantics { stateDescription = "並び順：" + options.first { it.first == selected }.second },
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Icon(Icons.Outlined.SwapVert, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("並び", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        options.forEach { (value, label) ->
            item { QuietFilterChip(selected = value == selected, onClick = { onSelect(value) }, label = label) }
        }
    }
}

@Composable
internal fun SearchField(text: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(text, onChange, placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = if (text.isNotEmpty()) ({ IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, "検索語を消す") } }) else null,
        // 下に並ぶ入力欄・チップと同じ角丸（以前は検索欄だけ 18dp）。
        singleLine = true, shape = LovelyShapes.control, modifier = Modifier.fillMaxWidth())
}

/** 性別・待機中・公開は1タップで切り替える。それ以外の条件はタブに置く。 */
@Composable
private fun QuickFilterRow(criteria: RoomSearchCriteria, onChange: (RoomSearchCriteria) -> Unit) {
    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            QuietFilterChip(criteria.gender == Gender.FEMALE,
                { onChange(criteria.copy(gender = if (criteria.gender == Gender.FEMALE) null else Gender.FEMALE)) },
                label = "女性")
        }
        item {
            QuietFilterChip(criteria.gender == Gender.MALE,
                { onChange(criteria.copy(gender = if (criteria.gender == Gender.MALE) null else Gender.MALE)) },
                label = "男性")
        }
        // 利用状況と公開設定は、押すたびに「すべて → 片方 → もう片方 → すべて」と切り替える。
        // 詳しい条件に同じ項目を二重に置かず、指定中の状態がチップだけで分かるようにする。
        item {
            QuietFilterChip(criteria.waitingOnly != null,
                { onChange(criteria.copy(waitingOnly = nextTriState(criteria.waitingOnly))) },
                label = triStateLabel(criteria.waitingOnly, "利用状況", "待機中", "満室"),
                modifier = Modifier.semantics { stateDescription = triStateLabel(criteria.waitingOnly, "すべて", "待機中", "満室") })
        }
        item {
            QuietFilterChip(criteria.publicOnly != null,
                { onChange(criteria.copy(publicOnly = nextTriState(criteria.publicOnly))) },
                label = triStateLabel(criteria.publicOnly, "公開設定", "公開", "非公開"),
                modifier = Modifier.semantics { stateDescription = triStateLabel(criteria.publicOnly, "すべて", "公開", "非公開") })
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AreaFilter(selected: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(selected ?: "すべて", {}, label = { Text("地域") }, readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
        QuietExposedMenu(expanded, { expanded = false }) {
            QuietMenuRow("すべて", selected = selected == null, onClick = { onSelect(null); expanded = false })
            Prefectures.names.forEach { area -> QuietMenuRow(area, selected = area == selected, onClick = { onSelect(area); expanded = false }) }
        }
    }
}

/** 3 段階のチップの次の値。未指定 → true → false → 未指定。 */
internal fun nextTriState(value: Boolean?): Boolean? = when (value) { null -> true; true -> false; false -> null }

internal fun triStateLabel(value: Boolean?, none: String, yes: String, no: String) = when (value) { null -> none; true -> yes; false -> no }
