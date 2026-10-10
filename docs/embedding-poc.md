# EmbeddingGemma 2 日本語意味検索PoC（debug限定）

状態: **実装候補の初期検証 / 実機検証待ち**。タスク [Issue #104](https://github.com/springthief1123/Lovely-Space/issues/104)。全体の判断は [Issue #101](https://github.com/springthief1123/Lovely-Space/issues/101)。

## スコープ

- Androidの**debugビルドにだけ** LiteRT-LM 0.18.0 と検証用ランチャー「AI意味検索検証」を追加する。releaseにはSDK、検証Activity、モデルは含まれない。
- アプリ内に本家から取得した部屋・相手の文章を渡さず、UIに埋め込んだ**架空の募集文5件**を評価する。プライベートなCookie、`pwd`、認証データ、通信、通知、WorkManager、DBのコードは変更しない。
- CPUで推論し、希望文を変更してコサイン類似度順に並べ、初期化と埋め込み計算の時間を表示する。スコアは確率（％）ではない。

## 実機（Galaxy Z Fold5）での試し方

1. CIのdebug APKを導入する。ホーム画面の**「AI意味検索検証」**を起動（通常のLovely Spaceとは別アイコン）。デバッグ版のみ。
2. 公式LiteRT Communityの[EmbeddingGemma 2 Text 270Mモデル](https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/tree/main)から、**`embeddinggemma-2-text-270m.litertlm`**（標準CPU/GPU向け、約165MB）をGalaxy Z Fold5に保存する。Qualcomm/NPU等の個別最適化版を最初は使わない。モデルの配布元・ライセンスは同ページで確認。
3. 端末で「モデルを選んで取り込む」→上記のファイルを選択。ファイルの読み込みはAndroidのストレージアクセスフレームワークを使用し、アプリ専用の内部ストレージへコピーする。オリジナルは変更しない。モデルの選択・取得は利用者の操作のみ。
4. 初期の希望文「落ち着いて、長めに雑談できる部屋」を確認し、「端末内で意味検索」を押す。初回は時間がかかる。結果が表示されたら順位・スコア・所要時間を記録する。
5. 希望文を「5分だけ暇つぶししたい」などに変えて再実行し、結果を比較する。
6. **機内モード**でも同じ処理が成功するか確認する。CPU実行時の電池消費・発熱・操作性を記録する。
7. 検証後は「モデルを削除する」でアプリ内コピーを削除できる（元のダウンロードファイルは残る）。

## 実装の境界

- `app/src/debug/` に検証Activityを閉じ込め、SDKは `debugRuntimeOnly` で導入する。LiteRT-LM 0.18.0のKotlin 2.4メタデータがアプリのKotlin 2.1コンパイラと非互換のため、PoCでは**debug専用のリフレクション・アダプター**経由でCPU実行する。本番版にこの方式を持ち込まず、正式導入時にはツールチェーン更新または別モジュール化をレビューする。モデルの取得・更新を自動化しない。
- `app/src/main/.../ai/SemanticRanking.kt` はベクトルの比較だけを行う純粋なKotlinコード。正規化されていない出力でもコサイン類似度を計算する。ゼロベクトル、NaN、長さ不一致は拒否し、同点では入力順を維持する。
- モデルはアプリ内部の `filesDir/ai-poc/` に置く。Androidアプリのバックアップは既存のマニフェストで無効。任意の文字列をモデル入力へ渡す前に正規化し、公式の検索クエリ／検索結果プレフィックスを付ける。
- ランタイムの重い初期化とモデルファイルのコピーは `Dispatchers.IO`。エラーやモデル未選択を表示し、既存の検索・通信に影響させない。
- 推論は都度モデルを開いて閉じる**初期PoCの設計**。連続利用時のキャッシュ、GPU/NPU、100件のバッチ処理、キャンセル時のネイティブ処理の中断、機種互換は後続の評価で対応する。

## 記録する項目

| 検証項目 | 結果（実機で記入） |
| --- | --- |
| Android/One UIバージョン | 未確認 |
| ファイル取り込み | 未確認 |
| CPU初期化時間 | 未測定 |
| 5件の埋め込み所要時間 | 未測定 |
| 予想と異なる日本語の順位例 | 未測定 |
| 機内モード動作 | 未確認 |
| RAMピーク・電池・発熱 | 未測定 |
| UI操作性・文字拡大・TalkBack | 未確認 |

参照: [LiteRT-LM Kotlin Embedding API](https://developers.google.com/edge/litert-lm/embedding_models)、[Android API](https://developers.google.com/edge/litert-lm/android)、[モデル一覧](https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/tree/main)。

**このPoCがビルドできたことは、Galaxy Z Fold5で正常に推論できることの保証ではない。** 実機の結果を得てから本番UIへの統合可否を判断する。
