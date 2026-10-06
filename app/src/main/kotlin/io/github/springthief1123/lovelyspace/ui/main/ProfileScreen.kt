package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.core.messageWidth
import io.github.springthief1123.lovelyspace.core.validProfile
import io.github.springthief1123.lovelyspace.data.MessagePreset
import io.github.springthief1123.lovelyspace.data.ProfilePreset
import io.github.springthief1123.lovelyspace.ui.create.PrefectureField
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpacing
import io.github.springthief1123.lovelyspace.ui.theme.lovelyMainContentTopPadding

@Composable
fun ProfileScreen(onOpenSettings: () -> Unit, onCreateRoom: () -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val vm: PresetViewModel = viewModel(factory = viewModelFactory { initializer { PresetViewModel(app.presets) } })
    val profiles by vm.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val messages by vm.messages.collectAsStateWithLifecycle(initialValue = emptyList())
    val error by vm.error.collectAsStateWithLifecycle()
    val working by vm.working.collectAsStateWithLifecycle()
    var profileEditor by rememberSaveable { mutableStateOf(false) }
    var messageEditor by rememberSaveable { mutableStateOf(false) }
    var profile by rememberSaveable(stateSaver = ProfilePresetSaver) { mutableStateOf<ProfilePreset?>(null) }
    var message by rememberSaveable(stateSaver = MessagePresetSaver) { mutableStateOf<MessagePreset?>(null) }
    var deleting by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = LovelySpacing.screenHorizontal, end = LovelySpacing.screenHorizontal,
            top = lovelyMainContentTopPadding(), bottom = LovelySpacing.bottomContentInset + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { io.github.springthief1123.lovelyspace.ui.components.QuietHeading("YOUR OWN SPACE", "マイルーム", "いつものあなたで、心地よい会話を。") }
        item { io.github.springthief1123.lovelyspace.ui.components.QuietPanel {
            Button(onClick = onCreateRoom, modifier = Modifier.fillMaxWidth()) { Text("部屋をつくる") }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("表示・一覧・非表示の設定") }
        } }
        item { Text("保存したプロフィールと募集文を、入室・部屋作成で選べます。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (error != null) item {
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = vm::reload, enabled = !working) { Text("もう一度読み込む") }
        }
        item { SectionHeading("プロフィール", enabled = !working) { profile = null; profileEditor = true } }
        if (profiles.isEmpty()) item { Text("プロフィールを保存すると、名前・性別・年齢・地域をまとめて使えます。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(profiles, key = { "profile-${it.id}" }) { p ->
            PresetRow(p.label, "${p.name} · ${if (p.sex == 1) "男" else "女"} · ${p.years?.let { "${it}歳" } ?: "年齢は秘密"}", p.isDefault,
                enabled = !working, onEdit = { profile = p; profileEditor = true },
                onDefault = { vm.action { vm.repository.setDefaultProfile(p.id) } }, onDelete = { deleting = true to p.id })
        }
        item { SectionHeading("募集文", enabled = !working) { message = null; messageEditor = true } }
        if (messages.isEmpty()) item { Text("よく使う募集文を保存できます。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(messages, key = { "message-${it.id}" }) { m ->
            PresetRow(m.label, m.message, m.isDefault, enabled = !working,
                onEdit = { message = m; messageEditor = true },
                onDefault = { vm.action { vm.repository.setDefaultMessage(m.id) } }, onDelete = { deleting = false to m.id })
        }
    }
    if (profileEditor) ProfileEditor(profile, working, error, onDismiss = { if (!working) profileEditor = false }) { value ->
        vm.action(onSuccess = { profileEditor = false }) { vm.repository.save(value) }
    }
    if (messageEditor) MessageEditor(message, working, error, onDismiss = { if (!working) messageEditor = false }) { value ->
        vm.action(onSuccess = { messageEditor = false }) { vm.repository.save(value) }
    }
    deleting?.let { target ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("プリセットを削除しますか？") },
            confirmButton = { TextButton(enabled = !working, onClick = {
                vm.action(onSuccess = { deleting = null }) {
                    if (target.first) vm.repository.deleteProfile(target.second) else vm.repository.deleteMessage(target.second)
                }
            }) { Text("削除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("キャンセル") } })
    }
}

@Composable
private fun SectionHeading(title: String, enabled: Boolean, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        TextButton(onClick = onAdd, enabled = enabled) { Text("追加") }
    }
}

@Composable
private fun PresetRow(label: String, detail: String, isDefault: Boolean, enabled: Boolean, onEdit: () -> Unit, onDefault: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(label + if (isDefault) " · 既定" else "", style = MaterialTheme.typography.titleSmall)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Row {
            TextButton(onClick = onEdit, enabled = enabled) { Text("編集") }
            if (!isDefault) TextButton(onClick = onDefault, enabled = enabled) { Text("既定にする") }
            TextButton(onClick = onDelete, enabled = enabled) { Text("削除") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ProfileEditor(preset: ProfilePreset?, working: Boolean, error: String?, onDismiss: () -> Unit, onSave: (ProfilePreset) -> Unit) {
    var label by rememberSaveable { mutableStateOf(preset?.label.orEmpty()) }
    var name by rememberSaveable { mutableStateOf(preset?.name.orEmpty()) }
    var sex by rememberSaveable { mutableIntStateOf(preset?.sex ?: 1) }
    var years by rememberSaveable { mutableStateOf(preset?.years?.toString().orEmpty()) }
    var prefecture by rememberSaveable { mutableStateOf(preset?.prefecture) }
    var isDefault by rememberSaveable { mutableStateOf(preset?.isDefault ?: false) }
    val validYears = years.isEmpty() || years.toIntOrNull()?.let { it in 18..99 } == true
    val valid = label.isNotBlank() && validYears && validProfile(name, sex, years.toIntOrNull())
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (preset == null) "プロフィールを追加" else "プロフィールを編集") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(label, { label = it }, label = { Text("保存名") }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("名前") }, singleLine = true)
                Row { TextButton(onClick = { sex = 1 }) { Text(if (sex == 1) "✓ 男" else "男") }; TextButton(onClick = { sex = 2 }) { Text(if (sex == 2) "✓ 女" else "女") } }
                OutlinedTextField(years, { years = it.filter(Char::isDigit).take(2) }, label = { Text("年齢（空欄は秘密）") }, singleLine = true,
                    isError = !validYears, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                PrefectureField(prefecture) { prefecture = it }
                Row { Checkbox(isDefault, { isDefault = it }); Text("既定のプロフィールにする", modifier = Modifier.padding(top = 12.dp)) }
            }
        }, confirmButton = { TextButton(enabled = valid && !working, onClick = {
            onSave(ProfilePreset(id = preset?.id ?: java.util.UUID.randomUUID().toString(), label = label, name = name,
                sex = sex, years = years.toIntOrNull(), prefecture = prefecture, isDefault = isDefault))
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("キャンセル") } })
}

@Composable
private fun MessageEditor(preset: MessagePreset?, working: Boolean, error: String?, onDismiss: () -> Unit, onSave: (MessagePreset) -> Unit) {
    var label by rememberSaveable { mutableStateOf(preset?.label.orEmpty()) }
    var message by rememberSaveable { mutableStateOf(preset?.message.orEmpty()) }
    var isDefault by rememberSaveable { mutableStateOf(preset?.isDefault ?: false) }
    val width = messageWidth(message)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("募集文") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(label, { label = it }, label = { Text("保存名") }, singleLine = true)
            OutlinedTextField(message, { message = it.replace('\n', ' ') }, label = { Text("募集文") },
                isError = width > 500, supportingText = { Text("$width / 500（全角は2文字）") })
            Row { Checkbox(isDefault, { isDefault = it }); Text("既定のメッセージにする", modifier = Modifier.padding(top = 12.dp)) }
        }
    }, confirmButton = { TextButton(enabled = label.isNotBlank() && message.isNotBlank() && width <= 500 && !working, onClick = {
        onSave(MessagePreset(id = preset?.id ?: java.util.UUID.randomUUID().toString(), label = label, message = message, isDefault = isDefault))
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("キャンセル") } })
}

// 一覧の非同期読み込みより先に、編集中の対象と入力欄を復元できるよう保存する。
internal val ProfilePresetSaver = listSaver<ProfilePreset?, Any>(
    save = { p -> if (p == null) emptyList() else listOf(p.id, p.label, p.name, p.sex, p.years ?: -1, p.prefecture ?: -1, p.isDefault) },
    restore = { values -> if (values.isEmpty()) null else ProfilePreset(values[0] as String, values[1] as String, values[2] as String,
        values[3] as Int, (values[4] as Int).takeIf { it >= 0 }, (values[5] as Int).takeIf { it >= 0 }, values[6] as Boolean) },
)
internal val MessagePresetSaver = listSaver<MessagePreset?, Any>(
    save = { m -> if (m == null) emptyList() else listOf(m.id, m.label, m.message, m.isDefault) },
    restore = { values -> if (values.isEmpty()) null else MessagePreset(values[0] as String, values[1] as String, values[2] as String, values[3] as Boolean) },
)
