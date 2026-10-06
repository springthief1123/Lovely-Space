package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CandidateRuleEditor(value: CandidateRule, working: Boolean, error: String?, onDismiss: () -> Unit, onSave: (CandidateRule) -> Unit) {
    var label by rememberSaveable(value.id) { mutableStateOf(value.label) }
    var genre by rememberSaveable(value.id) { mutableStateOf(value.genreKey) }
    var term by rememberSaveable(value.id) { mutableStateOf(value.term) }
    var mode by rememberSaveable(value.id) { mutableStateOf(value.mode) }
    ModalBottomSheet(onDismissRequest = { if (!working) onDismiss() }) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("候補監視の条件", style = MaterialTheme.typography.titleLarge)
            Text("一覧に表示されている名前だけを対象にします。同じ名前や識別文字列の一致は、同じ人であることの証明にはなりません。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(label, { label = it.take(80) }, enabled = !working, singleLine = true,
                label = { Text("条件名") }, modifier = Modifier.fillMaxWidth())
            RadarDropdown("確認するジャンル", genre, Genres.all.map { it.key to it.label }, !working) { genre = it }
            RadarChoice("一致の方法", mode, listOf(CandidateMode.EXACT_NAME to "名前の完全一致", CandidateMode.DISPLAY_TEXT to "表示名に含まれる文字列"), !working) { mode = it }
            OutlinedTextField(term, { term = it.take(160) }, enabled = !working, label = { Text(if (mode == CandidateMode.EXACT_NAME) "表示されている名前" else "表示名に含まれる文字列") },
                supportingText = { Text("大文字・小文字を区別します。公開トリップなどは、一覧の表示名に含まれている場合にだけ判定できます。") },
                modifier = Modifier.fillMaxWidth())
            Text("手動巡回で1ページずつ確認します。初回は比較の基準を作り、未取得のページや名前が非表示の部屋は判定しません。最大20条件を保存できます。", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !working && label.isNotBlank() && term.isNotBlank(), onClick = {
                onSave(value.copy(label = label, genreKey = genre, term = term, mode = mode))
            }, modifier = Modifier.fillMaxWidth()) { Text(if (working) "保存中…" else "条件を保存") }
            TextButton(enabled = !working, onClick = onDismiss) { Text("キャンセル") }
        }
    }
}

internal val CandidateRuleSaver = listSaver<CandidateRule?, Any>(
    save = { if (it == null) emptyList() else listOf(it.id, it.label, it.genreKey, it.term, it.mode.name, it.enabled) },
    restore = { if (it.isEmpty()) null else CandidateRule(it[0] as String, it[1] as String, it[2] as String, it[3] as String, CandidateMode.valueOf(it[4] as String), it[5] as Boolean) },
)
