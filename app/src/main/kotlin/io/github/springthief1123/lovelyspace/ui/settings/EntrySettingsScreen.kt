package io.github.springthief1123.lovelyspace.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.data.AutoEntryController
import io.github.springthief1123.lovelyspace.settings.EntrySettings
import io.github.springthief1123.lovelyspace.ui.components.QuietSwitchRow
import io.github.springthief1123.lovelyspace.ui.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 入室を速くする設定。空き枠は秒単位で埋まるので、通知からの入室・ロボット確認後の送信・順番待ちの自動入室を選べる。 */
@Composable
fun EntrySettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val settings by app.settings.entrySettings.collectAsStateWithLifecycle(initialValue = EntrySettings())
    val attempts by app.autoEntry.attempts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val bottomContentPadding = 14.dp +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // 自動入室の残り時間を出すため、開いている間だけ時計を進める。
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    val autoActive = AutoEntryController.active(settings.autoEntryStartedAt, now)

    fun save(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar("設定の保存に失敗しました：${describeError(e)}")
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            SettingsPageHeader(title = "入室", onBack = onBack)
            LazyColumn(contentPadding = PaddingValues(top = 14.dp, bottom = bottomContentPadding)) {
                item {
                    Text(
                        "名前・性別・年齢は既定のプロフィール（無ければ前回の入力）を使います。どちらも無いときは、自動では入室しません。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
                    )
                }
                item {
                    QuietSwitchRow(
                        checked = settings.fromNotification,
                        onCheckedChange = { enabled -> save { app.settings.setEntryFromNotification(enabled) } },
                        title = "通知から開いたらすぐ入室する",
                        description = "順番待ちの部屋が空いた通知を開くと、入力画面で止まらずに入室します。ロボット確認があるときは本家の入室画面を開きます。",
                    )
                }
                item {
                    QuietSwitchRow(
                        checked = settings.autoSubmit,
                        onCheckedChange = { enabled -> save { app.settings.setEntryAutoSubmit(enabled) } },
                        title = "ロボット確認が通ったら自動で送信する",
                        description = "本家の入室画面で「入室」が押せるようになったら、アプリが押します。確認そのものは本家の画面で行います。",
                    )
                }
                item {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
                item {
                    QuietSwitchRow(
                        checked = autoActive,
                        onCheckedChange = { enabled ->
                            save {
                                app.autoEntry.reset()
                                app.settings.setAutoEntryStartedAt(if (enabled) System.currentTimeMillis() else 0L)
                                now = System.currentTimeMillis()
                            }
                        },
                        title = "順番待ちの部屋が空いたら自動で入室する",
                        description = autoEntryDescription(autoActive, settings.autoEntryStartedAt, now, attempts),
                    )
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
}

/** 自動入室の説明。動いている間は残り時間と回数を出す。 */
internal fun autoEntryDescription(active: Boolean, startedAt: Long, now: Long, attempts: Int): String {
    val rules = "アプリを開いて一覧などを見ている間だけ動きます。待機メッセージが登録時と同じ部屋に限り、" +
        "${AutoEntryController.DURATION_MS / 60_000}分たつか${AutoEntryController.MAX_ATTEMPTS}回入室すると止まります。"
    if (!active) return rules
    val left = ((startedAt + AutoEntryController.DURATION_MS - now + 59_999) / 60_000).coerceAtLeast(1)
    val status = if (attempts >= AutoEntryController.MAX_ATTEMPTS) "回数の上限に達したので止まっています。もう一度オンにすると再開します。"
    else "あと${left}分（${attempts}/${AutoEntryController.MAX_ATTEMPTS}回）。"
    return "$status$rules"
}
