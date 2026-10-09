package io.github.springthief1123.lovelyspace.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.ui.theme.LovelyShapes

// チップ・セグメント・チェックの Quiet Rose の外観。docs/design-system.md 2.3 と同期する。
// - 境界: 補助的な絞り込み・操作（チップ）は outlineVariant。選択中のチップは primary の縁取りで、面の色だけに頼らずに分ける。
// - 選択中の面と文字: secondaryContainer / onSecondaryContainer。
// - フォームで入力欄と並ぶセグメントの境界は、入力欄と同じ outline（背景との差 3:1 以上）。

/**
 * 選ぶと絞り込みが切り替わるチップ。非選択は細い境界だけ、選択中は淡いローズの面と primary の縁取り。
 * 選択状態は FilterChip のまま読み上げられる（「選択済み」）。
 */
@Composable
fun QuietFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        enabled = enabled,
        leadingIcon = if (leadingIcon != null) {
            { Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(16.dp)) }
        } else null,
        trailingIcon = trailingIcon,
        shape = LovelyShapes.control,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = scheme.onSurfaceVariant,
            iconColor = scheme.onSurfaceVariant,
            selectedContainerColor = scheme.secondaryContainer,
            selectedLabelColor = scheme.onSecondaryContainer,
            selectedLeadingIconColor = scheme.primary,
            selectedTrailingIconColor = scheme.onSecondaryContainer,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = scheme.outlineVariant,
            selectedBorderColor = scheme.primary,
            disabledBorderColor = scheme.onSurface.copy(alpha = 0.12f),
            disabledSelectedBorderColor = scheme.onSurface.copy(alpha = 0.12f),
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp,
        ),
    )
}

/** 押すと何かをする小さなチップ（「今の条件を保存」「新着・先頭へ」など）。アイコンはローズ、境界は細く。 */
@Composable
fun QuietAssistChip(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val scheme = MaterialTheme.colorScheme
    AssistChip(
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        enabled = enabled,
        leadingIcon = if (icon != null) {
            { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) }
        } else null,
        shape = LovelyShapes.control,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = Color.Transparent,
            labelColor = scheme.onSurface,
            leadingIconContentColor = scheme.primary,
        ),
        border = AssistChipDefaults.assistChipBorder(
            enabled = enabled,
            borderColor = scheme.outlineVariant,
            disabledBorderColor = scheme.onSurface.copy(alpha = 0.12f),
        ),
    )
}

/** 性別などをフォームで 1 つ選ぶセグメント。選択中は淡いローズの面と primary の縁取り（✓ は標準のまま出る）。 */
@Composable
fun quietSegmentedButtonColors(): SegmentedButtonColors {
    val scheme = MaterialTheme.colorScheme
    return SegmentedButtonDefaults.colors(
        activeContainerColor = scheme.secondaryContainer,
        activeContentColor = scheme.onSecondaryContainer,
        activeBorderColor = scheme.primary,
        inactiveContainerColor = Color.Transparent,
        inactiveContentColor = scheme.onSurface,
        inactiveBorderColor = scheme.outline,
    )
}

/**
 * 文字付きのチェック。行全体を押せるようにし、読み上げでは文字とチェックの状態を 1 つの項目として伝える。
 * （チェックだけを押せて文字が読み上げで結び付かない、以前の Row { Checkbox; Text } の代わり）
 */
@Composable
fun QuietCheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(LovelyShapes.control)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.weight(1f))
    }
}
