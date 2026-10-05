# Lovely Space

2ショットチャット「ラブルーム」専用の非公式Androidクライアントです。部屋一覧・入室・チャットはJetpack Composeで表示し、ロボット確認が必要な入室・部屋作成と公開ルーム閲覧は本家のWebView画面を使います。

通信はアプリ全体で共有する `ShaloveClient` に集約し、既存の間隔制限・一覧キャッシュを維持します。チャットは本家ブラウザの受信・発言スケジュールに合わせます。

## 構成

| モジュール | 内容 |
| --- | --- |
| `core` | Kotlin/JVM。通信、HTML解析、チャットセッション、検索条件判定 |
| `app` | Android / Compose。Glassデザイン、画面、WebViewとHTTPのCookie共有、Roomによるプリセット保存 |

## ビルド・検証

```sh
./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Android SDKやGoogle Mavenに届かない環境では、coreだけを検証できます。

```sh
LS_JVM_ONLY=1 ./gradlew :core:test
```

GitHub ActionsはPRとmainへのpushでcoreテストとAPKビルドを実行します。PR #9以降のブランチはAndroidユニットテストとLintも実行します。成功したrunのArtifactsに検証対象commitのデバッグAPKを保存します。

## 実装状況

mainにはジャンル一覧、入室・チャット、部屋作成・公開閲覧までが取り込まれています。最新デザインと追加機能は次のPRに分けて開発中です。後続PRのAPKには前段の変更も含まれます。

| PR | 内容 |
| --- | --- |
| [#6](https://github.com/springthief1123/Lovely-Space/pull/6) | Glassデザイン、4タブ、Light/Dark切替 |
| [#7](https://github.com/springthief1123/Lovely-Space/pull/7) | 初回チャット受信の待機計算修正 |
| [#8](https://github.com/springthief1123/Lovely-Space/pull/8) | WebViewとHTTPのCookie共有 |
| [#9](https://github.com/springthief1123/Lovely-Space/pull/9) | プロフィール・待機メッセージの複数保存、既定値、入室・作成での選択 |
| [#10](https://github.com/springthief1123/Lovely-Space/pull/10) | 条件検索、AND/OR・除外、年齢不明の扱い、並び替え、取得範囲の表示 |

検索は選択したジャンルの取得済みページが対象です。次ページは利用者の操作で追加取得し、条件入力のたびには通信しません。

今後の対象は検索条件の保存、お気に入りと非表示、認証フローの整理、セッションの復帰、通知・背景監視・順番待ちです。お気に入りタブは現在プレースホルダーです。実機の表示と本家との一連の操作は別途検証が必要です。詳細は [実装引継ぎ状況](docs/implementation-status.md) を参照してください。
