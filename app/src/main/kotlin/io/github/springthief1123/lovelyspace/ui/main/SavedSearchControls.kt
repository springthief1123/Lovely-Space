package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.ui.components.QuietConfirmDialog
import io.github.springthief1123.lovelyspace.ui.components.QuietDialog
import io.github.springthief1123.lovelyspace.ui.theme.LovelyShapes
import io.github.springthief1123.lovelyspace.ui.components.QuietAssistChip
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
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

/**
 * 検索パネルの中に出す保存した条件。チップを押すと適用し、長押しで名前の変更・上書き・削除を選ぶ。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SavedSearchControls(search: SearchUiState, onApply: (SearchPreset) -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val vm: SavedSearchViewModel = viewModel(factory = viewModelFactory { initializer { SavedSearchViewModel(app.searchPresets) } })
    val state by vm.state.collectAsStateWithLifecycle()
    var draft by rememberSaveable(stateSaver = SearchPresetSaver) { mutableStateOf<SearchPreset?>(null) }
    var replacing by rememberSaveable(stateSaver = SearchPresetSaver) { mutableStateOf<SearchPreset?>(null) }
    var deleting by rememberSaveable(stateSaver = SearchPresetSaver) { mutableStateOf<SearchPreset?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("保存した条件", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            QuietAssistChip(
                onClick = {
                    vm.clearEditError()
                    draft = SearchPreset(label = "", genreKey = search.genre.key, criteria = search.criteria)
                },
                enabled = search.validAges && !state.working,
                label = "今の条件を保存",
                icon = Icons.Outlined.Add,
            )
            state.presets.forEach { value ->
                SavedSearchChip(
                    value = value,
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
        when {
            state.loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            state.loadError != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.loadError.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::reload) { Text("もう一度読み込む") }
            }
        }
        Text(if (state.presets.isEmpty()) "ジャンルと条件を端末に保存できます。呼び出しても自動では通信しません。"
            else "押すと適用、長押しで名前の変更・上書き・削除。呼び出しても自動では通信しません。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    draft?.let { value ->
        SavedSearchNameDialog(value, state.working, state.editError,
            onDismiss = { draft = null }, onSave = { vm.save(it) { draft = null } })
    }
    replacing?.let { value ->
        QuietConfirmDialog(title = "保存した条件を更新しますか？",
            text = "「${value.label}」を現在のジャンル（${Genres[value.genreKey]?.label}）と条件で上書きします。",
            confirmLabel = "更新", onConfirm = { vm.save(value) { replacing = null } }, onDismiss = { replacing = null },
            enabled = !state.working, dismissLabel = "キャンセル", error = state.editError)
    }
    deleting?.let { value ->
        QuietConfirmDialog(title = "保存した条件を削除しますか？", text = value.label,
            confirmLabel = "削除", onConfirm = { vm.delete(value.id) { deleting = null } }, onDismiss = { deleting = null },
            destructive = true, enabled = !state.working, dismissLabel = "キャンセル", error = state.editError)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedSearchChip(
    value: SearchPreset,
    enabled: Boolean,
    canApply: Boolean,
    canReplace: Boolean,
    onApply: () -> Unit,
    onRename: () -> Unit,
    onReplace: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    // 隣の「今の条件を保存」（QuietAssistChip）と同じ角丸・境界にそろえる。
    val shape = LovelyShapes.control
    val scheme = MaterialTheme.colorScheme
    Box {
        Text(
            value.label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier
                .widthIn(max = 220.dp)
                .heightIn(min = 32.dp)
                .clip(shape)
                .border(BorderStroke(1.dp, scheme.outlineVariant), shape)
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
private fun SavedSearchNameDialog(value: SearchPreset, working: Boolean, error: String?, onDismiss: () -> Unit, onSave: (SearchPreset) -> Unit) {
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
