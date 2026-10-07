# ロードマップ

最終更新: 2026-10-07

実装は 7 つのフェーズに分けて進めています。GitHub では親 issue [#26](https://github.com/springthief1123/Lovely-Space/issues/26) の下にフェーズ issue、その下にタスク issue をサブ issue として置いています。このファイルはその要約で、**最新の状態は issue が正**です。

## 現在地

| フェーズ | 状態 | issue | 主な PR |
| --- | --- | --- | --- |
| 0. 本家の画面・通信の採取と仕様確定 | 完了 | [#27](https://github.com/springthief1123/Lovely-Space/issues/27) | — |
| 1. 基盤（通信・一覧・テーマ） | 完了 | [#28](https://github.com/springthief1123/Lovely-Space/issues/28) | #1, #2 |
| 2. チャット（入室・会話・退室・部屋作成） | 主要部分は完了 | [#29](https://github.com/springthief1123/Lovely-Space/issues/29) | #3, #4, #5, #7, #8, #17 |
| 3. プロフィールプリセット | 完了 | [#35](https://github.com/springthief1123/Lovely-Space/issues/35) | #9 |
| 4. 検索・整理 | 主要部分は完了 | [#36](https://github.com/springthief1123/Lovely-Space/issues/36) | #10, #12, #13, #15, #16, #24 |
| 5. 巡回・通知・順番待ち | 前面での巡回まで完了 | [#39](https://github.com/springthief1123/Lovely-Space/issues/39) | #15, #18〜#23, #25（レビュー中） |
| 6. 仕上げとリリース | 未着手 | [#47](https://github.com/springthief1123/Lovely-Space/issues/47) | — |

## 残りのタスク

### フェーズ2: チャット

| issue | 内容 | ラベル | 依存 |
| --- | --- | --- | --- |
| [#30](https://github.com/springthief1123/Lovely-Space/issues/30) | 実機確認: ロボット確認つき入室・部屋作成・閉鎖・公開ルーム閲覧 | needs-device-test | — |
| [#31](https://github.com/springthief1123/Lovely-Space/issues/31) | 採取: 作成者側の ajax.php 応答（入室者あり・相手退室・部屋終了） | needs-capture | — |
| [#32](https://github.com/springthief1123/Lovely-Space/issues/32) | 作成者の操作: 相手を退室・発言クリア・待機メッセージ変更・公開設定 | | #31 があると確実 |
| [#33](https://github.com/springthief1123/Lovely-Space/issues/33) | 進行中の部屋への復帰（アプリ終了・再起動後） | | — |
| [#34](https://github.com/springthief1123/Lovely-Space/issues/34) | 画像の送信・会話ログ全文の表示 | | — |

### フェーズ4: 検索・整理

| issue | 内容 | ラベル | 依存 |
| --- | --- | --- | --- |
| [#37](https://github.com/springthief1123/Lovely-Space/issues/37) | 調査: 人を見分ける手がかり（一時ID・トリップ）の持続性 | needs-capture, research | — |
| [#38](https://github.com/springthief1123/Lovely-Space/issues/38) | 人単位のブロック・非表示（名前・トリップ） | | #37 |

### フェーズ5: 巡回・通知・順番待ち

| issue | 内容 | ラベル | 依存 |
| --- | --- | --- | --- |
| [#40](https://github.com/springthief1123/Lovely-Space/issues/40) | 一覧取得の一元化（ListSync）と背景実行の土台 | | — |
| [#41](https://github.com/springthief1123/Lovely-Space/issues/41) | 端末通知の基盤と通知ベルの接続 | | — |
| [#42](https://github.com/springthief1123/Lovely-Space/issues/42) | 定期巡回の背景実行と一致の通知 | | #40, #41 |
| [#43](https://github.com/springthief1123/Lovely-Space/issues/43) | 入室者あり通知（自分の部屋で待機中） | | #41, #31, #33 |
| [#44](https://github.com/springthief1123/Lovely-Space/issues/44) | 調査: 本家の Web プッシュ（UnifiedPush）で入室通知を受け取れるか | research | — |
| [#45](https://github.com/springthief1123/Lovely-Space/issues/45) | 順番待ち（満室の部屋に空きが出たら知らせる） | | #40, #41 |
| [#46](https://github.com/springthief1123/Lovely-Space/issues/46) | 背景機能の設定と電池最適化の案内 | | #42, #43, #45 |

### フェーズ6: 仕上げとリリース

| issue | 内容 | ラベル | 依存 |
| --- | --- | --- | --- |
| [#48](https://github.com/springthief1123/Lovely-Space/issues/48) | 本家機能の対応表と残りの画面（会員ログイン・年齢認証・他サブドメイン） | needs-capture | — |
| [#49](https://github.com/springthief1123/Lovely-Space/issues/49) | エラー処理とアクセス頻度の最終確認 | | フェーズ5 |
| [#50](https://github.com/springthief1123/Lovely-Space/issues/50) | 署名付き APK と GitHub Releases での配布 | | — |
| [#51](https://github.com/springthief1123/Lovely-Space/issues/51) | 実機で 1 週間の運用確認 | needs-device-test | #50, フェーズ5 |

## おすすめの着手順

依存の少ないものから並べています。`needs-capture` と `needs-device-test` は Yuya の作業が必要なので、開発と並行して依頼します。

1. #30（実機確認）と #31・#37（採取）を Yuya に依頼する
2. #33 進行中の部屋への復帰（#43 の前提）
3. #40 一覧取得の一元化 → #41 通知の基盤
4. #42 定期巡回の背景実行、#45 順番待ち
5. #43 入室者あり通知（#44 の調査結果で方式を決める）
6. #32 作成者の操作、#38 人単位のブロック、#34 画像とログ
7. #46 背景機能の設定 → フェーズ6

## 変えない設計の前提

- アプリが自分で行う本家への通信はすべて `ShaloveClient` を通し、最小間隔 3 秒・一覧キャッシュ 20 秒を短くしない。機能ごとに別々に一覧を取りに行かず、取得結果を共有する。例外は利用者が操作する `SiteWebView`（ロボット確認つきの入室・部屋作成など）で、通常のブラウザとして直接通信し、この間隔制限はかからない。背景機能から WebView を自動で読み込ませない。
- 背景での周期実行は WorkManager の最短 15 分を下限にし、利用者が明示的にオンにした機能だけを動かす。
- ロボット確認は自動で突破しない。順番待ちは「空きの通知」と「入力済みの入室画面」までを自動化する。
- データは端末内だけに保存し、自前のサーバーは持たない。

## 記録

- 2026-10-03: 実装プランを作成。Kotlin + Jetpack Compose を採用。
- 2026-10-04: フェーズ0〜2 の主要部分を実装（#1〜#5）。実機で入室・会話・退室を確認。
- 2026-10-05〜06: プリセット・検索・お気に入り・非表示・レーダー（前面での巡回）と画面の再設計を実装（#6〜#24）。
- 2026-10-07: ロードマップを親子 issue（#26〜#51）に整理。
