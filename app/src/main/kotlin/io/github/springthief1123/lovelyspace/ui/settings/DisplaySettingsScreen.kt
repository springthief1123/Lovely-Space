package io.github.springthief1123.lovelyspace.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.settings.RoomMessageLines
import io.github.springthief1123.lovelyspace.settings.TextScale
import io.github.springthief1123.lovelyspace.settings.ThemeMode
import io.github.springthief1123.lovelyspace.ui.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DisplaySettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val themeMode by app.settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val textScale by app.settings.textScale.collectAsStateWithLifecycle(initialValue = TextScale.STANDARD)
    val messageLines by app.settings.roomMessageLines.collectAsStateWithLifecycle(initialValue = RoomMessageLines.FOUR)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val bottomContentPadding = 14.dp +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    fun saveSetting(block: suspend () -> Unit) {
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
            SettingsPageHeader(title = "表示設定", onBack = onBack)
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 0.dp,
                    top = 14.dp,
                    end = 0.dp,
                    bottom = bottomContentPadding,
                ),
            ) {
            item { SettingsSectionTitle("テーマ") }
            item {
                SettingsChoiceGroup {
                    ThemeMode.entries.forEach { mode ->
                        SettingsChoiceRow(
                            title = mode.label,
                            selected = mode == themeMode,
                            onClick = { saveSetting { app.settings.setThemeMode(mode) } },
                        )
                    }
                }
            }
            item {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
            item { SettingsSectionTitle("テキストサイズ") }
            item {
                SettingsChoiceGroup {
                    TextScale.entries.forEach { scale ->
                        SettingsChoiceRow(
                            title = scale.label,
                            description = when (scale) {
                                TextScale.COMPACT -> "一覧を少しコンパクトに表示"
                                TextScale.STANDARD -> "標準の読みやすさ"
                                TextScale.LARGE -> "文字を少し大きく表示"
                            },
                            selected = scale == textScale,
                            onClick = { saveSetting { app.settings.setTextScale(scale) } },
                        )
                    }
                }
            }
            item {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
            item { SettingsSectionTitle("部屋カードの待機メッセージ") }
            item {
                SettingsChoiceGroup {
                    RoomMessageLines.entries.forEach { lines ->
                        SettingsChoiceRow(
                            title = lines.label,
                            description = when (lines) {
                                RoomMessageLines.TWO -> "一覧に多くの部屋を並べる"
                                RoomMessageLines.THREE -> null
                                RoomMessageLines.FOUR -> "標準"
                                RoomMessageLines.ALL -> "省略せずに全文を表示"
                            },
                            selected = lines == messageLines,
                            onClick = { saveSetting { app.settings.setRoomMessageLines(lines) } },
                        )
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
}

@Composable
internal fun SettingsSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 6.dp),
    )
}

/** 1 つだけ選ぶ設定の行をまとめる。読み上げでは「n 個中 m 個目」と伝わる。 */
@Composable
internal fun SettingsChoiceGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup(), content = content)
}

/**
 * 設定の選択肢の 1 行。[SettingsChoiceGroup] の中に並べ、読み上げではラジオボタンとして選択状態を伝える。
 * [radio] が false の行は選択肢ではなく、押すと何かを開くボタンとして読む（✓ は状態の目印だけ）。
 */
@Composable
internal fun SettingsChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    description: String? = null,
    radio: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (radio) Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                else Modifier.clickable(role = Role.Button, onClick = onClick),
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
