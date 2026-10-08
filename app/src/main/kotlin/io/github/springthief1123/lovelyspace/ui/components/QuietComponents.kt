package io.github.springthief1123.lovelyspace.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.text.style.TextOverflow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
// onBack を最後に置き、既存の呼び出し（QuietTopBar(title) { 戻る }）をそのまま使えるようにする。
fun QuietTopBar(title: String, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る") }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
fun QuietHeading(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium))
}

@Composable
fun QuietPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** 項目ごとの副次的な操作。ボタンを並べず「︙」にまとめて、項目の高さを抑える。 */
data class QuietMenuItem(val label: String, val enabled: Boolean = true, val destructive: Boolean = false, val onClick: () -> Unit)

@Composable
fun QuietOverflowMenu(items: List<QuietMenuItem>, contentDescription: String = "その他の操作") {
    if (items.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = contentDescription)
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            items.forEach { item ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(item.label, color = if (item.destructive) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
                    },
                    enabled = item.enabled,
                    onClick = { open = false; item.onClick() },
                )
            }
        }
    }
}

/** 一覧の区切り見出し。右端に「追加」などの操作を1つだけ置ける。 */
@Composable
fun QuietSectionHeader(title: String, supporting: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        action?.invoke()
    }
}

/** 画面内の切り替え。横に収まらないときは横スクロールする。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> QuietTabs(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            item {
                androidx.compose.material3.FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
            }
        }
    }
}

/** 一覧の行として使う小さなパネル。内側の余白を QuietPanel より詰める。 */
@Composable
fun QuietListPanel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface,
        shape = shape, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(
            (if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp), content = content,
        )
    }
}
