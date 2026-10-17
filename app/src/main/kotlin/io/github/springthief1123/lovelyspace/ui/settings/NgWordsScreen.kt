package io.github.springthief1123.lovelyspace.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.core.NgTarget
import io.github.springthief1123.lovelyspace.core.NgWordSet
import io.github.springthief1123.lovelyspace.data.NgWordRepository
import io.github.springthief1123.lovelyspace.ui.components.QuietConfirmDialog
import io.github.springthief1123.lovelyspace.ui.components.QuietEditorScreen
import io.github.springthief1123.lovelyspace.ui.components.QuietFilterChip
import io.github.springthief1123.lovelyspace.ui.components.QuietListPanel
import io.github.springthief1123.lovelyspace.ui.components.QuietSwitchRow
import io.github.springthief1123.lovelyspace.ui.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class NgWordsUiState(
    val sets: List<NgWordSet> = emptyList(),
    val loading: Boolean = true,
    val working: Boolean = false,
    val loadError: String? = null,
    val editError: String? = null,
)

class NgWordsViewModel(private val repository: NgWordRepository) : ViewModel() {
    private val _state = MutableStateFlow(NgWordsUiState())
    val state = _state.asStateFlow()
    private var collection: Job? = null
    init { reload() }

    fun reload() {
        collection?.cancel()
        _state.update { it.copy(loading = true, loadError = null) }
        collection = viewModelScope.launch {
            try { repository.sets.collect { values -> _state.update { it.copy(sets = values, loading = false) } } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(loading = false, loadError = describeError(e)) } }
        }
    }

    fun clearEditError() = _state.update { it.copy(editError = null) }
    fun save(value: NgWordSet, onSuccess: () -> Unit) = action(onSuccess) { repository.save(value) }
    fun delete(id: String, onSuccess: () -> Unit) = action(onSuccess) { repository.delete(id) }
    fun setEnabled(id: String, enabled: Boolean) = action({}) { repository.setEnabled(id, enabled) }

    private fun action(onSuccess: () -> Unit, block: suspend () -> Unit) {
        if (_state.value.working) return
        _state.update { it.copy(working = true, editError = null) }
        viewModelScope.launch {
            try { block(); onSuccess() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(editError = e.takeIf { it is IllegalArgumentException }?.message ?: describeError(e)) } }
            finally { _state.update { it.copy(working = false) } }
        }
    }
}

/** 設定 > NG ワード。セットの一覧と ON/OFF、セットの追加・編集・削除。 */
@Composable
fun NgWordsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val vm: NgWordsViewModel = viewModel(factory = viewModelFactory { initializer { NgWordsViewModel(app.ngWords) } })
    val state by vm.state.collectAsStateWithLifecycle()
    // 編集中のセットの ID。新しいセットは保存するまで一覧に無い ID になる。画面が作り直されても編集を開いたままにする。
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SettingsPageHeader(title = "NGワード", onBack = onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp,
                bottom = 20.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "オンのセットの語が名前か待機メッセージに含まれる部屋を、見つける・レーダーでいつも除外します。" +
                        "ひらがな・カタカナ、全角・半角、大文字・小文字の違いは区別しません。" +
                        "その検索だけで外したい語は、検索の詳しい条件の「除外する語」に入れてください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            if (state.loading) item { CircularProgressIndicator() }
            state.loadError?.let { error -> item {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = vm::reload) { Text("もう一度読み込む") }
            } }
            if (editingId == null) state.editError?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            items(state.sets, key = { it.id }) { set ->
                QuietListPanel(onClick = { vm.clearEditError(); editingId = set.id }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(set.label, style = MaterialTheme.typography.titleSmall)
                            Text(ngSetSummary(set), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Switch(checked = set.enabled, onCheckedChange = { vm.setEnabled(set.id, it) }, enabled = !state.working,
                            modifier = Modifier.semantics {
                                contentDescription = "${set.label}を使う"
                                stateDescription = if (set.enabled) "オン" else "オフ"
                            })
                    }
                }
            }
            if (!state.loading && state.loadError == null) item {
                OutlinedButton(onClick = { vm.clearEditError(); editingId = NEW_PREFIX + UUID.randomUUID() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("新しいセット")
                }
            }
        }
    }

    editingId?.let { id ->
        val existing = state.sets.firstOrNull { it.id == id }
        if (existing == null && !id.startsWith(NEW_PREFIX)) {
            // 編集中のセットが（別の操作で）消えたら閉じる。
            if (!state.loading) LaunchedEffect(id) { editingId = null }
            return@let
        }
        NgWordSetEditor(
            initial = existing ?: NgWordSet(id.removePrefix(NEW_PREFIX), "", emptyList()),
            isNew = existing == null,
            working = state.working,
            error = state.editError,
            onDismiss = { vm.clearEditError(); editingId = null },
            onSave = { vm.save(it) { editingId = null } },
            onDelete = { vm.delete(id) { editingId = null } },
        )
    }
}

/** 一覧の行の説明。例:「17語・金額の表記・名前と待機メッセージ」。 */
internal fun ngSetSummary(set: NgWordSet): String = listOfNotNull(
    if (set.words.isEmpty()) null else "${set.words.size}語（${set.words.take(4).joinToString("・")}${if (set.words.size > 4) "…" else ""}）",
    if (set.amounts) "金額の表記" else null,
    ngTargetLabel(set.target),
).joinToString("・")

internal fun ngTargetLabel(target: NgTarget) = when (target) {
    NgTarget.NAME_AND_MESSAGE -> "名前と待機メッセージ"
    NgTarget.NAME -> "名前だけ"
    NgTarget.MESSAGE -> "待機メッセージだけ"
}

private const val NEW_PREFIX = "new:"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NgWordSetEditor(
    initial: NgWordSet,
    isNew: Boolean,
    working: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (NgWordSet) -> Unit,
    onDelete: () -> Unit,
) {
    var label by rememberSaveable(initial.id) { mutableStateOf(initial.label) }
    // 語は改行区切りの 1 つの文字列で持つ（画面の作り直しでも残せるように）。
    var wordsText by rememberSaveable(initial.id) { mutableStateOf(initial.words.joinToString("\n")) }
    var amounts by rememberSaveable(initial.id) { mutableStateOf(initial.amounts) }
    var target by rememberSaveable(initial.id) { mutableStateOf(initial.target) }
    var input by rememberSaveable(initial.id) { mutableStateOf("") }
    var confirmDelete by rememberSaveable(initial.id) { mutableStateOf(false) }
    val words = wordsText.split('\n').filter(String::isNotEmpty)
    // 入力欄に残った語も保存に含める（追加を押し忘れても消えない）。
    fun allWords() = (words + NgWordSet.splitWords(input)).distinct()
    fun addInput() {
        val added = (words + NgWordSet.splitWords(input)).distinct()
        wordsText = added.joinToString("\n")
        input = ""
    }
    val builtIn = initial.builtInKey?.let(NgWordSet::builtIn)

    QuietEditorScreen(
        title = if (isNew) "新しいセット" else "セットを編集",
        onDismiss = onDismiss,
        saveEnabled = label.isNotBlank() && (allWords().isNotEmpty() || amounts),
        working = working,
        onSave = { onSave(initial.copy(label = label, words = allWords(), amounts = amounts, target = target)) },
        error = error,
    ) {
        OutlinedTextField(label, { label = it.take(NgWordRepository.MAX_LABEL) }, label = { Text("セットの名前") },
            placeholder = { Text("例: お金目的") }, singleLine = true, enabled = !working, modifier = Modifier.fillMaxWidth())

        Text("語（${words.size}）", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it }, label = { Text("足す語") },
                supportingText = { Text("空白で区切ると、まとめて足せます") },
                singleLine = true, enabled = !working, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addInput() }))
            TextButton(onClick = ::addInput, enabled = !working && input.isNotBlank()) { Text("追加") }
        }
        if (words.isEmpty()) Text("まだ語がありません。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            words.forEach { word ->
                QuietFilterChip(selected = false, onClick = { wordsText = (words - word).joinToString("\n") }, label = word, enabled = !working,
                    modifier = Modifier.semantics { contentDescription = "$word を外す" },
                    trailingIcon = { Icon(Icons.Outlined.Close, null, Modifier.size(16.dp)) })
            }
        }

        QuietSwitchRow(amounts, { amounts = it }, "金額の表記も除外", enabled = !working,
            description = "3000円・1万円・五千円・¥500 など。「円満」や「20歳」は除外しません。",
            contentPadding = PaddingValues(vertical = 8.dp))

        Text("見る場所", style = MaterialTheme.typography.labelLarge)
        SettingsChoiceGroup {
            NgTarget.entries.forEach { value ->
                SettingsChoiceRow(ngTargetLabel(value), selected = target == value, onClick = { if (!working) target = value })
            }
        }

        if (builtIn != null) TextButton(onClick = { wordsText = builtIn.words.joinToString("\n"); amounts = builtIn.amounts; target = builtIn.target },
            enabled = !working) { Text("初期の語に戻す") }
        if (!isNew) TextButton(onClick = { confirmDelete = true }, enabled = !working,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("このセットを削除") }
    }

    if (confirmDelete) QuietConfirmDialog(
        title = "セットを削除しますか？",
        text = "「${initial.label}」と、その語をすべて削除します。" + if (builtIn != null) "最初から入っていたセットは、削除すると元に戻せません。" else "",
        confirmLabel = "削除",
        onConfirm = { confirmDelete = false; onDelete() },
        onDismiss = { confirmDelete = false },
        destructive = true,
    )
}
