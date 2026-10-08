@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.springthief1123.lovelyspace.ui.entry

import io.github.springthief1123.lovelyspace.ui.components.quietSegmentedButtonColors
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import io.github.springthief1123.lovelyspace.ui.components.QuietHeading
import io.github.springthief1123.lovelyspace.ui.components.QuietPanel
import io.github.springthief1123.lovelyspace.ui.components.QuietTopBar
import io.github.springthief1123.lovelyspace.core.chat.ChatRoomRef
import io.github.springthief1123.lovelyspace.core.SitePages
import io.github.springthief1123.lovelyspace.ui.main.ProfilePresetPicker
import io.github.springthief1123.lovelyspace.ui.web.SiteWebView
import io.github.springthief1123.lovelyspace.ui.web.prefillFormScript

@Composable
fun EntryScreen(
    host: String,
    genreKey: String,
    roomId: Long,
    onBack: () -> Unit,
    onEntered: (ChatRoomRef) -> Unit,
) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val vm: EntryViewModel = viewModel(
        factory = viewModelFactory { initializer { EntryViewModel(app.client, app.settings, app.presets, host, genreKey, roomId) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var showBrowser by rememberSaveable { mutableStateOf(false) }

    // 端末の戻る操作も、ツールバーの矢印と同じくロボット確認の画面から入力画面へ戻す。
    BackHandler(enabled = showBrowser) { showBrowser = false }

    LaunchedEffect(state.entered) {
        state.entered?.let(onEntered)
    }

    Scaffold(
        topBar = {
            QuietTopBar(if (showBrowser) "ロボット確認" else "入室") {
                if (showBrowser) showBrowser = false else onBack()
            }
        },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        when {
            showBrowser -> SiteWebView(
                url = SitePages.preEnter(host, genreKey, roomId),
                genreKey = genreKey,
                onLoadScript = prefillFormScript(
                    "entry",
                    mapOf("name" to state.name.trim(), "sex" to state.sex.toString(), "years" to state.yearsValue?.toString().orEmpty()),
                ),
                scriptPath = "/PreEnterRoom",
                onRoomOpened = vm::onEnteredInBrowser,
                modifier = modifier,
            )
            state.isLoading && state.form == null -> Box(modifier, contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.form == null -> Column(
                modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.loadError.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = vm::load) { Text("もう一度読み込む") }
                OutlinedButton(onClick = onBack) { Text("一覧に戻る") }
            }
            else -> EntryFormContent(state, vm, onOpenBrowser = { vm.saveProfile(); showBrowser = true }, modifier = modifier)
        }
    }
}

@Composable
private fun EntryFormContent(state: EntryUiState, vm: EntryViewModel, onOpenBrowser: () -> Unit, modifier: Modifier) {
    val form = state.form ?: return
    val profiles by vm.presets.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.fillMaxHeight().widthIn(max = 720.dp).fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            QuietHeading("入室")
            QuietPanel {
                Text("待機している相手", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(form.hostDescription, style = MaterialTheme.typography.titleSmall)
                if (form.waitingMessage.isNotBlank()) {
                    Text(form.waitingMessage, style = MaterialTheme.typography.bodyMedium)
                }
            }

            QuietPanel {
                Text("あなたのプロフィール", style = MaterialTheme.typography.titleSmall)

                ProfilePresetPicker(profiles, enabled = !state.isEntering, onSelect = vm::applyPreset)

                OutlinedTextField(
                    value = state.name,
                    onValueChange = vm::setName,
                    enabled = !state.isEntering,
                    label = { Text("名前") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )

                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(1 to "男", 2 to "女").forEachIndexed { i, (value, label) ->
                        SegmentedButton(
                            selected = state.sex == value,
                            enabled = !state.isEntering,
                            onClick = { vm.setSex(value) },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                            colors = quietSegmentedButtonColors(),
                        ) { Text(label) }
                    }
                }

                OutlinedTextField(
                    value = state.years,
                    onValueChange = vm::setYears,
                    enabled = !state.isEntering,
                    label = { Text("年齢") },
                    placeholder = { Text("空欄なら秘密") },
                    isError = !state.yearsValid,
                    supportingText = if (!state.yearsValid) {
                        { Text("${EntryUiState.MIN_YEARS}〜${EntryUiState.MAX_YEARS} で入力してください") }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.enter() }),
                    modifier = Modifier.fillMaxWidth(),
                )

            }

            state.entryError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            if (form.requiresCaptcha) {
                QuietPanel {
                    Text("ロボット確認", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "この部屋に入るにはロボット確認が必要です。ラブルームの入室画面を開くので、確認のあと「入室」を押してください。名前などは入力済みになります。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = onOpenBrowser,
                        enabled = state.name.isNotBlank() && state.yearsValid && !state.isLoading && !state.isEntering && state.entered == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("ロボット確認へ進む") }
                }
            } else {
                Button(onClick = vm::enter, enabled = state.canEnter, modifier = Modifier.fillMaxWidth()) {
                    if (state.isEntering) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("  入室しています…")
                    } else {
                        Text("この部屋に入室する")
                    }
                }
            }
        }
    }
}
