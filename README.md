# Lovely Space

2ショットチャット「ラブルーム」専用の Android ブラウザアプリ。本家サイトの画面をそのまま表示せず、モバイル向けの独自 UI で部屋一覧やチャットを扱います。

> 非公式アプリです。本家サイトの利用規約に従い、アクセス頻度は通常のブラウザ利用と同程度に抑えています（`core` の `ShaloveClient` がすべての通信の間隔を制限します）。

## 構成

| モジュール | 内容 |
|---|---|
| `core` | Kotlin/JVM のみ。本家サイトへの通信（`ShaloveClient`）、HTML の解析（`RoomListParser`）、ジャンル定義と一覧 URL の組み立て |
| `app` | Android アプリ（Jetpack Compose + Material 3）。テーマ切り替え、部屋一覧画面 |

## ビルド

```sh
./gradlew :core:test :app:assembleDebug
```

Android SDK や Google Maven に届かない環境では、`core` だけをビルド・テストできます。

```sh
LS_JVM_ONLY=1 ./gradlew :core:test
```

GitHub Actions が push と PR のたびにテストとデバッグ APK のビルドを行い、APK を Artifacts に保存します。

## 実装状況

- [x] フェーズ1: 土台、テーマ（ライト / ダーク / 端末に合わせる）、ジャンル別の部屋一覧
- [ ] フェーズ2: 入室・チャット・部屋作成
- [ ] フェーズ3: プロフィールプリセット
- [ ] フェーズ4: 絞り込み検索・並び替え・ブックマーク・ブロック
- [ ] フェーズ5: 入室通知・順番待ち・定期巡回
