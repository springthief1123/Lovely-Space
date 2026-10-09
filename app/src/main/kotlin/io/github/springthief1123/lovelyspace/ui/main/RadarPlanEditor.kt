package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.ui.components.QuietSheetHeader
import io.github.springthief1123.lovelyspace.ui.components.QuietSheet
import io.github.springthief1123.lovelyspace.ui.components.QuietCheckboxRow
import io.github.springthief1123.lovelyspace.ui.components.QuietFilterChip
import androidx.compose.foundation.layout.*
import io.github.springthief1123.lovelyspace.ui.components.QuietFieldPair
import io.github.springthief1123.lovelyspace.ui.components.QuietExposedMenu
import io.github.springthief1123.lovelyspace.ui.components.QuietMenuRow
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpacing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.data.SearchPreset

/** 保存した検索条件そのものを編集する。入力では通信せず、計画の基準は保存後に作り直す。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RadarPlanEditor(value: SearchPreset, working: Boolean, error: String?,
    onDismiss: () -> Unit, onSave: (SearchPreset) -> Unit) {
    var draft by rememberSaveable(value.id, stateSaver = SearchPresetSaver) { mutableStateOf<SearchPreset?>(value) }
    var minAge by rememberSaveable(value.id) { mutableStateOf(value.criteria.minAge?.toString().orEmpty()) }
    var maxAge by rememberSaveable(value.id) { mutableStateOf(value.criteria.maxAge?.toString().orEmpty()) }
    val preset = draft ?: return
    val c = preset.criteria
    val validAges = (minAge.isBlank() || (minAge.toIntOrNull() ?: 0) in 18..99) &&
        (maxAge.isBlank() || (maxAge.toIntOrNull() ?: 0) in 18..99) &&
        (minAge.isBlank() || maxAge.isBlank() || minAge.toInt() <= maxAge.toInt())
    fun criteria(next: RoomSearchCriteria) { draft = preset.copy(criteria = next) }
    QuietSheet(onDismissRequest = { if (!working) onDismiss() }) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = LovelySpacing.screenHorizontal).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            QuietSheetHeader("巡回計画を編集", "保存した検索条件も更新します。条件を変更すると、次の巡回は1ページ目から比較の基準を作ります。")
            OutlinedTextField(preset.label, { draft = preset.copy(label = it) }, enabled = !working,
                label = { Text("計画名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            RadarDropdown("ジャンル", preset.genreKey, Genres.all.map { it.key to it.label }, !working) { draft = preset.copy(genreKey = it) }
            OutlinedTextField(c.text, { criteria(c.copy(text = it)) }, enabled = !working, label = { Text("名前と待機メッセージの検索語") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(c.name, { criteria(c.copy(name = it)) }, enabled = !working, label = { Text("名前のキーワード") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(c.message, { criteria(c.copy(message = it)) }, enabled = !working, label = { Text("待機メッセージのキーワード") }, modifier = Modifier.fillMaxWidth())
            RadarChoice("語句の一致", c.keywordMode, listOf(KeywordMode.ALL to "すべて", KeywordMode.ANY to "いずれか"), !working) { criteria(c.copy(keywordMode = it)) }
            Text("複数の語句はスペースで区切ります。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(c.excluded, { criteria(c.copy(excluded = it)) }, enabled = !working, label = { Text("除外する語句") }, modifier = Modifier.fillMaxWidth())
            RadarChoice("性別", c.gender, listOf(null to "すべて", Gender.FEMALE to "女性", Gender.MALE to "男性"), !working) { criteria(c.copy(gender = it)) }
            QuietFieldPair(first = { fieldModifier ->
                OutlinedTextField(minAge, { minAge = it.filter(Char::isDigit).take(2) }, enabled = !working,
                    label = { Text("最低年齢") }, singleLine = true, isError = !validAges,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = fieldModifier)
            }, second = { fieldModifier ->
                OutlinedTextField(maxAge, { maxAge = it.filter(Char::isDigit).take(2) }, enabled = !working,
                    label = { Text("最高年齢") }, singleLine = true, isError = !validAges,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = fieldModifier)
            })
            if (!validAges) Text("年齢は18〜99で、最低年齢が最高年齢以下になるよう指定してください。", color = MaterialTheme.colorScheme.error)
            QuietCheckboxRow(c.includeUnknownAge, { criteria(c.copy(includeUnknownAge = it)) }, "年齢が秘密の部屋も含める", enabled = !working)
            RadarDropdown("地域", c.area.orEmpty(), listOf("" to "すべて") + Prefectures.names.map { it to it }, !working) { criteria(c.copy(area = it.ifBlank { null })) }
            RadarChoice("利用状況", c.waitingOnly, listOf(null to "すべて", true to "待機中", false to "満室"), !working) { criteria(c.copy(waitingOnly = it)) }
            RadarChoice("公開設定", c.publicOnly, listOf(null to "すべて", true to "公開", false to "非公開"), !working) { criteria(c.copy(publicOnly = it)) }
            RadarChoice("並び順", c.sort, listOf(RoomSort.SITE to "一覧順", RoomSort.NAME to "名前", RoomSort.AGE to "年齢", RoomSort.ELAPSED to "経過"), !working) { criteria(c.copy(sort = it)) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !working && preset.label.isNotBlank() && validAges, onClick = {
                onSave(preset.copy(criteria = c.copy(minAge = minAge.toIntOrNull(), maxAge = maxAge.toIntOrNull())))
            }, modifier = Modifier.fillMaxWidth()) { Text(if (working) "保存中…" else "計画を保存") }
            TextButton(enabled = !working, onClick = onDismiss) { Text("キャンセル") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> RadarChoice(title: String, selected: T, values: List<Pair<T, String>>, enabled: Boolean, onSelect: (T) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            values.forEach { (value, label) -> QuietFilterChip(selected == value, { onSelect(value) }, label = label, enabled = enabled) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RadarDropdown(title: String, selected: String, values: List<Pair<String, String>>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded && enabled, { if (enabled) expanded = it }) {
        OutlinedTextField(values.firstOrNull { it.first == selected }?.second ?: selected, {}, readOnly = true, enabled = enabled,
            label = { Text(title) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded && enabled) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = enabled))
        QuietExposedMenu(expanded && enabled, { expanded = false }) {
            values.forEach { (value, label) -> QuietMenuRow(label, selected = value == selected, onClick = { onSelect(value); expanded = false }) }
        }
    }
}
