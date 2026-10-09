# Lovely Space 開発ガイド（人と AI エージェント共通）

このファイルは、このリポジトリで作業する人と AI エージェント（Claude Code、ChatGPT / Codex など）が最初に読むものです。`CLAUDE.md` はこのファイルを読み込むだけの入口です。

## このアプリ

2ショットチャット「ラブルーム」（`chat.shalove.net` ほか）専用の**非公式** Android クライアント。本家サイトには公開 API が無いため、本家の HTML と通信を解析して独自 UI で表示する。

- 要件: [docs/requirements.md](docs/requirements.md)
- ロードマップと現在地: [docs/roadmap.md](docs/roadmap.md)（GitHub の親 issue #26 と対応）
- 設計: [docs/architecture.md](docs/architecture.md)
- 本家の画面と通信の仕様: [docs/site-protocol.md](docs/site-protocol.md)

## 必ず守ること

1. **アプリが自分で行う本家への通信は必ず `ShaloveClient` を通す。** 本家の利用規約は「通常のブラウザ利用とは異なるアクセスを繰り返す」ツールによるアクセスを禁止している。`ShaloveClient` がリクエスト同士の最小間隔と一覧キャッシュを強制している。間隔は利用者が設定の「更新の間隔」で選ぶ（`RefreshPacing`。既定は最小間隔 3 秒・見つける画面とレーダーの 1 ページ目 4 秒・前面での空き枠の確認 5 秒・公開ルーム 20 秒、キャッシュは一覧を取り直す間隔のうち最も短いもの）。空き枠や新しい部屋は秒単位で埋まるので、本家で更新ボタンを押しながら待つ程度まで縮められるようにした Yuya の決定（2026-10-09）。ただし 1 秒に 5 回以上の再読み込みで本家から一時的にアクセスを止められたことがあるので、**最小間隔の下限 1 秒・各機能の下限 2 秒（`RefreshPacing.FLOOR_*`）を下げる変更、選択肢を下限より短くする変更、キャッシュや間隔を迂回する変更はしない**。取得の失敗時は 20 秒空ける（`RoomPageSchedule.ERROR_INTERVAL_MS`）。新しい機能で一覧が必要なときは、既存の取得結果を共有する（`RoomListRepository` など）。例外は利用者が操作する `SiteWebView`（ロボット確認つきの入室・部屋作成など）で、これは通常のブラウザとして本家と直接通信し、`ShaloveClient` の間隔制限はかからない。WebView にアプリから自動で読み込み・送信を繰り返させない。
2. **チャット中の通信は `ChatSession` に任せる。** `ajax.php` の新着取得・発言は、本家ブラウザの `2shot.js` と同じ規則（`PollSchedule` の間隔、発言は 1.5 秒以上空ける、新着取得は常に 1 本）で行う。本家より攻撃的にしない。
3. **ロボット確認（Cloudflare Turnstile / hCaptcha）は回避しない。** 入室（条件付き）と部屋作成（毎回）では、本家の画面を WebView で開き、入力欄をアプリが埋めたうえで、確認と送信は利用者が行う。
4. **本家の実データをコミットしない。** リポジトリは公開。他の利用者の名前・募集文・発言、部屋の `pwd`、Cookie は、テスト用データ・issue・PR・ログに入れない。テスト用 HTML（`core/src/test/resources/fixtures`）は本家の構造だけを再現した合成データにする。
5. **`pwd` は部屋の鍵として扱う。** URL・例外メッセージ・ナビゲーションの route・ログに出さない。core の通信エラー（`HttpStatusException`）は URL の `pwd` を伏せて持つ。伏せる処理（`HttpStatusException.redact`）は core 内部用なので、app 側で `pwd` を含む URL を扱う必要が出たら core に公開の関数を用意してから使う。
6. **一覧の解析は PC 版レイアウト前提。** `ShaloveClient.USER_AGENT` は PC の Chrome のままにする。

## 構成

| モジュール | 内容 |
| --- | --- |
| `core` | 純粋な Kotlin/JVM（OkHttp・Jsoup・Coroutines）。本家への通信、HTML の解析、チャットのセッション、検索条件の判定。**サイトに依存する処理はここに置き、JUnit でテストする。**（例外: WebView の入力済み化で使うフォーム名・項目名は app の `EntryScreen.kt`・`CreateRoomScreen.kt` にある。[docs/architecture.md](docs/architecture.md) 参照） |
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

## UI・デザインの共通ルール

- **Lovely Space の UI は Quiet Rose に統一する。** UI 実装・変更・レビューの前に [docs/design-system.md](docs/design-system.md) を読み、既存の [docs/quiet-rose-redesign.md](docs/quiet-rose-redesign.md) と実装を照合する。
- **Google 標準の Material 3 の外観を無調整で追加しない。** Material 3 自体の使用は禁止しない。色、形状、余白、タイポグラフィ、選択・無効・エラー状態を Quiet Rose に合わせる。
- **既存の Lovely / Quiet コンポーネントとトークンを優先**する。重複する独自 UI を増やさず、必要なら共通化してから利用する。特にメニュー、チップ、入力欄、ダイアログ、シート、スイッチ、ヘッダーは標準デザインの露出を確認する。
- Glass は原則として既存のメインヘッダー、下部ナビ、作成 FAB 等の意図された浮遊 UI で使い、通常カード・フォームへの無秩序な展開はしない。
- **UI 変更は見た目だけでなく挙動を維持する。** Back、画面外タップ、フォーカス、IME、状態保持、TalkBack、文字拡大、ライト／ダークを確認する。ビルド・Lint が通っても実画面を確認できなければ「視覚検証済み」とは扱わず、実機確認項目を PR に残す。
- 例外的に Material の既定外観を残す場合は、その理由と比較対象を PR に記載する。新たな色・形状・共通部品は [docs/design-system.md](docs/design-system.md) と同期する。

## 書き方

- UI の文言・コードのコメント・コミットメッセージ・PR・issue は**日本語**。
- PR の本文は「変更前 / 変更後 / 方法」と、検証したこと・実機確認が必要なことを書く。
- 既存のコードの書き方（命名・コメントの量・Compose の部品）に合わせる。デザインの実装基準は [docs/design-system.md](docs/design-system.md)、画面・機能の意図は [docs/quiet-rose-redesign.md](docs/quiet-rose-redesign.md)。
- Room のスキーマを変えるときは `version` を上げて移行を書き、`app/schemas` の生成物をコミットする。既存の保存データ（プリセット・検索条件・お気に入り・非表示）を消さない。
