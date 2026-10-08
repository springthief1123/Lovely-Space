package io.github.springthief1123.lovelyspace.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.chat.ChatRoomRef

/**
 * 進行中の部屋へ戻る帯。一覧を見ている間は主画面の下に常に出す。
 * [activity] があれば（会話画面を開いた後）、離れている間の新着の数と待機中かを出す。
 * × は帯を小さな丸いボタンにするだけで、会話への入口は残す。小さい形を押すと会話に戻る（次に部屋へ入るまで小さいまま）。
 */
@Composable
fun ResumeChatBar(
    room: ChatRoomRef,
    activity: ChatActivity?,
    collapsed: Boolean,
    onResume: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unseen = activity?.unseen ?: 0
    val summary = resumeSummary(Genres[room.genreKey]?.label ?: room.genreKey, activity)
    if (collapsed) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            BadgedBox(badge = { if (unseen > 0) Badge { Text(if (unseen > 99) "99+" else unseen.toString()) } }) {
                SmallFloatingActionButton(
                    onClick = onResume,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.semantics { contentDescription = "会話に戻る。$summary" },
                ) { Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null) }
            }
        }
        return
    }
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier.clickable(onClickLabel = "会話に戻る", role = Role.Button, onClick = onResume).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BadgedBox(badge = { if (unseen > 0) Badge() }) {
                Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("会話に戻る", style = MaterialTheme.typography.titleSmall)
                Text(summary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            IconButton(onClick = onCollapse) { Icon(Icons.Outlined.Close, contentDescription = "帯を小さくする") }
        }
    }
}

/** 帯の 2 行目。部屋の終了、新着の件数、作成者の待機中の順に優先して出す。 */
internal fun resumeSummary(genreLabel: String, activity: ChatActivity?): String = when {
    activity == null -> "${genreLabel}の進行中の部屋"
    activity.ended -> "部屋は終了しました・開くと理由を確認できます"
    activity.unseen > 0 -> "新着 ${activity.unseen}件・${genreLabel}"
    activity.waitingForPartner -> "相手の入室を待っています・${genreLabel}"
    else -> "${genreLabel}の進行中の部屋・新着を確認しています"
}
