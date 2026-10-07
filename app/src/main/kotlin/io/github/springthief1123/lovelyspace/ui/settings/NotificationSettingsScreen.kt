package io.github.springthief1123.lovelyspace.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.notify.NotificationKind
import io.github.springthief1123.lovelyspace.notify.NotificationSamples
import io.github.springthief1123.lovelyspace.notify.channelSettingsIntent
import io.github.springthief1123.lovelyspace.notify.rememberNotificationPermissionRequest
import io.github.springthief1123.lovelyspace.settings.NotificationPreview
import io.github.springthief1123.lovelyspace.ui.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as LovelySpaceApp
    val preview by app.settings.notificationPreview.collectAsStateWithLifecycle(initialValue = NotificationPreview.HIDE_ON_LOCK_SCREEN)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // 権限やチャンネルは端末の設定画面で変えられるので、この画面に戻るたびに読み直す。
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val canPost = remember(refresh) { app.notifier.canPost() }
    val enabledKinds = remember(refresh) { NotificationKind.entries.filter { app.notifier.channelEnabled(it) }.toSet() }
    val requestPermission = rememberNotificationPermissionRequest { refresh++ }
    val bottomContentPadding = 14.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    fun show(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    fun sendTest(kind: NotificationKind) {
        scope.launch {
            try {
                app.notifier.post(NotificationSamples.of(kind))
                when {
                    !canPost -> show("通知が許可されていないため、お知らせにだけ追加しました")
                    kind !in enabledKinds -> show("「${kind.label}」の通知は端末の設定でオフになっています")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                show("テスト通知を送れませんでした：${describeError(e)}")
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            SettingsPageHeader(title = "通知", onBack = onBack)
            LazyColumn(contentPadding = PaddingValues(top = 14.dp, bottom = bottomContentPadding)) {
                item { SettingsSectionTitle("端末の通知") }
                item {
                    SettingsChoiceRow(
                        title = if (canPost) "オン" else "オフ",
                        description = if (canPost) "端末の設定から変更できます" else "タップして通知を許可する",
                        selected = canPost,
                        onClick = requestPermission,
                    )
                }
                item { SettingsDivider() }
                item { SettingsSectionTitle("通知に出す内容") }
                items(NotificationPreview.entries) { option ->
                    SettingsChoiceRow(
                        title = option.label,
                        description = option.description,
                        selected = option == preview,
                        onClick = {
                            scope.launch {
                                try {
                                    app.settings.setNotificationPreview(option)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    show("設定の保存に失敗しました：${describeError(e)}")
                                }
                            }
                        },
                    )
                }
                item { SettingsDivider() }
                item { SettingsSectionTitle("通知の種類") }
                items(NotificationKind.entries) { kind ->
                    NotificationKindRow(
                        kind = kind,
                        enabled = kind in enabledKinds,
                        onOpenSettings = { context.startActivity(channelSettingsIntent(context, kind)) },
                        onSendTest = { sendTest(kind) },
                    )
                }
                item {
                    Text(
                        "種類ごとの音やバイブは、行をタップして端末の設定で変えられます。" +
                            "巡回の一致・入室者あり・順番待ちの通知は、背景での確認機能と合わせて順に届くようになります。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 10.dp))
}

@Composable
private fun NotificationKindRow(kind: NotificationKind, enabled: Boolean, onOpenSettings: () -> Unit, onSendTest: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpenSettings).padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(kind.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (enabled) kind.description else "オフ・${kind.description}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onSendTest) { Text("テスト") }
    }
}
