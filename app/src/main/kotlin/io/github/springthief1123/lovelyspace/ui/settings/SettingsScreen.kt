package io.github.springthief1123.lovelyspace.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.ui.components.QuietTopBar
import io.github.springthief1123.lovelyspace.ui.theme.LovelyShapes

@Composable
fun SettingsScreen(
    onOpenDisplay: () -> Unit,
    onOpenRoomList: () -> Unit,
    onOpenRefresh: () -> Unit,
    onOpenHiddenRooms: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenLock: () -> Unit,
    onBack: () -> Unit,
) {
    val bottomContentPadding = 18.dp +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SettingsPageHeader(title = "設定", onBack = onBack)
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 18.dp,
                end = 16.dp,
                bottom = bottomContentPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "アプリの表示・部屋一覧・更新の間隔・通知・ロックの設定を、用途ごとに整理しています。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
                )
            }
            item {
                SettingsCategoryCard(
                    icon = Icons.Outlined.Palette,
                    title = "表示設定",
                    description = "テーマ、テキストサイズ、待機メッセージの行数",
                    onClick = onOpenDisplay,
                )
            }
            item {
                SettingsCategoryCard(
                    icon = Icons.AutoMirrored.Outlined.ViewList,
                    title = "部屋一覧",
                    description = "起動時に開くジャンル",
                    onClick = onOpenRoomList,
                )
            }
            item {
                SettingsCategoryCard(
                    icon = Icons.Outlined.Timer,
                    title = "更新の間隔",
                    description = "新しい部屋・自動巡回・空き枠・覗いている部屋を確認する間隔",
                    onClick = onOpenRefresh,
                )
            }
            item {
                SettingsCategoryCard(
                    icon = Icons.Outlined.VisibilityOff,
                    title = "非表示にした部屋",
                    description = "一覧から隠した部屋の確認と解除",
                    onClick = onOpenHiddenRooms,
                )
            }
            item {
                SettingsCategoryCard(
                    icon = Icons.Outlined.NotificationsNone,
                    title = "通知",
                    description = "端末通知の許可、ロック画面での表示、テスト通知",
                    onClick = onOpenNotifications,
                )
            }
            item {
                SettingsCategoryCard(
                    icon = Icons.Outlined.Lock,
                    title = "アプリロック",
                    description = "パスコード・パターン・生体認証、ロックまでの時間",
                    onClick = onOpenLock,
                )
            }
        }
    }
}

/** 設定の各ページの見出し。シェルの外の画面と同じ [QuietTopBar] を使い、ステータスバーの余白は親が取る。 */
@Composable
internal fun SettingsPageHeader(title: String, onBack: () -> Unit) {
    QuietTopBar(title, windowInsets = WindowInsets(0, 0, 0, 0), onBack = onBack)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SettingsCategoryCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    // 一覧の行（QuietListPanel）と同じ不透明な面・角丸・境界。以前は 18dp と tonalElevation の色の重ねで、ほかのカードと違っていた。
    Surface(
        shape = LovelyShapes.panel,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = LovelyShapes.control,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
