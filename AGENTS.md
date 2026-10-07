# Lovely Space 開発ガイド（人と AI エージェント共通）

このファイルは、このリポジトリで作業する人と AI エージェント（Claude Code、ChatGPT / Codex など）が最初に読むものです。`CLAUDE.md` はこのファイルを読み込むだけの入口です。

## このアプリ

2ショットチャット「ラブルーム」（`chat.shalove.net` ほか）専用の**非公式** Android クライアント。本家サイトには公開 API が無いため、本家の HTML と通信を解析して独自 UI で表示する。

- 要件: [docs/requirements.md](docs/requirements.md)
- ロードマップと現在地: [docs/roadmap.md](docs/roadmap.md)（GitHub の親 issue #26 と対応）
- 設計: [docs/architecture.md](docs/architecture.md)
- 本家の画面と通信の仕様: [docs/site-protocol.md](docs/site-protocol.md)

## 必ず守ること

1. **本家への通信は必ず `ShaloveClient` を通す。** 本家の利用規約は「通常のブラウザ利用とは異なるアクセスを繰り返す」ツールによるアクセスを禁止している。`ShaloveClient` が最小間隔（3 秒）と一覧キャッシュ（20 秒）を強制しているので、**間隔を短くする変更・キャッシュを迂回する変更はしない**。新しい機能で一覧が必要なときは、既存の取得結果を共有する（`RoomListRepository` など）。
2. **チャット中の通信は `ChatSession` に任せる。** `ajax.php` の新着取得・発言は、本家ブラウザの `2shot.js` と同じ規則（`PollSchedule` の間隔、発言は 1.5 秒以上空ける、新着取得は常に 1 本）で行う。本家より攻撃的にしない。
3. **ロボット確認（Cloudflare Turnstile / hCaptcha）は回避しない。** 入室（条件付き）と部屋作成（毎回）では、本家の画面を WebView で開き、入力欄をアプリが埋めたうえで、確認と送信は利用者が行う。
4. **本家の実データをコミットしない。** リポジトリは公開。他の利用者の名前・募集文・発言、部屋の `pwd`、Cookie は、テスト用データ・issue・PR・ログに入れない。テスト用 HTML（`core/src/test/resources/fixtures`）は本家の構造だけを再現した合成データにする。
5. **`pwd` は部屋の鍵として扱う。** URL・例外メッセージ・ナビゲーションの route・ログに出さない（`ShaloveClient.redact` を使う）。
6. **一覧の解析は PC 版レイアウト前提。** `ShaloveClient.USER_AGENT` は PC の Chrome のままにする。

## 構成

| モジュール | 内容 |
| --- | --- |
| `core` | 純粋な Kotlin/JVM（OkHttp・Jsoup・Coroutines）。本家への通信、HTML の解析、チャットのセッション、検索条件の判定。**サイトに依存する処理はすべてここに置き、JUnit でテストする。** |
| `app` | Android アプリ（Jetpack Compose・Material 3・Room・DataStore・Haze）。画面、端末内の保存、WebView との Cookie 共有。 |

詳しくは [docs/architecture.md](docs/architecture.md)。

## ビルドと検証

```sh
./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Google Maven や Android SDK に届かない環境（クラウドの開発環境など）では core だけを検証できる。

```sh
LS_JVM_ONLY=1 ./gradlew :core:test
```

GitHub Actions（`.github/workflows/android.yml`）が PR と main への push で core テスト・APK ビルド・Android ユニットテスト・Lint を実行し、デバッグ APK を Artifacts に保存する。app 側の変更は CI が通ることを確認してから完了とする。

本家サイトにはクラウドの開発環境から接続できない。実際の画面・通信の確認は、Yuya が Mac（Chrome の DevTools で HAR を保存）か実機の APK で行う。

## 作業の進め方

- **タスクは GitHub issue で管理している。** 親 issue #26（ロードマップ）の下にフェーズ issue、その下にタスク issue がある（GitHub のサブ issue）。着手するタスク issue の「やること」「完了条件」「依存」を読んでから始める。
- 1 タスク = 1 PR を基本にし、PR 本文に `Closes #<番号>` を書く。
- ラベル: `needs-capture` は本家の画面・通信の採取が必要（自分では採取できないので、必要な手順を issue に書いて Yuya に依頼する）。`needs-device-test` は実機確認が必要。`research` は調査結果の文書が成果物。
- 作業中に新しいタスクを見つけたら、該当するフェーズ issue のサブ issue として追加する。
- 本家の仕様について新しく分かったことは [docs/site-protocol.md](docs/site-protocol.md) に追記する。ロードマップの状態が変わったら [docs/roadmap.md](docs/roadmap.md) を更新する。

## 書き方

- UI の文言・コードのコメント・コミットメッセージ・PR・issue は**日本語**。
- PR の本文は「変更前 / 変更後 / 方法」と、検証したこと・実機確認が必要なことを書く。
- 既存のコードの書き方（命名・コメントの量・Compose の部品）に合わせる。デザインの方針は [docs/quiet-rose-redesign.md](docs/quiet-rose-redesign.md)。
- Room のスキーマを変えるときは `version` を上げて移行を書き、`app/schemas` の生成物をコミットする。既存の保存データ（プリセット・検索条件・お気に入り・非表示）を消さない。
