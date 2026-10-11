package io.github.springthief1123.lovelyspace.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.ai.EmbeddingPocActivity
import io.github.springthief1123.lovelyspace.ai.ExistingSnapshotSearchActivity

/** debugビルドにだけ存在する「設定 > 開発者用ツール」。releaseでは別の空実装を使う。 */
internal object DeveloperTools {
    const val available = true

    @Composable
    fun Screen(onBack: () -> Unit) {
        val context = LocalContext.current
        val bottomPadding = 18.dp +
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            SettingsPageHeader(title = "開発者用ツール", onBack = onBack)
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 18.dp,
                    end = 16.dp,
                    bottom = bottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "この項目はdebugビルドでのみ表示されます。実機での検証専用で、本番の検索・通知・巡回には影響しません。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
                    )
                }
                item {
                    SettingsCategoryCard(
                        icon = Icons.Outlined.Analytics,
                        title = "AI意味検索検証",
                        description = "モデル取り込み、架空文の性能・品質、RAM解放・通常画面の負荷計測",
                        onClick = {
                            context.startActivity(Intent(context, EmbeddingPocActivity::class.java))
                        },
                    )
                }
                item {
                    SettingsCategoryCard(
                        icon = Icons.Outlined.Search,
                        title = "取得済み一覧AI検証",
                        description = "通信を増やさず、取得済みの公開募集文を手動で端末内検索",
                        onClick = {
                            context.startActivity(Intent(context, ExistingSnapshotSearchActivity::class.java))
                        },
                    )
                }
                item {
                    Text(
                        "取得済み一覧の検証では、先に通常の「見つける」で部屋一覧を取得してください。募集文・検索文・ベクトルはCSVや永続キャッシュに保存しません。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
