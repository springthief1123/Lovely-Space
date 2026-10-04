@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.springthief1123.lovelyspace.ui.web

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.springthief1123.lovelyspace.core.SitePages

/**
 * 満室の公開ルームを覗く。覗き画面はまだ解析していないので、本家のページをそのまま表示する。
 * 新着の更新はページ自身の仕組み（ブラウザと同じ）に任せる。
 */
@Composable
fun PublicRoomScreen(host: String, genreKey: String, roomId: Long, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("公開ルームを覗く") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        SiteWebView(
            url = SitePages.publicRoom(host, genreKey, roomId),
            genreKey = genreKey,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}
