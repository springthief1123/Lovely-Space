package io.github.springthief1123.lovelyspace.lock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.ui.components.QuietSwitchRow
import io.github.springthief1123.lovelyspace.ui.settings.SettingsChoiceGroup
import io.github.springthief1123.lovelyspace.ui.settings.SettingsChoiceRow
import io.github.springthief1123.lovelyspace.ui.settings.SettingsPageHeader
import io.github.springthief1123.lovelyspace.ui.settings.SettingsSectionTitle

/**
 * 設定の「アプリロック」。ロックの有無、解除の方法（パスコード・パターン）、生体認証、ロックまでの時間を選ぶ。
 * 方法を選ぶと、同じものを 2 回入力して登録する。
 */
@Composable
fun AppLockSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val controller = app.appLock
    val state by controller.state.collectAsStateWithLifecycle()
    val config = state.config
    val context = LocalContext.current
    val biometricReady = remember(context) { biometricAvailable(context) }
    var setup by remember { mutableStateOf<LockMethod?>(null) }
    // ロックを切る・方法を変える前に、いまの解除方法で本人か確かめる。null は「ロックを切る」。
    var verifying by remember { mutableStateOf<LockChange?>(null) }

    verifying?.let { change ->
        LockVerify(
            title = if (change.method == null) "ロックをオフにする" else "${change.method.label}に変更",
            state = state,
            check = controller::verify,
            verifyBiometric = if (config.biometric) controller::verifyWithBiometric else null,
            onVerified = {
                verifying = null
                if (change.method == null) controller.disable() else setup = change.method
            },
            onCancel = { verifying = null },
        )
        return
    }
    setup?.let { method ->
        LockSetup(method, onDone = { secret -> controller.enable(method, secret); setup = null }, onCancel = { setup = null })
        return
    }

    val bottom = 14.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SettingsPageHeader(title = "アプリロック", onBack = onBack)
        LazyColumn(contentPadding = PaddingValues(top = 14.dp, bottom = bottom)) {
            item {
                QuietSwitchRow(
                    checked = config.enabled,
                    onCheckedChange = { on -> if (on) setup = LockMethod.PASSCODE else verifying = LockChange(null) },
                    title = "アプリを開くときにロックする",
                    description = "背景の巡回・順番待ちの通知はロック中も届きます。通知から開いたときもロックを先に解きます。",
                )
            }
            if (config.enabled) {
                item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 10.dp)) }
                item { SettingsSectionTitle("解除の方法") }
                item {
                    SettingsChoiceGroup {
                        LockMethod.entries.forEach { method ->
                            SettingsChoiceRow(
                                title = method.label,
                                description = when (method) {
                                    LockMethod.PASSCODE -> if (config.method == method) "${config.passcodeLength}桁の数字。押すと変更します" else "4〜8桁の数字"
                                    LockMethod.PATTERN -> if (config.method == method) "押すと変更します" else "4つ以上の点をなぞる"
                                },
                                selected = config.method == method,
                                onClick = { verifying = LockChange(method) },
                            )
                        }
                    }
                }
                item {
                    QuietSwitchRow(
                        checked = config.biometric && biometricReady,
                        onCheckedChange = controller::setBiometric,
                        title = "生体認証でも解除する",
                        description = if (biometricReady) "指紋・顔で解除します。使えないときはパスコード・パターンで解除できます。"
                            else "この端末では使えません（指紋・顔が登録されていない場合を含む）。",
                        enabled = biometricReady,
                    )
                }
                item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 10.dp)) }
                item { SettingsSectionTitle("ロックするまでの時間") }
                item {
                    SettingsChoiceGroup {
                        LockDelay.entries.forEach { delay ->
                            SettingsChoiceRow(
                                title = delay.label,
                                description = if (delay == LockDelay.IMMEDIATE) "アプリを離れたらすぐロック" else "アプリを離れてから${delay.label.removeSuffix("後")}たったらロック",
                                selected = config.delay == delay,
                                onClick = { controller.setDelay(delay) },
                            )
                        }
                    }
                }
                item {
                    Text("パスコード・パターンを忘れたときは、ロック画面の「忘れた場合」からアプリのデータを消して作り直します。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
                }
            }
        }
    }
}

/** 確かめたあとに行う変更。[method] が null ならロックを切り、そうでなければその方法を登録し直す。 */
private data class LockChange(val method: LockMethod?)

/** 設定を変える前の確認。ロック画面と同じ入力欄で、いまの解除方法を入力してもらう。 */
@Composable
private fun LockVerify(
    title: String,
    state: AppLockState,
    check: (String) -> Boolean,
    verifyBiometric: (() -> Boolean)?,
    onVerified: () -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SettingsPageHeader(title = title, onBack = onCancel)
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("続けるには、いまの解除方法で確認してください。", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            LockChallenge(state, check = check, onSuccess = onVerified,
                onBiometricSuccess = verifyBiometric?.let { verify -> { if (verify()) onVerified() } })
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onCancel) { Text("やめる") }
        }
    }
}

/** パスコード・パターンを 2 回入力して登録する。 */
@Composable
private fun LockSetup(method: LockMethod, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var entered by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onCancel)

    fun submit(secret: String) {
        val previous = first
        when {
            previous == null -> { first = secret; message = null }
            previous == secret -> onDone(secret)
            else -> { first = null; message = "一致しませんでした。最初からやり直してください" }
        }
        entered = ""
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SettingsPageHeader(title = "${method.label}の登録", onBack = onCancel)
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                message ?: when {
                    method == LockMethod.PASSCODE && first == null -> "4〜8桁の数字を入力"
                    method == LockMethod.PASSCODE -> "確認のため、もう一度入力"
                    first == null -> "4つ以上の点をなぞる"
                    else -> "確認のため、もう一度なぞる"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (message != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            when (method) {
                LockMethod.PASSCODE -> {
                    PasscodeDots(entered.length, first?.length ?: 0)
                    Spacer(Modifier.height(28.dp))
                    PasscodeKeypad(
                        onDigit = { digit ->
                            val limit = first?.length ?: MAX_PASSCODE_LENGTH
                            if (entered.length < limit) {
                                entered += digit
                                // 2 回目は 1 回目と同じ桁数になったら確かめる。
                                if (first != null && entered.length == limit) submit(entered)
                            }
                        },
                        onDelete = { entered = entered.dropLast(1) },
                        onBiometric = null,
                    )
                    if (first == null) {
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = { submit(entered) }, enabled = entered.length >= MIN_PASSCODE_LENGTH) { Text("次へ") }
                    }
                }
                LockMethod.PATTERN -> PatternPad(onComplete = { dots ->
                    if (dots.size < MIN_PATTERN_DOTS) message = "4つ以上の点をつないでください"
                    else submit(patternSecret(dots))
                }, error = message != null)
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onCancel) { Text("やめる") }
        }
    }
}
