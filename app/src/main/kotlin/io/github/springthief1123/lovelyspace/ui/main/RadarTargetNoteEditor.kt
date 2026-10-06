package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.foundation.layout.*
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpacing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.data.TrackedRoom

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RadarTargetNoteEditor(target: TrackedRoom, working: Boolean, error: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var note by rememberSaveable(target.identity) { mutableStateOf(target.note) }
    ModalBottomSheet(onDismissRequest = { if (!working) onDismiss() }) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(LovelySpacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${target.identity.name}のメモ", style = MaterialTheme.typography.titleMedium)
            Text("自分の端末内に保存します。相手には送信されません。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(note, { note = it }, label = { Text("自分用メモ") }, enabled = !working, minLines = 3, maxLines = 6,
                isError = note.length > 500, supportingText = { Text("${note.length}/500文字") }, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { onSave(note) }, enabled = !working && note.length <= 500, modifier = Modifier.fillMaxWidth()) { Text(if (working) "保存中…" else "メモを保存") }
            TextButton(onClick = onDismiss, enabled = !working) { Text("戻る") }
        }
    }
}
