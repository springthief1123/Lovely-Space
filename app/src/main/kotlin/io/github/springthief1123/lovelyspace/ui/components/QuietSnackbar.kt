package io.github.springthief1123.lovelyspace.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.ui.theme.LovelyShapes

/**
 * 画面下に出す短いお知らせ（「部屋を非表示にしました ／ 元に戻す ／ ✕」）。
 * 標準の Snackbar は文・操作・✕ の周りの余白が広く、狭い画面だと「部屋を非表示にしました」程度の文でも途中で折り返すので、
 * 色と形は標準と同じ（inverseSurface・操作文字は inversePrimary・角丸 control）のまま、横の余白だけを詰める。
 */
@Composable
fun QuietSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier) { data -> QuietSnackbar(data) }
}

@Composable
private fun QuietSnackbar(data: SnackbarData) {
    val scheme = MaterialTheme.colorScheme
    val visuals = data.visuals
    Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth(), shape = LovelyShapes.control,
        color = scheme.inverseSurface, contentColor = scheme.inverseOnSurface, shadowElevation = 6.dp) {
        Row(Modifier.heightIn(min = 48.dp).padding(start = 16.dp, end = if (visuals.withDismissAction) 2.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(visuals.message, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp))
            visuals.actionLabel?.let { label ->
                TextButton(onClick = data::performAction, contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = scheme.inversePrimary)) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (visuals.withDismissAction) IconButton(onClick = data::dismiss, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "閉じる", modifier = Modifier.size(20.dp))
            }
        }
    }
}
