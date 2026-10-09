@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.springthief1123.lovelyspace.ui.rooms

import io.github.springthief1123.lovelyspace.ui.components.QuietSheetHeader
import io.github.springthief1123.lovelyspace.ui.components.QuietSheet
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.core.Genre
import io.github.springthief1123.lovelyspace.core.GenreGroup
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpacing
import kotlinx.coroutines.launch

@Composable
fun GenreBar(
    selected: Genre,
    recent: List<Genre>,
    counts: Map<String, Int>,
    onSelect: (Genre) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .clickable { sheetOpen = true }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selected.label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "ジャンルを選ぶ")
            counts[selected.key]?.let {
                Text(
                    "$it rooms",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        if (recent.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                recent.forEach { genre ->
                    Text(
                        genre.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (genre == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { onSelect(genre) }
                            .padding(vertical = 4.dp),
                    )
                }
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
    QuietSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LovelySpacing.screenHorizontal)
                .padding(top = 8.dp, bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            QuietSheetHeader("ジャンルを選ぶ")
            GenreGroup.entries.forEach { group ->
                Text(
                    group.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Genres.all.filter { it.group == group }.forEach { genre ->
                        val active = genre == selected
                        Text(
                            genreLabel(genre, counts),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                    else MaterialTheme.colorScheme.surfaceContainer,
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable {
                                    scope.launch { sheetState.hide() }.invokeOnCompletion { onSelect(genre) }
                                }
                                .padding(horizontal = 11.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun genreLabel(genre: Genre, counts: Map<String, Int>): String =
    counts[genre.key]?.let { "${genre.label} $it" } ?: genre.label
