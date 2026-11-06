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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.settings.EntrySettings
import io.github.springthief1123.lovelyspace.ui.components.QuietSwitchRow
import io.github.springthief1123.lovelyspace.ui.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 入室を速くする設定。空き枠は秒単位で埋まるので、通知からの入室・ロボット確認後の送信を選べる（自動入室はピンごとに選ぶ）。 */
@Composable
fun EntrySettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val settings by app.settings.entrySettings.collectAsStateWithLifecycle(initialValue = EntrySettings())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val bottomContentPadding = 14.dp +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

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
                        description = "ピンした部屋が空いた通知を開くと、入力画面で止まらずに入室します。ロボット確認があるときは本家の入室画面を開きます。",
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
                    Text(
                        "空いた部屋に自動で入室するかは、ピンごとに知らせ方を「自動で入室」にして選びます。" +
                            "アプリで一覧などを見ている間だけ、待機メッセージがピンしたときと同じ部屋に入り、入室できたらそのピンは「空いたら知らせる」に戻ります。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
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
