# Lovely Space

2ショットチャット「ラブルーム」専用の非公式Androidクライアントです。部屋一覧・入室・チャットはJetpack Composeで表示し、ロボット確認が必要な入室・部屋作成と公開ルーム閲覧は本家のWebView画面を使います。

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

mainにはPR #12までのデザインと追加機能を統合済みです。

| PR | 内容 |
| --- | --- |
| [#6](https://github.com/springthief1123/Lovely-Space/pull/6) | Glassデザイン、4タブ、Light/Dark切替 |
| [#7](https://github.com/springthief1123/Lovely-Space/pull/7) | 初回チャット受信の待機計算修正 |
| [#8](https://github.com/springthief1123/Lovely-Space/pull/8) | WebViewとHTTPのCookie共有 |
| [#9](https://github.com/springthief1123/Lovely-Space/pull/9) | プロフィール・待機メッセージの複数保存、既定値、入室・作成での選択 |
| [#10](https://github.com/springthief1123/Lovely-Space/pull/10) | 条件検索、AND/OR・除外、年齢不明の扱い、並び替え、取得範囲の表示 |
| [#12](https://github.com/springthief1123/Lovely-Space/pull/12) | 検索条件の名前付き保存・適用、Room v2移行 |

検索は選択したジャンルの取得済みページが対象です。次ページは利用者の操作で追加取得し、条件入力のたびには通信しません。

検索条件は「さがす」のジャンルと全条件を名前付きで保存し、適用・名前変更・現在の条件で更新・削除できます。同じジャンルへの適用は取得済みページを保持し、別ジャンルへの適用後は検索ボタンで取得します。保存・適用だけでは本家への通信を行いません。

追加開発中のお気に入り・非表示では、部屋を端末内に保存し、通常一覧・検索結果から非表示にできます。保存した部屋は一覧で再観測したときだけ表示内容を更新し、同じIDでプロフィールが矛盾した場合はID再利用の可能性として自動追従しません。さらにUI/UX改善ブランチでは、部屋カードの横スワイプ、お気に入り専用画面、設定のサブページ化、表示サイズ設定、起動カテゴリ記憶、トップバーとボトムナビのアニメーションを追加しています。通知ベルは先にUIのみ用意し、実通知データは今後の通知機能で接続します。今後の対象は認証フローの整理、セッションの復帰、通知・背景監視・順番待ちです。実機の表示と本家との一連の操作は別途検証が必要です。詳細は [実装引継ぎ状況](docs/implementation-status.md) を参照してください。
