@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.springthief1123.lovelyspace.ui.rooms

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.core.Genre
import io.github.springthief1123.lovelyspace.core.GenreGroup
import io.github.springthief1123.lovelyspace.core.Genres
import kotlinx.coroutines.launch

/**
 * 選択中のジャンルを示すボタンと、直前に見ていたジャンルへの近道。
 * ボタンを押すと全ジャンルをグループ別に並べたシートが開く。
 */
@Composable
fun GenreBar(
    selected: Genre,
    recent: List<Genre>,
    counts: Map<String, Int>,
    onSelect: (Genre) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(
            onClick = { sheetOpen = true },
            modifier = Modifier.semantics { contentDescription = "ジャンル: ${selected.label}。押すとジャンルを選べます" },
        ) {
            Text(genreLabel(selected, counts), fontWeight = FontWeight.SemiBold, maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            recent.forEach { genre ->
                SuggestionChip(
                    onClick = { onSelect(genre) },
                    label = { Text(genre.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
    if (sheetOpen) {
        GenreSheet(
            selected = selected,
            counts = counts,
            onSelect = {
                sheetOpen = false
                onSelect(it)
            },
            onDismiss = { sheetOpen = false },
        )
    }
}

@Composable
private fun GenreSheet(
    selected: Genre,
    counts: Map<String, Int>,
    onSelect: (Genre) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
        ) {
            Text("ジャンルを選ぶ", style = MaterialTheme.typography.titleMedium)
            GenreGroup.entries.forEach { group ->
                Text(
                    group.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Genres.all.filter { it.group == group }.forEach { genre ->
                        FilterChip(
                            selected = genre == selected,
                            onClick = {
                                // シートを閉じるアニメーションの後に切り替える。
                                scope.launch { sheetState.hide() }.invokeOnCompletion { onSelect(genre) }
                            },
                            label = { Text(genreLabel(genre, counts)) },
                        )
                    }
                }
            }
        }
    }
}

private fun genreLabel(genre: Genre, counts: Map<String, Int>): String =
    counts[genre.key]?.let { "${genre.label} $it" } ?: genre.label
