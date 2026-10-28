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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.notify.AppNotification
import io.github.springthief1123.lovelyspace.notify.NotificationKind
import io.github.springthief1123.lovelyspace.notify.NotificationSamples
import io.github.springthief1123.lovelyspace.notify.appNotificationSettingsIntent
import io.github.springthief1123.lovelyspace.notify.channelSettingsIntent
import io.github.springthief1123.lovelyspace.notify.rememberNotificationPermissionRequest
import io.github.springthief1123.lovelyspace.settings.NoticeRetention
import io.github.springthief1123.lovelyspace.settings.NotificationPreview
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import io.github.springthief1123.lovelyspace.ui.components.ProvideLovelyHazeState
import io.github.springthief1123.lovelyspace.ui.shell.PinVacancyToastStack
import io.github.springthief1123.lovelyspace.ui.shell.MAX_FLOATING_PIN_NOTICES
import io.github.springthief1123.lovelyspace.ui.components.QuietDropdownMenu
import io.github.springthief1123.lovelyspace.ui.components.QuietMenuRow
import io.github.springthief1123.lovelyspace.ui.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as LovelySpaceApp
    val preview by app.settings.notificationPreview.collectAsStateWithLifecycle(initialValue = NotificationPreview.HIDE_ON_LOCK_SCREEN)
    val retention by app.settings.noticeRetention.collectAsStateWithLifecycle(initialValue = NoticeRetention.defaults)
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
    val previewHazeState = remember { HazeState() }
    var previewCount by remember { mutableIntStateOf(0) }
    var floatingPreviews by remember { mutableStateOf(emptyList<AppNotification>()) }
    var previewExpanded by remember { mutableStateOf(false) }
    fun showFloatingPreview() {
        previewCount++
        floatingPreviews = (listOf(NotificationSamples.floatingPreview(previewCount)) + floatingPreviews)
            .take(MAX_FLOATING_PIN_NOTICES)
    }

    fun show(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    fun sendTest(kind: NotificationKind) {
        scope.launch {
            try {
                val sample = NotificationSamples.of(kind)
                app.notifier.post(
                    sample,
                    timeoutAfterMs = if (kind == NotificationKind.ONGOING) ONGOING_TEST_TIMEOUT_MS else null,
                )
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
        // 設定画面の本体だけを Haze の取得元とし、重ねたサンプル通知から背景をぼかす。
        Box(Modifier.fillMaxSize().hazeSource(state = previewHazeState)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            SettingsPageHeader(title = "通知", onBack = onBack)
            LazyColumn(contentPadding = PaddingValues(top = 14.dp, bottom = bottomContentPadding)) {
                item { SettingsSectionTitle("端末の通知") }
                item {
                    SettingsChoiceRow(
                        title = if (canPost) "オン" else "オフ",
                        description = if (canPost) "端末の設定から変更できます" else "タップして通知を許可する",
                        selected = canPost,
                        radio = false,
                        // 許可済みなら、アプリの通知をまとめてオフにできるよう端末の設定を開く。
                        onClick = { if (canPost) context.startActivity(appNotificationSettingsIntent(context)) else requestPermission() },
                    )
                }
                item { SettingsDivider() }
                item { SettingsSectionTitle("通知に出す内容") }
                item {
                    SettingsChoiceGroup {
                        NotificationPreview.entries.forEach { option ->
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
                    }
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
                item { FloatingPreviewTestRow(onSendTest = ::showFloatingPreview) }
                item {
                    Text(
                        "種類ごとの音やバイブは、行をタップして端末の設定で変えられます。" +
                            "ピンの空きと条件の新着は、アプリを開いている間も閉じている間も届きます。名前で待つ人の部屋は、アプリを開いている間に届きます。" +
                            "会話の画面では、条件と名前で待つの通知を出さずにお知らせにだけ残します。" +
                            "入室者ありの通知はまだ届きません（準備中）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
                item { SettingsDivider() }
                item { SettingsSectionTitle("お知らせを残す時間") }
                item {
                    Text("過ぎたお知らせは、ベルのお知らせからも端末の通知からも自動で消えます。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp))
                }
                items(NoticeRetention.kinds.filter { it.delivered }) { kind ->
                    RetentionRow(kind, retention[kind] ?: NoticeRetention.default(kind)) { choice ->
                        scope.launch {
                            try {
                                app.settings.setNoticeRetention(kind, choice)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                show("設定の保存に失敗しました：${describeError(e)}")
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        }
        // 端末通知・お知らせ履歴に一切触れない画面内プレビュー。
        // 実際のフローティング通知と同じスタック・フリック・Glassを使う。
        ProvideLovelyHazeState(previewHazeState) {
            if (floatingPreviews.isNotEmpty()) {
                PinVacancyToastStack(
                    notices = floatingPreviews,
                    pins = emptyList(),
                    expanded = previewExpanded,
                    onExpandedChange = { previewExpanded = it },
                    onEnter = { show("サンプル表示です。実際の入室は行いません") },
                    onReview = { show("サンプル表示です。実際の部屋は開きません") },
                    onDismiss = { notice ->
                        floatingPreviews = floatingPreviews - notice
                        if (floatingPreviews.size <= 1) previewExpanded = false
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
                    preview = true,
                )
            }
        }
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
                listOfNotNull("オフ".takeIf { !enabled }, kind.description, "準備中".takeIf { !kind.delivered }).joinToString("・"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onSendTest) { Text("テスト") }
    }
}


/**
 * 実機の通知権限に関係なく、その場で外観を検証するためのテスト。
 * 続けて押すと5件まで重なり、展開・スワイプも試せる。
 */
@Composable
internal fun FloatingPreviewTestRow(onSendTest: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("フローティング通知", style = MaterialTheme.typography.bodyLarge)
            Text("画面内にサンプルを表示。連続タップで最大5件スタックします",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onSendTest, modifier = Modifier.semantics { contentDescription = "フローティング通知をテスト" }) {
            Text("テスト")
        }
    }
}

/** 種類ごとのお知らせを残す時間。右の選択ボタンから選ぶ（更新の間隔の設定と同じ形）。 */
@Composable
private fun RetentionRow(kind: NotificationKind, value: NoticeRetention, onSelect: (NoticeRetention) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(kind.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.semantics { contentDescription = "${kind.label}のお知らせを残す時間：${value.label}" },
            ) {
                Text(value.label)
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, modifier = Modifier.padding(start = 6.dp))
            }
            QuietDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                NoticeRetention.entries.forEach { choice ->
                    QuietMenuRow(
                        choice.label,
                        supporting = if (choice == NoticeRetention.default(kind)) "既定" else null,
                        selected = choice == value,
                        onClick = {
                            expanded = false
                            onSelect(choice)
                        },
                    )
                }
            }
        }
    }
}

private const val ONGOING_TEST_TIMEOUT_MS = 15_000L
