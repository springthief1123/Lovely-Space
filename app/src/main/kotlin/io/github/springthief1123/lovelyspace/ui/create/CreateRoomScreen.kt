@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.springthief1123.lovelyspace.ui.create

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.core.Genre
import io.github.springthief1123.lovelyspace.core.Prefectures
import io.github.springthief1123.lovelyspace.core.SitePages
import io.github.springthief1123.lovelyspace.core.chat.ChatRoomRef
import io.github.springthief1123.lovelyspace.ui.web.SiteWebView
import io.github.springthief1123.lovelyspace.ui.web.prefillFormScript

/**
 * 部屋を作る。サイトの部屋作成は毎回ロボット確認が必要なので、アプリで項目を入力してから
 * 本家の作成画面を開き、入力済みにした状態で確認と作成ボタンを利用者に押してもらう。
 */
@Composable
fun CreateRoomScreen(genre: Genre, onBack: () -> Unit, onCreated: (ChatRoomRef) -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val vm: CreateRoomViewModel = viewModel(factory = viewModelFactory { initializer { CreateRoomViewModel(app.settings) } })
    val state by vm.state.collectAsStateWithLifecycle()
    var showBrowser by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = showBrowser) { showBrowser = false }

    LaunchedEffect(state.created) {
        state.created?.let(onCreated)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showBrowser) "ロボット確認" else "部屋を作る（${genre.label}）") },
                navigationIcon = {
                    IconButton(onClick = { if (showBrowser) showBrowser = false else onBack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        if (showBrowser) {
            // kct はサイトのリンクと同じく開いた時刻。画面を開き直すまで変えない。
            val url = remember { SitePages.makeRoom(genre, System.currentTimeMillis() / 1000) }
            SiteWebView(
                url = url,
                genreKey = genre.key,
                onLoadScript = prefillFormScript(
                    "makeroom",
                    mapOf(
                        "name" to state.name.trim(),
                        "sex" to state.sex.toString(),
                        "years" to state.yearsValue?.toString().orEmpty(),
                        "prefecture" to state.prefecture?.toString().orEmpty(),
                        "message" to state.message.trim(),
                    ),
                ),
                scriptPath = "/PreMakeRoom",
                onRoomOpened = vm::onCreated,
                modifier = modifier,
            )
        } else {
            CreateRoomForm(state, vm, onContinue = { vm.saveInputs(); showBrowser = true }, modifier = modifier)
        }
    }
}

@Composable
private fun CreateRoomForm(state: CreateRoomUiState, vm: CreateRoomViewModel, onContinue: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = state.name,
            onValueChange = vm::setName,
            label = { Text("名前") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(1 to "男", 2 to "女").forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = state.sex == value,
                    onClick = { vm.setSex(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(label) }
            }
        }

        OutlinedTextField(
            value = state.years,
            onValueChange = vm::setYears,
            label = { Text("年齢") },
            placeholder = { Text("空欄なら秘密") },
            isError = !state.yearsValid,
            supportingText = if (!state.yearsValid) {
                { Text("${CreateRoomUiState.MIN_YEARS}〜${CreateRoomUiState.MAX_YEARS} で入力してください") }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )

        PrefectureField(state.prefecture, vm::setPrefecture)

        OutlinedTextField(
            value = state.message,
            onValueChange = vm::setMessage,
            label = { Text("待機メッセージ") },
            isError = !state.messageValid,
            supportingText = {
                Text("${state.messageWidth} / ${CreateRoomUiState.MESSAGE_MAX_WIDTH}（全角は 2 文字として数えます）")
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
        )

        Text(
            "部屋の作成には毎回ロボット確認が必要です。ラブルームの作成画面を開くので、確認のあと作成ボタンを押してください。入力した内容は入力済みになります。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onContinue, enabled = state.canContinue, modifier = Modifier.fillMaxWidth()) {
            Text("ロボット確認へ進む")
        }
    }
}

@Composable
private fun PrefectureField(selected: Int?, onSelect: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.let(Prefectures::name) ?: "秘密",
            onValueChange = {},
            readOnly = true,
            label = { Text("地域") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("秘密") }, onClick = { onSelect(null); expanded = false })
            Prefectures.names.forEachIndexed { i, name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(i + 1); expanded = false })
            }
        }
    }
}
