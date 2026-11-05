package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.ui.components.QuietConfirmDialog
import io.github.springthief1123.lovelyspace.ui.components.QuietDialog
import io.github.springthief1123.lovelyspace.ui.theme.LovelyShapes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.data.SearchPreset
import io.github.springthief1123.lovelyspace.ui.rooms.RoomActionMenu
import io.github.springthief1123.lovelyspace.ui.rooms.RoomMenuItem

/** 見つける画面の保存した条件。検索シートと一覧の上のチップで同じものを使う。 */
@Composable
internal fun savedSearchViewModel(): SavedSearchViewModel {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    return viewModel(factory = viewModelFactory { initializer { SavedSearchViewModel(app.searchPresets) } })
}

/**
 * 検索シートの検索欄のすぐ下に出す、保存した条件の横並び。押すと適用し、長押しで名前の変更・上書き・削除を選ぶ。
 * いまの条件とまったく同じものは選択中にする。新しく保存するのはシートの下端の「保存」（[SavedSearchNameDialog]）。
 */
@Composable
internal fun SavedSearchRow(search: SearchUiState, vm: SavedSearchViewModel, onApply: (SearchPreset) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var draft by rememberSaveable(stateSaver = SearchPresetSaver) { mutableStateOf<SearchPreset?>(null) }
    var replacing by rememberSaveable(stateSaver = SearchPresetSaver) { mutableStateOf<SearchPreset?>(null) }
    var deleting by rememberSaveable(stateSaver = SearchPresetSaver) { mutableStateOf<SearchPreset?>(null) }
    val scheme = MaterialTheme.colorScheme

    when {
        state.loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        state.loadError != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.loadError.orEmpty(), color = scheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::reload) { Text("もう一度読み込む") }
        }
        state.presets.isEmpty() -> Text("よく使う条件は下の「保存」で残すと、ここから 1 回で呼び出せます。",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        else -> LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            item {
                Icon(Icons.Outlined.BookmarkBorder, contentDescription = "保存した条件", tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            items(state.presets, key = { it.id }) { value ->
                SavedSearchChip(
                    value = value,
                    selected = presetMatches(value, search.genre.key, search.criteria),
                    enabled = !state.working,
                    canApply = Genres[value.genreKey] != null,
                    canReplace = search.validAges,
                    onApply = { onApply(value) },
                    onRename = { vm.clearEditError(); draft = value },
                    onReplace = { vm.clearEditError(); replacing = value.copy(genreKey = search.genre.key, criteria = search.criteria) },
                    onDelete = { vm.clearEditError(); deleting = value },
                )
            }
        }
    }

    draft?.let { value ->
        SavedSearchNameDialog(value, state.working, state.editError,
            onDismiss = { draft = null }, onSave = { vm.save(it) { draft = null } })
    }
    replacing?.let { value ->
        QuietConfirmDialog(title = "保存した条件を更新しますか？",
            text = "「${value.label}」を現在のジャンル（${Genres[value.genreKey]?.label}）と条件で上書きします。",
            confirmLabel = "更新", onConfirm = { vm.save(value) { replacing = null } }, onDismiss = { replacing = null },
            enabled = !state.working, error = state.editError)
    }
    deleting?.let { value ->
        QuietConfirmDialog(title = "保存した条件を削除しますか？", text = value.label,
            confirmLabel = "削除", onConfirm = { vm.delete(value.id) { deleting = null } }, onDismiss = { deleting = null },
            destructive = true, enabled = !state.working, error = state.editError)
    }
}

/** 検索シートの下端の「保存」。いまのジャンルと条件に名前を付けて保存する。 */
@Composable
internal fun SaveSearchButton(search: SearchUiState, vm: SavedSearchViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var draft by rememberSaveable(stateSaver = SearchPresetSaver) { mutableStateOf<SearchPreset?>(null) }
    TextButton(
        onClick = { vm.clearEditError(); draft = SearchPreset(label = "", genreKey = search.genre.key, criteria = search.criteria) },
        enabled = search.validAges && !state.working,
    ) {
        Icon(Icons.Outlined.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("保存")
    }
    draft?.let { value ->
        SavedSearchNameDialog(value, state.working, state.editError,
            onDismiss = { draft = null }, onSave = { vm.save(it) { draft = null } })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedSearchChip(
    value: SearchPreset,
    selected: Boolean,
    enabled: Boolean,
    canApply: Boolean,
    canReplace: Boolean,
    onApply: () -> Unit,
    onRename: () -> Unit,
    onReplace: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    // 並ぶ絞り込みのチップ（QuietFilterChip）と同じ角丸・境界・選択中の色にそろえる。
    val shape = LovelyShapes.control
    val scheme = MaterialTheme.colorScheme
    val isSelected = selected
    val textColor = when {
        !enabled -> scheme.onSurface.copy(alpha = 0.38f)
        selected -> scheme.onSecondaryContainer
        else -> scheme.onSurface
    }
    Box {
        Text(
            value.label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = textColor,
            modifier = Modifier
                .widthIn(max = 220.dp)
                .heightIn(min = 32.dp)
                .clip(shape)
                .background(if (selected) scheme.secondaryContainer else Color.Transparent)
                .border(BorderStroke(1.dp, if (selected) scheme.primary else scheme.outlineVariant), shape)
                .semantics { this.selected = isSelected }
                .combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    // 適用できない条件（ジャンルが無くなったなど）は、押すとメニューが開くので読み上げ名もそれに合わせる。
                    onClickLabel = if (canApply) "この条件を適用" else "保存した条件のメニュー",
                    onLongClickLabel = "保存した条件のメニュー",
                    onLongClick = { menu = true },
                    onClick = { if (canApply) onApply() else menu = true },
                )
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
        RoomActionMenu(
            visible = menu,
            touch = null,
            title = value.label,
            subtitle = Genres[value.genreKey]?.label ?: value.genreKey,
            items = listOf(
                RoomMenuItem("適用", Icons.Outlined.Search, enabled = canApply, onClick = onApply),
                RoomMenuItem("名前を変更", Icons.Outlined.Edit, onClick = onRename),
                RoomMenuItem("今の条件で上書き", Icons.Outlined.Save, enabled = canReplace, onClick = onReplace),
                RoomMenuItem("削除", Icons.Outlined.Delete, separated = true, onClick = onDelete),
            ),
            onDismiss = { menu = false },
        )
    }
}

@Composable
internal fun SavedSearchNameDialog(value: SearchPreset, working: Boolean, error: String?, onDismiss: () -> Unit, onSave: (SearchPreset) -> Unit) {
    var label by rememberSaveable(value.id) { mutableStateOf(value.label) }
    QuietDialog(title = "検索条件の保存名", onDismissRequest = { if (!working) onDismiss() },
        confirmLabel = "保存", onConfirm = { onSave(value.copy(label = label)) }, confirmEnabled = label.isNotBlank() && !working,
        onDismiss = onDismiss, dismissEnabled = !working, error = error) {
        Text(Genres[value.genreKey]?.label ?: value.genreKey)
        OutlinedTextField(label, { label = it }, singleLine = true, enabled = !working, label = { Text("保存名") })
    }
}

// 保存・更新の確認中に画面が再生成されても、対象IDと条件のスナップショットを保持する。
internal val SearchPresetSaver = listSaver<SearchPreset?, Any>(
    save = { p -> if (p == null) emptyList() else with(p.criteria) {
        listOf(p.id, p.label, p.genreKey, name, message, excluded, keywordMode.name, gender?.name.orEmpty(),
            minAge ?: -1, maxAge ?: -1, includeUnknownAge, area.orEmpty(), waitingOnly.toSavedInt(), publicOnly.toSavedInt(), sort.name, text)
    } },
    restore = { v -> if (v.isEmpty()) null else SearchPreset(v[0] as String, v[1] as String, v[2] as String,
        RoomSearchCriteria(name = v[3] as String, message = v[4] as String, excluded = v[5] as String,
            keywordMode = KeywordMode.valueOf(v[6] as String), gender = (v[7] as String).takeIf { it.isNotEmpty() }?.let(Gender::valueOf),
            minAge = (v[8] as Int).takeIf { it >= 0 }, maxAge = (v[9] as Int).takeIf { it >= 0 }, includeUnknownAge = v[10] as Boolean,
            area = (v[11] as String).takeIf { it.isNotEmpty() }, waitingOnly = (v[12] as Int).toSavedBoolean(),
            publicOnly = (v[13] as Int).toSavedBoolean(), sort = RoomSort.valueOf(v[14] as String), text = v.getOrNull(15) as? String ?: "")) },
)

private fun Boolean?.toSavedInt(): Int = when (this) { null -> -1; true -> 1; false -> 0 }
private fun Int.toSavedBoolean(): Boolean? = when (this) { -1 -> null; 1 -> true; else -> false }
