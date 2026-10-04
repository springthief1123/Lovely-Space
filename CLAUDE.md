# Lovely Space

- 2ショットチャット「ラブルーム」(chat.shalove.net) 専用の非公式 Android クライアント。UI・コメント・コミットメッセージは日本語。
- `core` は純粋な Kotlin/JVM。サイト依存の処理（通信・HTML 解析）はすべてここに置き、JUnit でテストする。
- 本家の利用規約がツールによる繰り返しアクセスを禁止しているため、通信は必ず `ShaloveClient` を通す（最小間隔とキャッシュをここで強制）。間隔を短くする変更はしない。
- テスト用 HTML（`core/src/test/resources/fixtures`）は本家の構造を再現した合成データ。リポジトリは公開なので、本家から取得した実データ（他の利用者の名前・募集文・room の pwd）はコミットしない。
- 一覧は PC 版レイアウトを前提に解析しているため、`ShaloveClient.USER_AGENT` は PC の Chrome。
- Google Maven に届かない環境では `LS_JVM_ONLY=1 ./gradlew :core:test` で core のみ検証できる。app のビルドは CI（.github/workflows/android.yml）で確認する。
