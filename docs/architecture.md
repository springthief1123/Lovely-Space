# 設計

## スタックの選択

Kotlin + Jetpack Compose のネイティブ Android アプリです（2026-10-03 決定）。

- アプリの価値の半分は、アプリを閉じていても動く機能（入室者あり通知・順番待ち・定期巡回）にある。Android の背景処理の制約（Doze、WorkManager の最短 15 分、フォアグラウンドサービスの種別宣言）を最も素直に扱えるのがネイティブ。
- iOS は背景処理の制約が厳しく同じ機能をほぼ実現できないため、クロスプラットフォームの利点は小さい。
- PWA はブラウザの CORS 制約で本家を直接取得できず、中継サーバーが必要になるため不採用。自前のサーバーは持たない。

## モジュール

```
core  (Kotlin/JVM)  本家への通信・HTML 解析・チャットのセッション・検索条件の判定
  └─ JUnit + MockWebServer でテスト。Android に依存しない
app   (Android)     画面（Compose）・端末内の保存（Room / DataStore）・WebView・Cookie 共有
  └─ Robolectric のユニットテスト、Lint
```

サイトに依存する処理（URL・フォーム項目・HTML の構造・JS 応答の形式）は `core` に置きます。本家の HTML が変わったときに直す場所を 1 か所にするためです。

**現在の例外**: WebView で本家の画面を入力済みにする処理は app にあります。フォーム名・項目名・対象パスが `ui/entry/EntryScreen.kt`（`entry` フォーム、`/PreEnterRoom`）と `ui/create/CreateRoomScreen.kt`（`makeroom` フォーム、`/PreMakeRoom`）に、入力スクリプトが `ui/web/SiteWebView.kt`（`prefillFormScript`）に直接書かれています。本家の入室・部屋作成フォームが変わったときは core に加えてここも直してください。

## core の主なクラス

| クラス | 役割 |
| --- | --- |
| `ShaloveClient` | 本家への全通信の入口。リクエスト同士の最小間隔（設定で選ぶ。既定 3 秒・下限 1 秒）、一覧の URL ごとのキャッシュ（一覧を自動で取り直す間隔のうち最も短いもの。20 秒以下）。間隔は `RefreshPacing` を通信のたびに読む、絞り込み中の 2 ページ目以降は一覧に載っていたページャのリンクで取得、利用者の操作（部屋・公開ルーム・入室前画面を開く、入室、作成者の操作）を順番待ちの一覧の取得より先に通す順番（間隔は同じ）、文字コード判定、`pwd` を伏せたエラー（`HttpStatusException`）。入室・退室・閉鎖・作成者の操作・チャット画面の取得もここ |
| `SharedSiteCookieJar` / `SiteCookieStore` | OkHttp の Cookie を Android の `CookieManager`（WebView と同じ保管先）に委ねる |
| `Genres.kt`（`Genre`, `RoomQuery`） | ジャンル（3 つのサブドメインに分かれる）と、一覧 URL・サイト側の絞り込みパラメータの組み立て |
| `RoomListParser` | 一覧ページ（PC 版レイアウト）の解析。`Room`, `RoomListPage` を返す |
| `RoomSearch`（`RoomSearchCriteria`） | 端末側の絞り込み（複数語 AND/OR、除外、性別、年齢範囲、地域、公開・待機）と並び替え |
| `RoomTracking`（`RoomIdentityEvidence`） | 同じ `room_id` の部屋が同じ人のものかを、一覧で見える名前・性別・年齢で照合する（本人確認ではない） |
| `SitePages`, `Prefectures` | 部屋作成・入室前・公開ルームの URL、都道府県コード |
| `chat/ChatParsers` | `2shot.php`（待機・チャット画面）の初回表示と、ログ 1 行の解析 |
| `chat/JsAssignments` | `ajax.php` の応答（JavaScript の代入文の並び）を `eval` せずに読む |
| `chat/PollSchedule` | 本家の `2shot.js` と同じ新着取得の間隔計算 |
| `chat/ChatSession` | 新着取得（`live=1` の長時間ポーリングを常に 1 本）と発言（1.5 秒以上空ける）。送信・取得・間隔の状態を 1 つの Mutex で直列化 |

## app の構成

```
LovelySpaceApp           アプリ全体で 1 つずつの ShaloveClient・リポジトリ・DB
AppNavHost / LovelyAppShell
  ├─ 見つける  ui/rooms (RoomListScreen), ui/main (SearchScreen)
  ├─ レーダー  ui/main (RadarScreen …)        前面での巡回・部屋の追跡・名前の候補監視
  ├─ 保存      ui/main (FavoritesScreen)      お気に入りの部屋と保存した検索条件
  └─ マイルーム ui/main (ProfileScreen)        プリセット管理・部屋作成・設定
  ├─ ui/entry   入室（プロフィール入力。ロボット確認が必要なら WebView）
  ├─ ui/create  部屋作成（入力 → 本家の作成画面を WebView で開いて入力済みにする）
  ├─ ui/chat    会話（ActiveRooms が持つ ChatController が ChatSession を使う）
  ├─ ui/web     WebView（SiteWebView）、公開ルームの閲覧
  ├─ ui/settings 表示・部屋一覧・非表示の管理
  └─ lock        アプリロック（MainActivity が全画面の上にロック画面をかぶせる）
```

| パッケージ | 内容 |
| --- | --- |
| `data` | Room DB（`PresetDatabase`、現在 version 4）。プロフィール / 待機メッセージのプリセット、検索条件、部屋のお気に入り・非表示、レーダーの状態（`LocalState` に JSON で保存）。`RoomListRepository` は発見・巡回・追跡が同じ一覧の取得結果を共有するための層 |
| `lock` | アプリロック。設定とパスコード・パターンのハッシュ（塩つき PBKDF2）は起動直後に同期で読めるよう SharedPreferences（`app_lock`）に置く。`MainActivity` の `onStop` / `onStart` で背景にいた時間を測り、選んだ時間を過ぎていればロックする |
| `settings` | DataStore の設定（テーマ・文字サイズ・起動カテゴリなど）、WebView の Cookie 保管 |
| `ui/components`, `ui/theme` | デザインの部品とトークン（Quiet Rose。[quiet-rose-redesign.md](quiet-rose-redesign.md)） |

## 主な流れ

### 一覧の取得

画面 → ViewModel → `RoomListRepository` → `ShaloveClient.fetchRoomList(RoomQuery)` → `RoomListParser`。同じ URL はキャッシュの間は再取得しません。キャッシュからの再表示は、レーダーでは「新しい観測」に数えません。

### 入室

1. `ShaloveClient.openEntry` で入室前画面（`/PreEnterRoom`）を取得し、フォームとロボット確認の有無を読む。
2. 確認が無ければ、アプリから `enter`（`POST /PreEnterRoom`）。302 の行き先 `2shot.php?room_id=..&pwd=..` から部屋の鍵 `pwd` を得る。
3. 確認があれば `SiteWebView` で本家の入室前画面を開き、名前などを入力済みにする。利用者が確認を通して送信すると、WebView が `2shot.php` へ移る瞬間をアプリが捕まえ、会話画面へ引き継ぐ。
4. `pwd` を含む `ChatRoomRef` は `ActiveRooms`（メモリ上）に置き、ナビゲーションの route には一時 ID だけを載せる。

### 会話

`ChatController`（部屋ごとに 1 つ、`ActiveRooms` が持つ）が `ShaloveClient.openChat` で初回表示を読み、`ChatSession.updates()` で新着を受け取り続けます。会話画面の ← は部屋に残ったまま一覧へ戻り、アプリが前面にある間は一覧を見ていても取得を続けます（背景に回ったら止め、前面に戻ったら再開）。退室・部屋を閉じる・別の部屋への入室で接続を止めます。発言は `ChatSession.send`。退室は `shotact=bye`、作成者の閉鎖は `shotact=close`（失敗したら画面に留まってやり直せる）。作成者は右上のメニューから、相手を退室させる・発言クリア・待機メッセージの変更・公開設定の切り替えができる（`ShaloveClient.banGuest` / `clearLog` / `changeWaitingMessage` / `setPublic`）。

### 部屋作成

アプリで名前・年齢・地域・待機メッセージを入力（プリセットから選べる）→ 本家の `/PreMakeRoom` を WebView で開き、スクリプトでフォームを埋める → 利用者がロボット確認と作成ボタンを押す → `2shot.php` への遷移を捕まえて会話画面へ。

## テスト

- core: `core/src/test`。本家の HTML・JS 応答は `core/src/test/resources/fixtures` の合成データ（構造は本物と同じ、名前や本文は架空）。通信は MockWebServer。
- app: `app/src/test`（Robolectric）。Room の移行、プリセットの初回取り込み、検索、会話画面の表示状態など。
- 実機: CI の Artifacts のデバッグ APK を入れて確認する。本家との一連の操作は自動テストでは保証できない。
