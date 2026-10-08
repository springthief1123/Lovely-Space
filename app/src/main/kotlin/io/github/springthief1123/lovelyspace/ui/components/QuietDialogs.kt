package io.github.springthief1123.lovelyspace.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.ui.theme.LovelyShapes
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpacing

/**
 * 確認・入力のダイアログ。面は surfaceContainerHigh、角丸は [LovelyShapes.sheet]、見出しは titleMedium で、
 * アプリの文字サイズ設定に合わせて大きくなる（標準の見出し headlineSmall は設定の外にある）。
 * [destructive] は削除・退室など取り消せない確定で、確定ボタンの文字を error の色にする。
 * [error] は保存の失敗などを本文の下に出す。[dismissLabel] が null なら確定ボタンだけを出す。
 */
@Composable
fun QuietDialog(
    title: String,
    onDismissRequest: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    dismissLabel: String? = "キャンセル",
    onDismiss: () -> Unit = onDismissRequest,
    dismissEnabled: Boolean = true,
    error: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = confirmEnabled,
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = scheme.error) else ButtonDefaults.textButtonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = dismissLabel?.let { label ->
            { TextButton(onClick = onDismiss, enabled = dismissEnabled) { Text(label) } }
        },
        shape = LovelyShapes.sheet,
        containerColor = scheme.surfaceContainerHigh,
        titleContentColor = scheme.onSurface,
        textContentColor = scheme.onSurfaceVariant,
        tonalElevation = 0.dp,
    )
}

/** 文章 1 つで確かめるダイアログ。取り消しのボタンは「やめる」。 */
@Composable
fun QuietConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    enabled: Boolean = true,
    dismissLabel: String = "やめる",
    error: String? = null,
) {
    QuietDialog(
        title = title,
        onDismissRequest = { if (enabled) onDismiss() },
        confirmLabel = confirmLabel,
        onConfirm = onConfirm,
        confirmEnabled = enabled,
        destructive = destructive,
        dismissLabel = dismissLabel,
        onDismiss = onDismiss,
        dismissEnabled = enabled,
        error = error,
    ) { Text(text) }
}

/**
 * 下から開くシート。面は surfaceContainerLow（カードと同じ不透明な面）、上端の角丸は [LovelyShapes.sheet] と同じ 24dp。
 * 中身は左右 [LovelySpacing.screenHorizontal]・上下 8dp / 24dp の余白で、見出しは [QuietSheetHeader] を使う。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuietSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = QuietSheetShape,
        containerColor = scheme.surfaceContainerLow,
        contentColor = scheme.onSurface,
        tonalElevation = 0.dp,
        content = content,
    )
}

private val QuietSheetShape = LovelyShapes.sheet.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))

/** シートの見出し。titleLarge で、読み上げでは見出しとして扱う。[supporting] は見出しの下の短い説明。 */
@Composable
fun QuietSheetHeader(title: String, supporting: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        supporting?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
