package io.github.springthief1123.lovelyspace.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.core.RoomStatus
import io.github.springthief1123.lovelyspace.ui.theme.LocalLovelyColors
import kotlin.time.Duration

@Composable
fun RoomCard(
    room: Room,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isFavorite: Boolean = false,
    isHidden: Boolean = false,
    onFavoriteClick: (() -> Unit)? = null,
    onHideClick: (() -> Unit)? = null,
    actionsEnabled: Boolean = true,
) {
    val lovely = LocalLovelyColors.current
    val genderColor = when (room.gender) {
        Gender.FEMALE -> lovely.female
        Gender.MALE -> lovely.male
        Gender.UNKNOWN -> MaterialTheme.colorScheme.outline
    }
    val statusColor = when (room.status) {
        RoomStatus.WAITING -> lovely.waiting
        RoomStatus.PUBLIC_WAITING -> lovely.publicWaiting
        RoomStatus.FULL -> lovely.full
    }

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .height(IntrinsicSize.Min)
                .padding(vertical = 14.dp),
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(statusColor.copy(alpha = 0.85f)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = room.name ?: "2ショットチャット中",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (room.name == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    val profile = buildString {
                        if (room.gender == Gender.FEMALE) append("女")
                        if (room.gender == Gender.MALE) append("男")
                        room.age?.let {
                            if (isNotEmpty()) append(" ")
                            append(it).append("歳")
                        }
                    }
                    if (profile.isNotEmpty()) {
                        Text(
                            profile,
                            style = MaterialTheme.typography.labelMedium,
                            color = genderColor,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Text(
                        statusLabel(room.status),
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }

                Spacer(Modifier.height(5.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    room.area?.let { MetaText(it) }
                    if (!room.isPublic) {
                        MetaIcon(Icons.Outlined.Lock, "非公開")
                    }
                    if (room.action == RoomAction.PEEK) {
                        MetaIcon(Icons.Outlined.Visibility, "覗ける")
                    }
                    room.elapsed?.let { MetaText(formatElapsed(it)) }
                }

                if (room.message.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        room.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (onFavoriteClick != null || onHideClick != null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onFavoriteClick != null) {
                    TextButton(onClick = onFavoriteClick, enabled = actionsEnabled) {
                        Text(if (isFavorite) "お気に入り解除" else "お気に入り")
                    }
                }
                if (onHideClick != null) {
                    TextButton(onClick = onHideClick, enabled = actionsEnabled) {
                        Text(if (isHidden) "非表示解除" else "非表示")
                    }
                }
            }
        }
        HorizontalDivider(color = lovely.divider)
    }
}

@Composable
private fun MetaText(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MetaIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        MetaText(label)
    }
}

private fun statusLabel(status: RoomStatus): String = when (status) {
    RoomStatus.WAITING -> "待機中"
    RoomStatus.PUBLIC_WAITING -> "公開待機"
    RoomStatus.FULL -> "満室"
}

internal fun formatElapsed(d: Duration): String {
    val h = d.inWholeHours
    val m = d.inWholeMinutes % 60
    return when {
        h > 0 -> "${h}時間${m}分"
        m > 0 -> "${m}分"
        else -> "たった今"
    }
}
