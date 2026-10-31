package io.github.springthief1123.lovelyspace.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.core.SitePages
import io.github.springthief1123.lovelyspace.ui.web.SiteWebView

/**
 * 本家サイトの会員画面。パスワード・認証トークンはアプリで読み取らず、本家の WebView にだけ入力する。
 * WebView と ShaloveClient は WebViewCookieStore を共有するため、別の認証情報は保存しない。
 */
@Composable
fun MemberAccountScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SettingsPageHeader(title = "会員アカウント", onBack = onBack)
        Text(
            "本家ラブルームの会員ページです。ログイン・新規登録などは本家の画面で行います。ログイン情報はアプリ内の本家サイトへの通信と共有されます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        SiteWebView(
            url = SitePages.memberAccount(),
            genreKey = Genres.default.key,
            modifier = Modifier.fillMaxWidth().weight(1f).navigationBarsPadding(),
        )
    }
}
