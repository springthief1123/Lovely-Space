package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpacing

@Composable
fun SearchScreen() = MainSectionScreen(
    title = "さがす",
    description = "名前・待機メッセージと検索条件から、話したい相手の部屋を探します。",
)

@Composable
fun FavoritesScreen() = MainSectionScreen(
    title = "お気に入り",
    description = "ピン留めした部屋と、追跡・巡回している条件をここにまとめます。",
)

@Composable
fun ProfileScreen() = MainSectionScreen(
    title = "マイページ",
    description = "プロフィールプリセットと待機メッセージプリセットを管理します。",
)

@Composable
private fun MainSectionScreen(title: String, description: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(
                start = LovelySpacing.screenHorizontal,
                end = LovelySpacing.screenHorizontal,
                top = LovelySpacing.topContentInset,
                bottom = LovelySpacing.bottomContentInset,
            ),
        verticalArrangement = Arrangement.Top,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
