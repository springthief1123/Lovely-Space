package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import io.github.springthief1123.lovelyspace.data.ProfilePreset
import io.github.springthief1123.lovelyspace.data.MessagePreset

@Composable
fun ProfilePresetPicker(profiles: List<ProfilePreset>, enabled: Boolean = true, onSelect: (ProfilePreset) -> Unit) {
    if (profiles.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, enabled = enabled) { Text("保存したプロフィールを使う") }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("プロフィールを選ぶ") }, text = {
        LazyColumn { items(profiles, key = { it.id }) { p -> TextButton(onClick = { onSelect(p); open = false }) { Text(p.label + " · " + p.name) } } }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("閉じる") } })
}

@Composable
fun MessagePresetPicker(messages: List<MessagePreset>, onSelect: (MessagePreset) -> Unit) {
    if (messages.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("保存した待機メッセージを使う") }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("待機メッセージを選ぶ") }, text = {
        LazyColumn { items(messages, key = { it.id }) { m -> TextButton(onClick = { onSelect(m); open = false }) { Text(m.label) } } }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("閉じる") } })
}
