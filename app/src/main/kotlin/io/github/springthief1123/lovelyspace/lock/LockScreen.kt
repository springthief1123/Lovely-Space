package io.github.springthief1123.lovelyspace.lock

import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** この端末で生体認証（指紋・顔）が使えるか。登録が無いときも使えないとみなす。 */
fun biometricAvailable(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

internal fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}

/** 端末の生体認証の画面を出す。取り消したときや失敗が続いたときは何もしない（パスコード・パターンで解ける）。 */
internal fun showBiometricPrompt(activity: FragmentActivity, fallbackLabel: String, onSuccess: () -> Unit) {
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
    })
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("ロックを解除")
        .setSubtitle("Lovely Space")
        .setNegativeButtonText(fallbackLabel)
        .setAllowedAuthenticators(BIOMETRIC_WEAK)
        .build()
    prompt.authenticate(info)
}

/**
 * アプリの上にかぶせるロック画面。後ろの画面は見せず、触れないようにする。
 * 戻るボタンではアプリを背景に回すだけで、ロックは解かない。
 */
@Composable
fun LockScreen(controller: AppLockController, state: AppLockState) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    var forgotStep by remember { mutableIntStateOf(0) }
    BackHandler { activity?.moveTaskToBack(true) }
    // キー入力はロック画面で受け、後ろの画面へ届かないようにする。
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 後ろの画面に触れさせない。
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .focusRequester(focus)
            .focusable()
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(10.dp))
        Text("Lovely Space", style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif))
        Spacer(Modifier.height(6.dp))
        LockChallenge(state, check = controller::unlock, onSuccess = {},
            onBiometricSuccess = if (state.config.biometric) controller::unlockWithBiometric else null)
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = { forgotStep = 1 }) { Text("忘れた場合") }
    }

    // データを全部消すのは取り消せないので、説明と最終確認の 2 段階にする。
    when (forgotStep) {
        1 -> AlertDialog(
            onDismissRequest = { forgotStep = 0 },
            title = { Text("ロックを解除できないとき") },
            text = { Text("パスコード・パターンは端末内にハッシュで保存しているため、取り出せません。アプリのデータをすべて消すと、ロックも消えて最初の状態から使えます。保存した部屋・条件・プリセット・レーダーの設定もすべて消えます。") },
            confirmButton = { TextButton(onClick = { forgotStep = 2 }) { Text("データの消去へ進む", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { forgotStep = 0 }) { Text("戻る") } },
        )
        2 -> ClearDataDialog(
            onConfirm = {
                forgotStep = 0
                context.getSystemService(ActivityManager::class.java)?.clearApplicationUserData()
            },
            onDismiss = { forgotStep = 0 },
        )
    }
}

/** データ消去の最終確認。確認の言葉を入力したときだけ消せる。 */
@Composable
private fun ClearDataDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("すべてのデータを消しますか？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("消したデータは元に戻せません。続けるには「$CLEAR_DATA_WORD」と入力してください。")
                OutlinedTextField(typed, { typed = it }, singleLine = true, label = { Text("確認の言葉") })
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = typed.trim() == CLEAR_DATA_WORD) {
                Text("データを消す", color = if (typed.trim() == CLEAR_DATA_WORD) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

/**
 * いまの解除方法（パスコード・パターン・生体認証）の入力欄。ロック画面と、設定を変える前の確認で使う。
 * [check] は入力を確かめ、合っていれば true（間違いの回数と待ち時間は [AppLockController] が数える）。
 * 合っていたら [onSuccess] を呼ぶ。[onBiometricSuccess] が null なら生体認証は出さない。
 */
@Composable
internal fun LockChallenge(
    state: AppLockState,
    check: (String) -> Boolean,
    onSuccess: () -> Unit,
    onBiometricSuccess: (() -> Unit)?,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val scope = rememberCoroutineScope()
    val config = state.config
    val method = config.method ?: return
    val canUseBiometric = onBiometricSuccess != null && activity != null && remember(context) { biometricAvailable(context) }
    var entered by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lockedOut = now < state.lockedOutUntil
    LaunchedEffect(state.lockedOutUntil) {
        while (System.currentTimeMillis() < state.lockedOutUntil) { now = System.currentTimeMillis(); delay(500) }
        now = System.currentTimeMillis()
    }
    val fallbackLabel = if (method == LockMethod.PASSCODE) "パスコードで解除" else "パターンで解除"
    val biometric: (() -> Unit)? = if (canUseBiometric) ({ showBiometricPrompt(activity!!, fallbackLabel) { onBiometricSuccess!!() } }) else null
    // 入力欄が出たら、まず生体認証を出す。
    LaunchedEffect(Unit) { biometric?.invoke() }

    fun attempt(secret: String) {
        if (checking) return
        checking = true
        scope.launch {
            val ok = withContext(Dispatchers.Default) { check(secret) }
            checking = false
            entered = ""
            error = if (ok) null else if (method == LockMethod.PASSCODE) "パスコードが違います" else "パターンが違います"
            if (ok) onSuccess()
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when {
                lockedOut -> "続けて間違えたため、${(state.lockedOutUntil - now + 999) / 1000}秒お待ちください"
                error != null -> error!!
                method == LockMethod.PASSCODE -> "パスコードを入力"
                else -> "パターンをなぞる"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (error != null || lockedOut) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        when (method) {
            LockMethod.PASSCODE -> {
                PasscodeDots(entered.length, config.passcodeLength)
                Spacer(Modifier.height(28.dp))
                PasscodeKeypad(
                    enabled = !lockedOut && !checking,
                    onDigit = { digit ->
                        if (entered.length < MAX_PASSCODE_LENGTH) {
                            entered += digit
                            error = null
                            if (entered.length == config.passcodeLength) attempt(entered)
                        }
                    },
                    onDelete = { entered = entered.dropLast(1) },
                    onBiometric = biometric,
                )
            }
            LockMethod.PATTERN -> {
                PatternPad(onComplete = { error = null; attempt(patternSecret(it)) }, enabled = !lockedOut && !checking, error = error != null)
                if (biometric != null) {
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = biometric) {
                        Icon(Icons.Outlined.Fingerprint, null)
                        Text("生体認証で解除", Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

/** データ消去の最終確認で入力してもらう言葉。 */
internal const val CLEAR_DATA_WORD = "消去"

internal const val MIN_PASSCODE_LENGTH = 4
internal const val MAX_PASSCODE_LENGTH = 8
internal const val MIN_PATTERN_DOTS = 4
