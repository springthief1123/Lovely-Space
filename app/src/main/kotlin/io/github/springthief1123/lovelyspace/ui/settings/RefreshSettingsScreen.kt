package io.github.springthief1123.lovelyspace.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.core.RefreshPacing
import io.github.springthief1123.lovelyspace.ui.components.QuietDropdownMenu
import io.github.springthief1123.lovelyspace.ui.components.QuietMenuRow
import io.github.springthief1123.lovelyspace.ui.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 本家への自動の取り直しの間隔を選ぶ。短くするほど本家への通信が増えるので、下限は [RefreshPacing] が決める。 */
@Composable
fun RefreshSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val pacing by app.pacing.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val bottomContentPadding = 14.dp +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    /** 保存済みの値に対して、選んだ項目だけを変える。 */
    fun save(change: (RefreshPacing) -> RefreshPacing) {
        scope.launch {
            try {
                app.settings.updateRefreshPacing(change)
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
            SettingsPageHeader(title = "更新の間隔", onBack = onBack)
            LazyColumn(contentPadding = PaddingValues(top = 14.dp, bottom = bottomContentPadding)) {
                item {
                    Text(
                        "短くするほど早く気付けますが、本家への通信が増えます。1秒に何度も読み込むと、本家から一時的にアクセスを止められることがあります。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
                    )
                }
                item {
                    IntervalPicker(
                        title = "見つける画面の更新",
                        description = "見つける画面で、一覧を1ページ目から順に読み直す1周の間隔（ページの間は通信の最小間隔）",
                        value = pacing.searchHeadMs,
                        choices = RefreshPacing.LIST_CHOICES,
                        defaultValue = RefreshPacing.DEFAULT_SEARCH_HEAD_MS,
                        onSelect = { value -> save { it.copy(searchHeadMs = value) } },
                    )
                }
                item {
                    IntervalPicker(
                        title = "レーダーの自動巡回",
                        description = "レーダー画面を開いている間に、一覧を1ページ目から順に読み直す1周の間隔",
                        value = pacing.radarHeadMs,
                        choices = RefreshPacing.LIST_CHOICES,
                        defaultValue = RefreshPacing.DEFAULT_RADAR_HEAD_MS,
                        onSelect = { value -> save { it.copy(radarHeadMs = value) } },
                    )
                }
                item {
                    IntervalPicker(
                        title = "空き枠の確認",
                        description = "順番待ちの部屋が空いたかを、アプリを開いている間に確かめる間隔（閉じている間は15分ごと）",
                        value = pacing.waitlistMs,
                        choices = RefreshPacing.WAITLIST_CHOICES,
                        defaultValue = RefreshPacing.DEFAULT_WAITLIST_MS,
                        onSelect = { value -> save { it.copy(waitlistMs = value) } },
                    )
                }
                item {
                    IntervalPicker(
                        title = "覗いている部屋の新しい発言",
                        description = "公開ルームを読み直す間隔",
                        value = pacing.publicRoomMs,
                        choices = RefreshPacing.PUBLIC_ROOM_CHOICES,
                        defaultValue = RefreshPacing.DEFAULT_PUBLIC_ROOM_MS,
                        onSelect = { value -> save { it.copy(publicRoomMs = value) } },
                    )
                }
                item {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
                item {
                    IntervalPicker(
                        title = "通信の最小間隔",
                        description = "どの機能の通信も、前の通信からこの時間は空けます。上の間隔をこれより短くしても、この間隔より速くはなりません。",
                        value = pacing.minIntervalMs,
                        choices = RefreshPacing.MIN_INTERVAL_CHOICES,
                        defaultValue = RefreshPacing.DEFAULT_MIN_INTERVAL_MS,
                        onSelect = { value -> save { it.copy(minIntervalMs = value) } },
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

@Composable
private fun IntervalPicker(
    title: String,
    description: String,
    value: Long,
    choices: List<Long>,
    defaultValue: Long,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.semantics { contentDescription = "$title：${intervalLabel(value)}" },
            ) {
                Text(intervalLabel(value))
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            QuietDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                // 保存済みの値が選択肢に無い（下限に揃えた古い値など）ときも、今の値を選べる形で見せる。
                (choices + value).distinct().sorted().forEach { choice ->
                    QuietMenuRow(
                        intervalLabel(choice),
                        supporting = if (choice == defaultValue) "既定" else null,
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

/** 1500 → 「1.5秒」、4000 → 「4秒」。 */
internal fun intervalLabel(ms: Long): String =
    if (ms % 1_000 == 0L) "${ms / 1_000}秒" else "${ms / 1_000}.${(ms % 1_000) / 100}秒"
