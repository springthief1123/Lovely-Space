package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.ui.components.QuietFieldPair
import io.github.springthief1123.lovelyspace.ui.rooms.roomMenuBelowEnd
import io.github.springthief1123.lovelyspace.ui.theme.LocalLovelyColors

/**
 * 「見つける」の一覧の先頭に置く検索パネル。閉じているときは検索欄とよく使う絞り込みだけを出し、
 * 開くとその場で下に広がって、詳しい条件と保存した条件を出す。条件は変えた時点で取得済みの一覧に反映する。
 */
@Composable
internal fun SearchPanel(
    state: SearchUiState,
    vm: SearchViewModel,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surfaceContainerLow)
            .border(BorderStroke(1.dp, scheme.outlineVariant), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SearchField(state.criteria.text) { vm.criteria(state.criteria.copy(text = it)) }
        QuickFilterRow(
            criteria = state.criteria,
            advancedCount = advancedFilterCount(state.criteria),
            expanded = expanded,
            onChange = vm::criteria,
            onToggleDetails = { onExpandedChange(!expanded) },
        )
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SearchDetails(state, vm)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onReset) { Text("条件をリセット") }
                    TextButton(onClick = { onExpandedChange(false) }) { Text("閉じる") }
                }
            }
        }
    }
}

/**
 * トップバーの検索ボタン [anchor] の右端から左下へ広がる検索のポップオーバー。中身は開いた検索パネルと同じ。
 * 背景は透かさず、画面の高さに収めて中だけをスクロールさせる。「一覧を更新」は下端に固定する。
 */
@Composable
internal fun SearchPopover(
    visible: Boolean,
    anchor: IntRect?,
    state: SearchUiState,
    vm: SearchViewModel,
    statusText: String,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
) {
    val transition = remember { MutableTransitionState(false) }
    LaunchedEffect(visible) { transition.targetState = visible }
    if (anchor == null || (!transition.currentState && !transition.targetState)) return

    val density = LocalDensity.current
    val margin = with(density) { POPOVER_MARGIN.roundToPx() }
    val provider = remember(anchor, margin) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
                roomMenuBelowEnd(anchor, popupContentSize, windowSize, margin)
        }
    }
    val configuration = LocalConfiguration.current
    val width = minOf(configuration.screenWidthDp.dp - POPOVER_MARGIN * 2, 420.dp)
    val anchorBottom = with(density) { anchor.bottom.toDp() }
    val maxHeight = (configuration.screenHeightDp.dp - anchorBottom - POPOVER_MARGIN * 2).coerceAtLeast(240.dp)
    val origin = TransformOrigin(1f, 0f)
    val shape = RoundedCornerShape(24.dp)
    val scheme = MaterialTheme.colorScheme
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(
            visibleState = transition,
            enter = fadeIn(tween(140)) + scaleIn(tween(200), initialScale = 0.6f, transformOrigin = origin),
            exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.96f, transformOrigin = origin),
        ) {
            // ポップアップは別のウィンドウなので背景のぼかしは使えない。透かさない面と縁取りで後ろの一覧と分ける。
            Column(
                Modifier
                    .width(width)
                    .heightIn(max = maxHeight)
                    .shadow(16.dp, shape, clip = false)
                    .clip(shape)
                    .background(scheme.surfaceContainerLow)
                    .border(BorderStroke(1.dp, LocalLovelyColors.current.glassBorder), shape),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("検索", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "検索を閉じる") }
                }
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SearchField(state.criteria.text) { vm.criteria(state.criteria.copy(text = it)) }
                    QuickFilterRow(state.criteria, advancedFilterCount(state.criteria), expanded = true, onChange = vm::criteria, onToggleDetails = null)
                    SearchDetails(state, vm)
                    TextButton(onClick = onReset) { Text("条件をリセット") }
                }
                HorizontalDivider(color = scheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(statusText, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Button(onClick = vm::refresh, enabled = state.initialized && !state.loading && state.validAges) {
                        Text(if (state.page == 0) "一覧を取得" else "一覧を更新")
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchField(text: String, onChange: (String) -> Unit) {
    OutlinedTextField(text, onChange, placeholder = { Text("名前・待機メッセージを検索") },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = if (text.isNotEmpty()) ({ IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, "検索語を消す") } }) else null,
        singleLine = true, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth())
}

/** 性別・待機中・公開は1タップで切り替える。それ以外の条件は「詳しい条件」に置き、件数だけ示す。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickFilterRow(
    criteria: RoomSearchCriteria,
    advancedCount: Int,
    expanded: Boolean,
    onChange: (RoomSearchCriteria) -> Unit,
    onToggleDetails: (() -> Unit)?,
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
        if (onToggleDetails != null) item {
            FilterChip(advancedCount > 0 || expanded, onToggleDetails,
                label = { Text(if (advancedCount > 0) "詳しい条件 $advancedCount" else "詳しい条件") },
                leadingIcon = { Icon(Icons.Outlined.Tune, null, Modifier.size(16.dp)) },
                trailingIcon = { Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    if (expanded) "詳しい条件を閉じる" else "詳しい条件を開く", Modifier.size(16.dp)) })
        }
    }
}

/** 保存した条件と、チップ以外の詳しい条件。 */
@Composable
private fun SearchDetails(state: SearchUiState, vm: SearchViewModel) {
    val c = state.criteria
    val validAges = state.validAges
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SavedSearchControls(state, vm::applyPreset)
        OutlinedTextField(c.name, { vm.criteria(c.copy(name = it)) }, label = { Text("名前のキーワード") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(c.message, { vm.criteria(c.copy(message = it)) }, label = { Text("待機メッセージのキーワード") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ChoiceRow("語句の一致", c.keywordMode, listOf(KeywordMode.ALL to "すべて", KeywordMode.ANY to "いずれか")) { vm.criteria(c.copy(keywordMode = it)) }
        Text("複数の語句はスペースで区切ります。名前とメッセージの条件は両方を満たす部屋を表示します。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(c.excluded, { vm.criteria(c.copy(excluded = it)) }, label = { Text("除外する語句") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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

private val POPOVER_MARGIN = 12.dp
