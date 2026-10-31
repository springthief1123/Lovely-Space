package io.github.springthief1123.lovelyspace.ui.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.SitePages
import io.github.springthief1123.lovelyspace.core.chat.ChatRoomRef
import io.github.springthief1123.lovelyspace.ui.components.QuietDropdownMenu
import io.github.springthief1123.lovelyspace.ui.components.QuietMenuRow
import io.github.springthief1123.lovelyspace.ui.settings.SettingsPageHeader
import kotlinx.coroutines.flow.first

/**
 * 本家の再入室操作を WebView で 1 回開く。ログイン Cookie は会員画面と共通。
 * 入室済みの部屋があれば本家が 2shot.php に移るので、既存の SiteWebView が
 * pwd をナビゲーションに出さずアプリのチャットへ引き継ぐ。
 */
@Composable
fun SiteReentryScreen(onBack: () -> Unit, onRoomOpened: (ChatRoomRef) -> Unit) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var ready by rememberSaveable { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    // 保存中の部屋があればそのジャンル、無ければ最後に見たジャンルを先に開く。
    // 設定読み込み前に別ジャンルへ誤って通信しないよう、完了まで WebView は作らない。
    LaunchedEffect(Unit) {
        if (!ready) {
            selectedKey = app.activeRooms.resumable.value?.room?.genreKey
                ?.takeIf { Genres[it] != null }
                ?: app.settings.roomListPreferences.first().lastGenreKey
            ready = true
        }
    }
    val genre = selectedKey?.let { Genres[it] }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SettingsPageHeader(title = "再入室", onBack = onBack)
        Text(
            "本家サイトにログインした状態で、入室中の部屋へ戻ります。自分で作った部屋も同じ操作で戻れます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("部屋のジャンル", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Box {
                OutlinedButton(onClick = { expanded = true }, enabled = genre != null) {
                    Text(genre?.label ?: "読み込み中")
                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                }
                QuietDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.heightIn(max = 360.dp),
                ) {
                    Genres.all.forEach { choice ->
                        QuietMenuRow(
                            choice.label,
                            supporting = choice.group.label,
                            selected = choice.key == genre?.key,
                            onClick = {
                                expanded = false
                                selectedKey = choice.key
                            },
                        )
                    }
                }
            }
        }
        Text(
            "戻れない場合は、入室していたジャンルを選び直してください。部屋が終了している場合は、本家の案内が表示されます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        Box(Modifier.fillMaxWidth().weight(1f).navigationBarsPadding(), contentAlignment = Alignment.Center) {
            if (genre != null && ready) {
                // ジャンルを変えたら WebView も作り直し、前のページへ再送信しない。
                key(genre.key) {
                    SiteWebView(
                        url = SitePages.reenterRoom(genre),
                        genreKey = genre.key,
                        modifier = Modifier.fillMaxSize(),
                        onRoomOpened = onRoomOpened,
                    )
                }
            } else {
                CircularProgressIndicator()
            }
        }
    }
}
