package io.github.springthief1123.lovelyspace.ui.main

import io.github.springthief1123.lovelyspace.ui.components.QuietDialog
import io.github.springthief1123.lovelyspace.ui.components.QuietCheckboxRow
import io.github.springthief1123.lovelyspace.ui.components.QuietFilterChip
import androidx.compose.foundation.layout.*
import io.github.springthief1123.lovelyspace.ui.components.QuietHeading
import io.github.springthief1123.lovelyspace.ui.components.QuietListPanel
import io.github.springthief1123.lovelyspace.ui.components.QuietMenuItem
import io.github.springthief1123.lovelyspace.ui.components.QuietOverflowMenu
import io.github.springthief1123.lovelyspace.ui.components.QuietPage
import io.github.springthief1123.lovelyspace.ui.components.QuietSectionHeader
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
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
import io.github.springthief1123.lovelyspace.ui.theme.lovelyMainContentBottomInset
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
    QuietPage {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = LovelySpacing.screenHorizontal, end = LovelySpacing.screenHorizontal,
            top = lovelyMainContentTopPadding(), bottom = lovelyMainContentBottomInset() + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { QuietHeading("マイルーム") }
        item { Button(onClick = onCreateRoom, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("部屋をつくる")
        } }
        item { QuietListPanel(onClick = onOpenSettings) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("設定", style = MaterialTheme.typography.titleSmall)
                    Text("表示・一覧・非表示にした部屋", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 10.dp))
            }
        } }
        if (error != null) item {
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = vm::reload, enabled = !working) { Text("もう一度読み込む") }
        }
        item { SectionHeading("プロフィール", "入室・部屋作成で選べます", enabled = !working) { profile = null; profileEditor = true } }
        if (profiles.isEmpty()) item { Text("プロフィールを保存すると、名前・性別・年齢・地域をまとめて使えます。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(profiles, key = { "profile-${it.id}" }) { p ->
            PresetRow(p.label, listOfNotNull(p.name, if (p.sex == 1) "男性" else "女性", p.years?.let { "${it}歳" } ?: "年齢は秘密").joinToString("・"), p.isDefault,
                enabled = !working, onEdit = { profile = p; profileEditor = true },
                onDefault = { vm.action { vm.repository.setDefaultProfile(p.id) } }, onDelete = { deleting = true to p.id })
        }
        item { SectionHeading("待機メッセージ", "部屋作成で選べます", enabled = !working) { message = null; messageEditor = true } }
        if (messages.isEmpty()) item { Text("よく使う待機メッセージを保存できます。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(messages, key = { "message-${it.id}" }) { m ->
            PresetRow(m.label, m.message, m.isDefault, enabled = !working,
                onEdit = { message = m; messageEditor = true },
                onDefault = { vm.action { vm.repository.setDefaultMessage(m.id) } }, onDelete = { deleting = false to m.id })
        }
    }
    }
    if (profileEditor) ProfileEditor(profile, working, error, onDismiss = { if (!working) profileEditor = false }) { value ->
        vm.action(onSuccess = { profileEditor = false }) { vm.repository.save(value) }
    }
    if (messageEditor) MessageEditor(message, working, error, onDismiss = { if (!working) messageEditor = false }) { value ->
        vm.action(onSuccess = { messageEditor = false }) { vm.repository.save(value) }
    }
    deleting?.let { target ->
        QuietDialog(title = "プリセットを削除しますか？", onDismissRequest = { deleting = null },
            confirmLabel = "削除", confirmEnabled = !working, destructive = true, onConfirm = {
                vm.action(onSuccess = { deleting = null }) {
                    if (target.first) vm.repository.deleteProfile(target.second) else vm.repository.deleteMessage(target.second)
                }
            })
    }
}

@Composable
private fun SectionHeading(title: String, supporting: String, enabled: Boolean, onAdd: () -> Unit) {
    QuietSectionHeader(title, supporting) {
        TextButton(onClick = onAdd, enabled = enabled) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("追加")
        }
    }
}

/** 保存したプリセットの1行。タップで編集し、既定・削除は「︙」にまとめる。 */
@Composable
private fun PresetRow(label: String, detail: String, isDefault: Boolean, enabled: Boolean, onEdit: () -> Unit, onDefault: () -> Unit, onDelete: () -> Unit) {
    QuietListPanel(onClick = if (enabled) onEdit else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    if (isDefault) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small,
                        modifier = Modifier.padding(start = 8.dp)) {
                        Text("既定", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            QuietOverflowMenu(listOfNotNull(
                QuietMenuItem("編集", enabled = enabled, onClick = onEdit),
                if (!isDefault) QuietMenuItem("既定にする", enabled = enabled, onClick = onDefault) else null,
                QuietMenuItem("削除", enabled = enabled, destructive = true, onClick = onDelete),
            ), contentDescription = "${label}の操作")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    QuietDialog(title = if (preset == null) "プロフィールを追加" else "プロフィールを編集", onDismissRequest = onDismiss,
        confirmLabel = "保存", confirmEnabled = valid && !working, dismissEnabled = !working, error = error, onConfirm = {
            onSave(ProfilePreset(id = preset?.id ?: java.util.UUID.randomUUID().toString(), label = label, name = name,
                sex = sex, years = years.toIntOrNull(), prefecture = prefecture, isDefault = isDefault))
        }) {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("保存名") }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("名前") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuietFilterChip(sex == 1, { sex = 1 }, label = "男性")
                    QuietFilterChip(sex == 2, { sex = 2 }, label = "女性")
                }
                OutlinedTextField(years, { years = it.filter(Char::isDigit).take(2) }, label = { Text("年齢（空欄は秘密）") }, singleLine = true,
                    isError = !validYears, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                PrefectureField(prefecture) { prefecture = it }
                QuietCheckboxRow(isDefault, { isDefault = it }, "既定のプロフィールにする")
            }
        }
}

@Composable
private fun MessageEditor(preset: MessagePreset?, working: Boolean, error: String?, onDismiss: () -> Unit, onSave: (MessagePreset) -> Unit) {
    var label by rememberSaveable { mutableStateOf(preset?.label.orEmpty()) }
    var message by rememberSaveable { mutableStateOf(preset?.message.orEmpty()) }
    var isDefault by rememberSaveable { mutableStateOf(preset?.isDefault ?: false) }
    val width = messageWidth(message)
    QuietDialog(title = if (preset == null) "待機メッセージを追加" else "待機メッセージを編集", onDismissRequest = onDismiss,
        confirmLabel = "保存", confirmEnabled = label.isNotBlank() && message.isNotBlank() && width <= 500 && !working,
        dismissEnabled = !working, error = error, onConfirm = {
            onSave(MessagePreset(id = preset?.id ?: java.util.UUID.randomUUID().toString(), label = label, message = message, isDefault = isDefault))
        }) {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(label, { label = it }, label = { Text("保存名") }, singleLine = true)
            OutlinedTextField(message, { message = it.replace('\n', ' ') }, label = { Text("待機メッセージ") },
                isError = width > 500, supportingText = { Text("$width / 500（全角は2文字）") })
            QuietCheckboxRow(isDefault, { isDefault = it }, "既定のメッセージにする")
        }
    }
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
