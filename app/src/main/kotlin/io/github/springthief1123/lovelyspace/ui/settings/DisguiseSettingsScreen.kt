package io.github.springthief1123.lovelyspace.ui.settings

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.R
import io.github.springthief1123.lovelyspace.disguise.DisguisePaletteColors
import io.github.springthief1123.lovelyspace.disguise.DisguisePresetColors
import io.github.springthief1123.lovelyspace.disguise.DisguiseSymbol
import io.github.springthief1123.lovelyspace.disguise.LauncherDisguise
import io.github.springthief1123.lovelyspace.disguise.LauncherPreset
import io.github.springthief1123.lovelyspace.disguise.ShortcutDisguise
import io.github.springthief1123.lovelyspace.disguise.applyDisguiseTaskDescription
import io.github.springthief1123.lovelyspace.disguise.formatHexColor
import io.github.springthief1123.lovelyspace.disguise.symbolColorOn
import io.github.springthief1123.lovelyspace.ui.components.QuietConfirmDialog
import io.github.springthief1123.lovelyspace.ui.components.QuietTabs
import io.github.springthief1123.lovelyspace.ui.describeError
import io.github.springthief1123.lovelyspace.ui.theme.LovelyShapes
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ショートカットの状態。[known] は読み終えたか（読む前に「対応していない」と出さないため）。 */
private data class ShortcutState(val supported: Boolean = true, val pinned: Boolean = false, val known: Boolean = false)

private enum class ColorTab(val label: String) {
    PRESET("おすすめ"),
    PALETTE("パレット"),
    WHEEL("カラーホイール"),
}

/**
 * ホーム画面のアイコンと名前のカモフラージュ。
 * - ホーム画面のショートカット: 色・シンボル・名前を自由に組み、ホーム画面アプリにショートカットとして置いてもらう。
 * - アプリ一覧: ランチャーのアイコンは実行時に描けないので、マニフェストに用意した組み合わせ（activity-alias）から選ぶ。
 */
@Composable
fun DisguiseSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as LovelySpaceApp
    val disguise = remember { LauncherDisguise(app) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val bottomContentPadding = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // 編集中の見た目。保存済みの値を一度だけ読み込み、回転などでは編集中の値を残す。
    var loaded by rememberSaveable { mutableStateOf(false) }
    var color by rememberSaveable { mutableIntStateOf(ShortcutDisguise.DEFAULT_COLOR) }
    var symbol by rememberSaveable { mutableStateOf(DisguiseSymbol.CALCULATE) }
    var label by rememberSaveable { mutableStateOf(ShortcutDisguise().label) }
    var colorTab by rememberSaveable { mutableStateOf(ColorTab.PRESET) }
    LaunchedEffect(Unit) {
        if (loaded) return@LaunchedEffect
        val saved = app.settings.shortcutDisguise.first()
        color = saved.color
        symbol = saved.symbol
        label = saved.label
        loaded = true
    }

    // ショートカットが置かれたか（ホーム画面アプリの確認から戻ったとき）・外されたかは、この画面に戻るたびに読み直す。
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    // ShortcutManager はホーム画面アプリとのやり取りで時間がかかることがあるので、読み書きはメインスレッドの外で行う。
    var shortcutState by remember { mutableStateOf(ShortcutState()) }
    LaunchedEffect(refresh) {
        shortcutState = withContext(Dispatchers.IO) {
            ShortcutState(supported = disguise.isPinSupported(), pinned = disguise.isShortcutPinned(), known = true)
        }
    }
    val pinned = shortcutState.pinned
    val pinSupported = shortcutState.supported
    var preset by remember { mutableStateOf(disguise.currentPreset()) }
    var pendingPreset by remember { mutableStateOf<LauncherPreset?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    // 切り替え・追加の途中に重ねて押されないようにする。
    var busy by remember { mutableStateOf(false) }

    fun show(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    /** [block] をメインスレッドの外で実行し、終わったら状態を読み直す。失敗したら [failure] に理由を付けて出す。 */
    fun run(failure: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                show("$failure：${describeError(e)}")
            } finally {
                preset = withContext(Dispatchers.IO) { disguise.currentPreset() }
                (context as? Activity)?.applyDisguiseTaskDescription(preset)
                refresh++
                busy = false
            }
        }
    }

    fun placeShortcut() {
        val design = ShortcutDisguise(color = color, symbol = symbol, label = label.trim())
        run("ホーム画面に追加できませんでした") {
            app.settings.setShortcutDisguise(design)
            val (wasPinned, placed) = withContext(Dispatchers.IO) {
                val wasPinned = disguise.isShortcutPinned()
                wasPinned to disguise.pinShortcut(design)
            }
            when {
                !placed -> show("ホーム画面に追加できませんでした。ホーム画面アプリが対応していない可能性があります。")
                wasPinned -> show("ホーム画面のアイコンを変えました")
                else -> show("ホーム画面アプリの確認で「追加」を選んでください")
            }
        }
    }

    fun switchPreset(next: LauncherPreset) {
        run("切り替えられませんでした") {
            withContext(Dispatchers.IO) { disguise.applyPreset(next) }
            show("アプリ一覧を「${context.getString(next.label)}」にしました。反映まで少しかかることがあります。")
        }
    }

    fun resetToOriginal() {
        run("元に戻せませんでした") {
            val hadShortcut = withContext(Dispatchers.IO) {
                val hadShortcut = disguise.isShortcutPinned()
                disguise.resetToOriginal()
                hadShortcut
            }
            show(
                if (hadShortcut) "元に戻しました。置いてあるアイコンも Lovely Space にしました（要らなければホーム画面から外してください）。"
                else "元のアイコンと名前に戻しました。反映まで少しかかることがあります。",
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            SettingsPageHeader(title = "アイコンと名前", onBack = onBack)
            LazyColumn(contentPadding = PaddingValues(top = 14.dp, bottom = bottomContentPadding)) {
                item {
                    SettingsIntro(
                        "ホーム画面に置くアイコンの色・シンボル・名前を自由に組み合わせて、別のアプリのように見せられます。" +
                            "アプリ一覧のアイコンと名前は、下の候補から選べます。",
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { confirmReset = true },
                        enabled = !busy && (preset != LauncherPreset.DEFAULT || pinned),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = LovelySpacing.screenHorizontal, vertical = 6.dp),
                    ) {
                        Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("元のアイコンと名前に戻す")
                    }
                }
                item { SettingsSectionTitle("ホーム画面のアイコン") }
                item {
                    ShortcutPreview(color, symbol, label.trim().ifEmpty { symbol.label })
                }
                item {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it.replace("\n", "").take(ShortcutDisguise.MAX_LABEL_LENGTH) },
                        label = { Text("ホーム画面の名前") },
                        placeholder = { Text(symbol.label) },
                        singleLine = true,
                        supportingText = {
                            Text("${label.length} / ${ShortcutDisguise.MAX_LABEL_LENGTH}　長い名前はホーム画面で省略されます。空欄ならシンボルの名前になります。")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = LovelySpacing.screenHorizontal, vertical = 8.dp),
                    )
                }
                item { SettingsSubTitle("シンボル") }
                item { SymbolGrid(selected = symbol, background = color, onSelect = { symbol = it }) }
                item { SettingsSubTitle("色") }
                item {
                    Column(
                        Modifier.padding(horizontal = LovelySpacing.screenHorizontal),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        QuietTabs(ColorTab.entries.map { it to it.label }, colorTab) { colorTab = it }
                        when (colorTab) {
                            ColorTab.PRESET -> ColorSwatchGrid(DisguisePresetColors, columns = 6, selected = color) { color = it }
                            ColorTab.PALETTE -> ColorSwatchGrid(DisguisePaletteColors, columns = 6, selected = color) { color = it }
                            ColorTab.WHEEL -> ColorWheelPicker(color) { color = it }
                        }
                    }
                }
                item {
                    Column(
                        Modifier.padding(start = LovelySpacing.screenHorizontal, end = LovelySpacing.screenHorizontal, top = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { placeShortcut() },
                            enabled = loaded && shortcutState.known && pinSupported && !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (pinned) "ホーム画面のアイコンを変える" else "ホーム画面に追加") }
                        Text(
                            when {
                                shortcutState.known && !pinSupported -> "使っているホーム画面アプリが、アプリからのアイコンの追加に対応していません。"
                                pinned -> "置いてあるアイコンの色・シンボル・名前が変わります。"
                                else -> "追加したら、元の Lovely Space のアイコンはホーム画面から外してください（アプリ一覧には残ります）。" +
                                    "ホーム画面アプリによっては、アイコンの隅に小さくアプリ一覧のアイコンが付きます。下でアプリ一覧の見た目も変えると目立ちません。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(top = 20.dp, bottom = 10.dp),
                    )
                }
                item { SettingsSectionTitle("アプリ一覧のアイコンと名前") }
                item {
                    SettingsIntro(
                        "アプリ一覧と、元からホーム画面にあるアイコンの見た目です。端末の仕組み上、自由な組み合わせにはできず、用意した候補から選びます。" +
                            "通知や端末の設定画面のアプリ名は Lovely Space のままです。",
                    )
                }
                item {
                    SettingsChoiceGroup {
                        LauncherPreset.entries.forEach { option ->
                            PresetRow(option, selected = option == preset, onClick = { if (option != preset && !busy) pendingPreset = option })
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
    }

    if (confirmReset) {
        QuietConfirmDialog(
            title = "元のアイコンと名前に戻しますか？",
            text = "アプリ一覧を Lovely Space のアイコンと名前に戻します。ホーム画面に置いたアイコンも Lovely Space の見た目になります（要らなければホーム画面から外してください）。" +
                "選んだ色・シンボル・名前は残るので、あとで置き直せます。端末によっては、戻すとアプリが一度閉じます。",
            confirmLabel = "元に戻す",
            onConfirm = {
                confirmReset = false
                resetToOriginal()
            },
            onDismiss = { confirmReset = false },
        )
    }

    pendingPreset?.let { next ->
        QuietConfirmDialog(
            title = "「${stringResource(next.label)}」に切り替えますか？",
            text = "アプリ一覧のアイコンと名前が変わります。ホーム画面に置いていた元のアイコンは消えることがあるので、必要ならアプリ一覧から置き直してください。" +
                "端末によっては、切り替えるとアプリが一度閉じます。",
            confirmLabel = "切り替える",
            onConfirm = {
                pendingPreset = null
                switchPreset(next)
            },
            onDismiss = { pendingPreset = null },
        )
    }
}

@Composable
private fun SettingsIntro(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = LovelySpacing.screenHorizontal, end = LovelySpacing.screenHorizontal, bottom = 6.dp),
    )
}

@Composable
private fun SettingsSubTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = LovelySpacing.screenHorizontal, top = 14.dp, bottom = 8.dp),
    )
}

/** ホーム画面での見え方。角の丸め方はホーム画面アプリごとに違うので、目安として角丸の正方形で見せる。 */
@Composable
private fun ShortcutPreview(color: Int, symbol: DisguiseSymbol, label: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = "見本：${formatHexColor(color)}の${symbol.label}、名前は$label" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(88.dp)
                .clip(LovelyShapes.sheet)
                .background(Color(color)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(symbol.drawable), contentDescription = null, tint = Color(symbolColorOn(color)), modifier = Modifier.size(36.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = LovelySpacing.screenHorizontal),
        )
    }
}

/** シンボルの選択。選んでいる色の上に描いて、仕上がりを見ながら選べるようにする。 */
@Composable
private fun SymbolGrid(selected: DisguiseSymbol, background: Int, onSelect: (DisguiseSymbol) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // 5 列にして、幅 320dp の端末でも 1 つが 48dp 以上の四角になるようにする。
    val columns = 5
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = LovelySpacing.screenHorizontal)
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DisguiseSymbol.entries.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { option ->
                    val active = option == selected
                    Surface(
                        shape = LovelyShapes.control,
                        color = Color(background),
                        border = BorderStroke(if (active) 3.dp else 1.dp, if (active) scheme.primary else scheme.outlineVariant),
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(LovelyShapes.control)
                            .selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(option) })
                            .semantics { contentDescription = option.label },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painterResource(option.drawable),
                                contentDescription = null,
                                tint = Color(symbolColorOn(background)),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** アプリ一覧の候補の 1 行。アイコンの見本と名前を出し、読み上げではラジオボタンとして読む。 */
@Composable
private fun PresetRow(preset: LauncherPreset, selected: Boolean, onClick: () -> Unit) {
    val name = stringResource(preset.label)
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = LovelySpacing.screenHorizontal, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(LovelyShapes.control)
                .background(colorResource(preset.background))
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            val symbol = preset.symbol
            if (symbol == null) {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(44.dp))
            } else {
                Icon(painterResource(symbol.drawable), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        Text(
            if (preset == LauncherPreset.DEFAULT) "$name（元のアイコン）" else name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp),
        )
        if (selected) {
            Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
