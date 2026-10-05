package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.core.*
import io.github.springthief1123.lovelyspace.data.SearchPreset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SavedSearchControls(search: SearchUiState, onApply: (SearchPreset) -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val vm: SavedSearchViewModel = viewModel(factory = viewModelFactory { initializer { SavedSearchViewModel(app.searchPresets) } })
    val state by vm.state.collectAsStateWithLifecycle()
    var showSaved by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable(stateSaver = SearchPresetSaver) { mutableStateOf<SearchPreset?>(null) }
    var replacing by rememberSaveable(stateSaver = SearchPresetSaver) { mutableStateOf<SearchPreset?>(null) }
    var deleting by rememberSaveable(stateSaver = SearchPresetSaver) { mutableStateOf<SearchPreset?>(null) }

    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                vm.clearEditError()
                draft = SearchPreset(label = "", genreKey = search.genre.key, criteria = search.criteria)
            }, enabled = search.validAges && !state.working) { Text("条件を保存") }
            TextButton(onClick = { showSaved = true }) { Text("保存した条件") }
        }
        Text("ジャンルと条件を端末に保存します。呼び出しても自動では通信しません。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (showSaved) ModalBottomSheet(onDismissRequest = { showSaved = false }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("保存した条件", style = MaterialTheme.typography.titleLarge) }
            if (state.loading) item { CircularProgressIndicator(Modifier.size(28.dp)) }
            state.loadError?.let { error -> item {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = vm::reload, enabled = !state.loading) { Text("もう一度読み込む") }
            } }
            if (!state.loading && state.loadError == null && state.presets.isEmpty()) item {
                Text("よく使う条件を「条件を保存」から追加できます。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.presets, key = { it.id }) { value ->
                Column {
                    Text(value.label, style = MaterialTheme.typography.titleMedium)
                    Text(Genres[value.genreKey]?.label ?: value.genreKey, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled = !state.working && Genres[value.genreKey] != null, onClick = {
                            onApply(value); showSaved = false
                        }) { Text("適用") }
                        TextButton(enabled = !state.working, onClick = { vm.clearEditError(); draft = value }) { Text("名前変更") }
                        TextButton(enabled = !state.working && search.validAges, onClick = {
                            vm.clearEditError()
                            replacing = value.copy(genreKey = search.genre.key, criteria = search.criteria)
                        }) { Text("現在の条件で更新") }
                        TextButton(enabled = !state.working, onClick = { vm.clearEditError(); deleting = value }) { Text("削除") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    draft?.let { value ->
        SavedSearchNameDialog(value, state.working, state.editError,
            onDismiss = { draft = null }, onSave = { vm.save(it) { draft = null } })
    }
    replacing?.let { value ->
        AlertDialog(onDismissRequest = { if (!state.working) replacing = null },
            title = { Text("保存した条件を更新しますか？") },
            text = { Column {
                Text("「${value.label}」を現在のジャンル（${Genres[value.genreKey]?.label}）と条件で上書きします。")
                state.editError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !state.working, onClick = { vm.save(value) { replacing = null } }) { Text("更新") } },
            dismissButton = { TextButton(enabled = !state.working, onClick = { replacing = null }) { Text("キャンセル") } })
    }
    deleting?.let { value ->
        AlertDialog(onDismissRequest = { if (!state.working) deleting = null }, title = { Text("保存した条件を削除しますか？") },
            text = { Column {
                Text(value.label)
                state.editError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !state.working, onClick = { vm.delete(value.id) { deleting = null } }) { Text("削除") } },
            dismissButton = { TextButton(enabled = !state.working, onClick = { deleting = null }) { Text("キャンセル") } })
    }
}

@Composable
private fun SavedSearchNameDialog(value: SearchPreset, working: Boolean, error: String?, onDismiss: () -> Unit, onSave: (SearchPreset) -> Unit) {
    var label by rememberSaveable(value.id) { mutableStateOf(value.label) }
    AlertDialog(onDismissRequest = { if (!working) onDismiss() }, title = { Text("検索条件の保存名") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Genres[value.genreKey]?.label ?: value.genreKey)
            OutlinedTextField(label, { label = it }, singleLine = true, enabled = !working, label = { Text("保存名") })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = label.isNotBlank() && !working, onClick = { onSave(value.copy(label = label)) }) { Text("保存") } },
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text("キャンセル") } })
}

// 保存・更新の確認中に画面が再生成されても、対象IDと条件のスナップショットを保持する。
internal val SearchPresetSaver = listSaver<SearchPreset?, Any>(
    save = { p -> if (p == null) emptyList() else with(p.criteria) {
        listOf(p.id, p.label, p.genreKey, name, message, excluded, keywordMode.name, gender?.name.orEmpty(),
            minAge ?: -1, maxAge ?: -1, includeUnknownAge, area.orEmpty(), waitingOnly.toSavedInt(), publicOnly.toSavedInt(), sort.name)
    } },
    restore = { v -> if (v.isEmpty()) null else SearchPreset(v[0] as String, v[1] as String, v[2] as String,
        RoomSearchCriteria(name = v[3] as String, message = v[4] as String, excluded = v[5] as String,
            keywordMode = KeywordMode.valueOf(v[6] as String), gender = (v[7] as String).takeIf { it.isNotEmpty() }?.let(Gender::valueOf),
            minAge = (v[8] as Int).takeIf { it >= 0 }, maxAge = (v[9] as Int).takeIf { it >= 0 }, includeUnknownAge = v[10] as Boolean,
            area = (v[11] as String).takeIf { it.isNotEmpty() }, waitingOnly = (v[12] as Int).toSavedBoolean(),
            publicOnly = (v[13] as Int).toSavedBoolean(), sort = RoomSort.valueOf(v[14] as String))) },
)

private fun Boolean?.toSavedInt(): Int = when (this) { null -> -1; true -> 1; false -> 0 }
private fun Int.toSavedBoolean(): Boolean? = when (this) { -1 -> null; 1 -> true; else -> false }
