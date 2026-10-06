package io.github.springthief1123.lovelyspace.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.settings.TextScale
import io.github.springthief1123.lovelyspace.settings.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun DisplaySettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val themeMode by app.settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val textScale by app.settings.textScale.collectAsStateWithLifecycle(initialValue = TextScale.STANDARD)
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SettingsPageHeader(title = "表示設定", onBack = onBack)
        LazyColumn(
            contentPadding = PaddingValues(vertical = 14.dp),
        ) {
            item { SettingsSectionTitle("テーマ") }
            items(ThemeMode.entries.size) { index ->
                val mode = ThemeMode.entries[index]
                SettingsChoiceRow(
                    title = mode.label,
                    selected = mode == themeMode,
                    onClick = { scope.launch { app.settings.setThemeMode(mode) } },
                )
            }
            item {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
            item { SettingsSectionTitle("テキストサイズ") }
            items(TextScale.entries.size) { index ->
                val scale = TextScale.entries[index]
                SettingsChoiceRow(
                    title = scale.label,
                    description = when (scale) {
                        TextScale.COMPACT -> "一覧を少しコンパクトに表示"
                        TextScale.STANDARD -> "標準の読みやすさ"
                        TextScale.LARGE -> "文字を少し大きく表示"
                    },
                    selected = scale == textScale,
                    onClick = { scope.launch { app.settings.setTextScale(scale) } },
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingsChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    description: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
