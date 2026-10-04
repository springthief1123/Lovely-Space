@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.springthief1123.lovelyspace.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
fun RoomCard(room: Room, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalLovelyColors.current
    val genderColor = when (room.gender) {
        Gender.FEMALE -> colors.female
        Gender.MALE -> colors.male
        Gender.UNKNOWN -> MaterialTheme.colorScheme.outline
    }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(12.dp)) {
            Avatar(room, genderColor)
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
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = buildString {
                            append(if (room.gender == Gender.FEMALE) "女" else if (room.gender == Gender.MALE) "男" else "")
                            room.age?.let { append(" ").append(it).append("歳") }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = genderColor,
                    )
                    Spacer(Modifier.weight(1f))
                    StatusPill(room)
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    room.area?.let { Tag(it) }
                    if (!room.isPublic) Tag("非公開", icon = { Icon(Icons.Outlined.Lock, null, Modifier.size(12.dp)) })
                    if (room.action == RoomAction.PEEK) Tag("覗ける", icon = { Icon(Icons.Outlined.Visibility, null, Modifier.size(12.dp)) })
                    room.elapsed?.let {
                        Text(
                            formatElapsed(it),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (room.message.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        room.message,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun Avatar(room: Room, color: Color) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        val initial = room.name?.trim()?.firstOrNull()?.toString()
        if (initial != null) {
            Text(initial, color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        } else {
            Icon(Icons.Filled.Favorite, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun StatusPill(room: Room) {
    val colors = LocalLovelyColors.current
    val (label, color) = when (room.status) {
        RoomStatus.WAITING -> "待機中" to colors.waiting
        RoomStatus.PUBLIC_WAITING -> "公開待機" to colors.publicWaiting
        RoomStatus.FULL -> "満室" to colors.full
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun Tag(text: String, icon: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        icon?.invoke()
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
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
