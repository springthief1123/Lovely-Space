package io.github.springthief1123.lovelyspace.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.ui.theme.LocalLovelyColors
import io.github.springthief1123.lovelyspace.ui.theme.LovelyShapes

/**
 * 浮かぶメニューの共通の外観。部屋の長押しメニュー（RoomActionMenu）と同じ面・角丸・縁取り・影・行の角丸を、
 * 標準の DropdownMenu にも使う。位置・Back・外側タップ・キーボード操作・スクロールは標準の DropdownMenu に任せる。
 */
object QuietMenuDefaults {
    /** メニューの中の 1 行を押したときに光る範囲の角丸。 */
    val itemShape = RoundedCornerShape(14.dp)
    val shadowElevation = 16.dp
    /** 行の左右の外側の余白。押したときの角丸がパネルの縁に付かないようにする。 */
    val itemOuterPadding = 6.dp
    /** 行の左右の内側の余白。外側と合わせて 18dp で、見出しの文字とそろう。 */
    val itemInnerPadding = 12.dp

    @Composable fun containerColor(): Color = MaterialTheme.colorScheme.surfaceContainerLow
    @Composable fun border(): BorderStroke = BorderStroke(1.dp, LocalLovelyColors.current.glassBorder)
}

/** ︙・通知・選択などの汎用メニュー。中身は [QuietMenuRow] を並べる。 */
@Composable
fun QuietDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        shape = LovelyShapes.menu,
        containerColor = QuietMenuDefaults.containerColor(),
        tonalElevation = 0.dp,
        shadowElevation = QuietMenuDefaults.shadowElevation,
        border = QuietMenuDefaults.border(),
        content = content,
    )
}

/** 読み取り専用の入力欄から開く選択メニュー（地域など）。外観は [QuietDropdownMenu] と同じ。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExposedDropdownMenuBoxScope.QuietExposedMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ExposedDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = LovelyShapes.menu,
        containerColor = QuietMenuDefaults.containerColor(),
        tonalElevation = 0.dp,
        shadowElevation = QuietMenuDefaults.shadowElevation,
        border = QuietMenuDefaults.border(),
        content = content,
    )
}

/**
 * メニューの 1 行。RoomActionMenu の行と同じく、アイコンはローズ、文字は本文の大きさ、押すと角丸の範囲が光る。
 * [destructive] は削除などの取り消せない操作で、文字とアイコンを error の色にする。
 * [selected] は選択メニューの現在の値で、右端に印を付け、読み上げでも選択中と伝える。
 */
@Composable
fun QuietMenuRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supporting: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = if (destructive) scheme.error else scheme.primary
    DropdownMenuItem(
        text = {
            Column {
                Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                supporting?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.38f))
                }
            }
        },
        onClick = onClick,
        modifier = modifier
            .padding(horizontal = QuietMenuDefaults.itemOuterPadding)
            .clip(QuietMenuDefaults.itemShape)
            .semantics { if (selected) this.selected = true },
        leadingIcon = if (icon != null) {
            { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) }
        } else null,
        trailingIcon = if (selected) {
            { Icon(Icons.Outlined.Check, contentDescription = null) }
        } else null,
        enabled = enabled,
        colors = MenuDefaults.itemColors(
            textColor = if (destructive) scheme.error else scheme.onSurface,
            leadingIconColor = accent,
            trailingIconColor = scheme.primary,
            disabledTextColor = scheme.onSurface.copy(alpha = 0.38f),
            disabledLeadingIconColor = accent.copy(alpha = 0.38f),
            disabledTrailingIconColor = scheme.primary.copy(alpha = 0.38f),
        ),
        contentPadding = PaddingValues(horizontal = QuietMenuDefaults.itemInnerPadding),
    )
}
