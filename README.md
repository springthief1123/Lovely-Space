# Lovely Space

2ショットチャット「ラブルーム」専用の非公式Androidクライアントです。部屋一覧・入室・チャットはJetpack Composeで表示し、公開ルーム閲覧もアプリのチャットUIで表示し、ロボット確認が必要な入室・部屋作成は本家のWebView画面を使います。

通信はアプリ全体で共有する `ShaloveClient` に集約し、既存の間隔制限・一覧キャッシュを維持します。チャットは本家ブラウザの受信・発言スケジュールに合わせます。

## 構成

| モジュール | 内容 |
| --- | --- |
| `core` | Kotlin/JVM。通信、HTML解析、チャットセッション、検索条件判定 |
| `app` | Android / Compose。Glassデザイン、画面、WebViewとHTTPのCookie共有、Roomによるプリセット・検索条件・部屋設定保存 |

## ドキュメント

- [AGENTS.md](AGENTS.md): 開発ルールと進め方（人と AI エージェント共通）
- [docs/roadmap.md](docs/roadmap.md): ロードマップと残りのタスク（親 issue [#26](https://github.com/springthief1123/Lovely-Space/issues/26)）
- [docs/](docs/README.md): 要件・設計・本家サイトの仕様

## ビルド・検証

```sh
./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Android SDKやGoogle Mavenに届かない環境では、coreだけを検証できます。

```sh
LS_JVM_ONLY=1 ./gradlew :core:test
```

GitHub ActionsはPRとmainへのpushでcoreテスト、APKビルド、Androidユニットテスト、Lintを実行します。成功したrunのArtifactsに検証対象commitのデバッグAPKを保存します。

## 実装状況

以下は初期開発時の主な PR の履歴です。現在の対応機能・未対応機能は [要件と対応状況](docs/requirements.md)、今後の作業は [ロードマップ](docs/roadmap.md) と GitHub Issues を参照してください。

| PR | 内容 |
| --- | --- |
| [#6](https://github.com/springthief1123/Lovely-Space/pull/6) | Glassデザイン、4タブ、Light/Dark切替 |
| [#7](https://github.com/springthief1123/Lovely-Space/pull/7) | 初回チャット受信の待機計算修正 |
| [#8](https://github.com/springthief1123/Lovely-Space/pull/8) | WebViewとHTTPのCookie共有 |
| [#9](https://github.com/springthief1123/Lovely-Space/pull/9) | プロフィール・待機メッセージの複数保存、既定値、入室・作成での選択 |
| [#10](https://github.com/springthief1123/Lovely-Space/pull/10) | 条件検索、AND/OR・除外、年齢不明の扱い、並び替え、取得範囲の表示 |
| [#12](https://github.com/springthief1123/Lovely-Space/pull/12) | 検索条件の名前付き保存・適用、Room v2移行 |

現在の main では、一覧の全ページの段階的な取得、端末内の絞り込み・おすすめ順、条件の保存、NG ワード、部屋のお気に入り・非表示、レーダー（ピン・条件・名前で待つ）、自動入室、通知とお知らせ、アプリロックを利用できます。一部の背景機能や WebView 認証・表示は実機検証が必要です。仕様の変化が速いため、この README では機能ごとの完了・未完了を固定せず、上記の要件・ロードマップを正として扱います。

自動巡回・直接入室・公開ルーム専用表示の仕様と制約は [自動取得・入室・公開閲覧](docs/automatic-discovery.md) を参照してください。
