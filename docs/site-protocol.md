# 本家サイトの画面と通信

ラブルーム（`chat.shalove.net` ほか）の画面構造と通信の仕様です。2026-10-04 に Yuya が PC の Chrome で保存したページ・HAR を解析してまとめました。採取データそのものは他の利用者の名前・発言・`pwd`・Cookie を含むため、リポジトリには入れていません（このファイルの例も名前や ID は架空です）。

本家の仕様について新しく分かったことはこのファイルに追記してください。未確認の点は「未確認」「推測」と明記します。

## ドメインとジャンル

ジャンルは 3 つのサブドメインに分かれている（Cookie は `.shalove.net` で共有が必要になる可能性あり。未確認）。

| サブドメイン | ジャンル（genre_key / 部屋数） |
|---|---|
| chat.shalove.net | zenkoku 全国, hokkaido 114, tohoku 84, kanto 1054, chubu 394, kinki 574, chugoku 206, kyushu 207, gazo 画像 151 |
| 2shot.chat.shalove.net | imacha 833, chah 1189, tel 167, kikon 680, jukunen 298, sm 802, feti 193, pocha 163, dose 同性 210 |
| lr.chat.shalove.net | talk 333, nonadult 18, cosplay 52, game 22, wait 待ち合わせ 122 |

- 一覧 URL: `https://<sub>/g/<genre_key>/`
- ジャンル横の `(N)` が部屋数、`総部屋数 7893` も表示される
- ジャンル見出しに `待機中 18` / `満室 3` の件数

## 部屋一覧の行（`table.rooms` > `tr.roomcol`）

広告を挟んで `table.rooms` が複数に分割される。行は 6 列:

1. **利用状況**: `td.wait` / `td.fill`。`span` 内が `待機中` / `公開待機中` / `満室`。`font.room_time` に経過時間 `HH:MM:SS`
2. **入室**: GET フォーム。`room_id`（数値）と `genre_key` を hidden で持つ
   - 待機中 → `action=/PreEnterRoom`、ボタン `入室`
   - 満室・公開 → `action=/PublicRoom`、ボタン `覗く`（観覧）
   - 満室・非公開 → `/PreEnterRoom`、ボタン `秘密`（disabled）
3. **名前**（満室時は `span.heartmark` 「2ショットチャット中」に置き換わる）
4. **性**: `span.male` / `span.female`（満室時は `span.hearttext`）
5. **年**: 数値または `&nbsp;`
6. **メッセージ**: 先頭に地域タグ `<font class="small" color="#339933">大阪</font>` が付くことがある。満室時は広告リンクが表示され、本来の募集文は `span.hearttext[style=display:none]` に隠れている

→ `room_id` は数値 ID だが、**サブドメイン（host）ごとの値で、部屋が閉じた後に別の部屋で再利用されることがある**。ブックマーク・順番待ちのキーは `host + room_id` にし、使う前に一覧で見える名前・性別・年齢が登録時と一致するかを照合し直す（app の `RoomPreference`、core の `RoomIdentityEvidence.REUSED` が実装例）。

## サイト側の絞り込み（GET パラメータ）

`/g/<genre>/?vsex=&vpref=&vyears=&vnonpub=&vwait=&srchname=&srchmsg=`

| パラメータ | 値 |
|---|---|
| vsex | 1=男性, 2=女性 |
| vpref | 1〜47 都道府県, 48=海外 |
| vyears | -19, 20-29, 30-39, 40-49, 50-59, 60- |
| vnonpub | 1=非公開, 2=公開 |
| vwait | 1=待機中, 2=満室 |
| srchname / srchmsg | 名前・メッセージの部分一致（単語1つ） |

→ アプリの高度な検索は「サイト側パラメータで一次絞り込み → 端末側で AND/OR・除外ワード・並び替え」の二段構えにすると取得量を減らせる。

## その他のエンドポイント

- 部屋作成: `GET /PreMakeRoom?genre_key=<g>&kct=<unix秒>`
- 再入室: `GET /?submit=submit_rein2shot&genre_key=<g>`
- 不正通報: `POST /ReportBadRoom` `action=ReportBadRoom&room_id=..&ajax=1`（jsonp 版あり、charset Shift_JIS）
- 会員: `/member/`, 登録 `/PreRegistMail`, 画像 `/PhotoUp/`, ブロックリスト説明 `/information/editbans`
- プッシュ通知: `ipush.chat.shalove.net`（サイト自体が入室者通知を Web Push で提供している形跡あり）
- 2026/06/09 更新: 一覧でブロック中の相手の部屋を「ブロック中」と表示（会員のブロック機能がサイト側にもある）

## 文字コード・JS

- サイト自身の JS (`/js/common.js`, `/js/genre.js`) は Shift_JIS。HTML も Shift_JIS と推定（取得ツールが UTF-8 に変換して保存しているため未確定）
- 一覧ページに自動更新（meta refresh / タイマー）は見当たらない。「自動更新」はチャット画面側の機能と思われる
- 一覧は **1 ページ 60 部屋**でページ分割される: `/g/<genre>/pageID/<n>/`（関東は 18 ページ、`待機中 1005 / 満室 57`）。ページャは `<p align="center">` 内の `<a title="… 次のページ">`
- 絞り込み中（PC 版レイアウト）は 1 ページ目が `/g/<genre>/?vsex=1`（本家の絞り込みフォーム `#shibori` の GET）、2 ページ目以降は**条件がパスに入る**: `/g/hokkaido/vsex/1/pageID/2/`。ページャには全ページへのリンクが並ぶ。複数の条件を組み合わせたときのパスの並び順は未確認なので、アプリは一覧に載っていたページャのリンクを優先して使う（`ShaloveClient.fetchRoomList`）
- 別の形の一覧 `/?action=GenreK&genre_key=..&vsex=..&vpref=..&vyears=..&vnonpub=..&vwait=..&srchname=..&srchmsg=` もある（`<hr>` 区切りの軽いレイアウト、`table.rooms` ではない）。ページ送りは `pagernext` の `&bfrid=<room_id>` カーソルで、件数は `22-25/25部屋` の形。2026-10-09 の採取で、関東・女性・待機中は 25〜26 部屋、`vsex`・`vwait` は 2 ページ目でも保たれることを確認した。1 ページの件数は一定でない（21 件・26 件・60 件を観測）。アプリはこの形を使わず PC 版の `/g/` を使う
- 本家側で絞ると取得量が大きく減る（関東 1000 部屋超 → 女性 67 部屋 → 女性・待機中 25 部屋前後）

## 部屋作成フォーム（`/PreMakeRoom`）

`POST /PreMakeRoom?genre_key=<g>&kct=<unix秒>`

| name | 内容 |
|---|---|
| uniqid, pwd | ページごとに発行される hidden トークン（毎回取り直しが必要） |
| genre_key, shotact=makeroom, submit=submit | 固定 |
| name | 名前（`太郎#秘密` でトリップ `太郎◆xxxx` になる） |
| sex | 1=男, 2=女 |
| years | 空=秘密, 18〜 |
| email | 任意。入室時のメール通知用 |
| prefecture | 空=秘密, 1〜47, 48=海外 |
| message | 待機メッセージ。半角 500 文字まで、一覧では半角 350 文字以降省略 |
| is_public | 未ログインでは 1（公開）固定。非公開は会員＋年齢確認が必要 |

**ロボット除け認証あり**: Cloudflare Turnstile（失敗時は hCaptcha）を通らないと送信ボタンが有効にならない。
→ アプリの部屋作成は WebView 上で行い、プリセットで項目を自動入力したうえで、認証だけユーザーに通してもらう形になる。認証の回避はしない。

## 会員ログイン

`POST /` `action=MemberLogin&uniqid=..&submit=memberlogin&submit_use_login_cookie=1&mail=..&pass=..&use_login_cookie=1&login_back_uri=..`
（ログイン画面にも `sess_csn` などのトークンあり。ログインも WebView で行い Cookie を共有するのが安全）

## サイト側の既存機能（アプリとの重複に注意）

- **Web プッシュでの入室通知**: 部屋作成後の画面で「Webプッシュで通知する」を選ぶと、Android Chrome でも入室通知が届く。アプリ（WebView）では Web プッシュは使えないため、アプリ側の入室通知は自室の状態確認で実装する
- **メール通知**: 部屋作成時にメールアドレスを入れると入室をメールで通知
- **ブロックリスト**: 会員ログイン時、チャット中の相手をブロック（入室拒否）でき、一覧に「ブロック中」と表示される。アプリの「ブロック・非表示」は端末内の表示フィルタとして別に持つ
- **トリップ**: 名前の `#秘密` が `◆xxxx` に変換される。ブロック・ブックマークを人単位で行う際、トリップがあれば同名の別人と区別できる
- **再入室**: 接続が切れても同じルームに再入室できる場合がある
- 公開ルーム = 登録不要・覗き可能・出会い目的禁止。非公開ルーム = 会員＋年齢確認（証明画像提出）が必要。「通話」など非公開専用ジャンルもある

## 入室前画面（`/PreEnterRoom`）

- 表示: `GET /PreEnterRoom?room_id=..&genre_key=..&kct=..&back_uri=..`
- 待機者の情報: 「〇〇 (34) さん 女 (Android 一時ID xxxxx) が待機中です」+ 募集文。**一時ID**（端末種別 + 5 文字）が出るので、人単位のブロック・ブックマークの手がかりになる（どれくらいの期間同じ値が続くかは要確認）
- 入室: `POST /PreEnterRoom`

| name | 内容 |
|---|---|
| room_id, genre_key | 部屋 |
| pwd | この画面の表示時点で発行済みの本人用トークン（入室後の `2shot.php?room_id=..&pwd=..` で使うものと思われる） |
| shotact | `entry` |
| name, sex(1/2), years(空=秘密, 18〜) | 自分のプロフィール |
| cf-turnstile-response / g-recaptcha-response | ロボット除け認証の結果。**認証欄が表示されたときだけ必要**（前回の利用から間が空いたときなどに出る。出ないときは送らずに入室できる） |

- 「2人入室した状態で満室ロック」「作成者は閉鎖できる、後から入った側は自分だけ退室できる」
- 公開ルームの会話は `/PublicRoom?room_id=..&genre_key=..` で誰でも覗ける

**影響**: 入室にも認証が入ることがあるので、「満室の部屋に空きが出たら即座に入室する順番待ち」は完全自動にはできない。空きを検知したら通知し、名前などを入力済みの入室画面を開いて、認証（多くは自動で通る Turnstile）だけ通してもらう形にする。

## 待機画面・チャット画面（`/2shot.php`）

- URL: `https://<sub>/2shot.php?room_id=<id>&pwd=<32桁hex>`。**`pwd` がその部屋での本人用トークン**（部屋作成・入室ごとに発行）。URL を知っていれば再入室できる
- 待機画面とチャット画面は同じページ。上部に「入室者が来たら、アラートでお知らせします♪」、`#reloadspc` に「自動更新まで N 秒」、`#room_limit` に利用制限時間（作成直後で約 7 時間）

### 新着取得と発言（`/js/2shot.js`）

- `GET ajax.php?live=1&room_id=..&pwd=..&fromsize=<N>&kct=<ms>` で新着を取得。`live=1` は「常時自動更新」（長めに接続を保つ方式の可能性。要確認）
- 発言は同じ URL に `POST`、本文 `chat=<UTF-8 URL エンコード>`（Content-Type: application/x-www-form-urlencoded; charset=UTF-8）。発言すると同じレスポンスで新着も返る
- **レスポンスは JavaScript 文字列**で、ページ側が `eval` する。設定される変数:
  - `loglines` 新着ログ（HTML 断片の配列。空要素はログ全消去の合図）
  - `aj_alert_in` **入室者あり**（true でアラート「入室者あり!」）
  - `die_msg` 部屋の終了理由（あれば部屋終了）
  - `information` お知らせ欄
  - `rom_count`, `not_show_rom` ROM（覗き）人数
  - `aj_guest_off`, `aj_can_ban_guest` 相手退室・退室させられるか
  - `aj_is_public`, `dolive`
- アプリでは `eval` せず、この形式をパーサで読む（実レスポンスの見本が必要）
- 間隔: 待機中は約 30 秒（noscript の説明より）。サーバー負荷 `loadAverage` が 100 を超えると間隔を延ばす。発言は 1.5 秒以内の連投不可、負荷が高いと 5 秒
- ログの 1 行は `div.hello > table > tr > td(名前) td.usume(>) td(本文 + small.choiusu(時刻))`

### 入室者側のチャット画面

- URL は待機者と同じ `2shot.php?room_id=..&pwd=..`。pwd は入室前画面で発行されたものとは別の値（入室時に再発行される可能性）
- `#reloadspc` が「常時自動更新」になる（2人そろうと `ajax.php?live=1` の常時接続モードと思われる）
- フォーム: 発言（`chat`）、**退室** `shotact=bye`、画像投稿。閉鎖・発言クリア・相手退室・公開設定・待機メッセージ変更は作成者のみ
- 待機メッセージは画面下部に表示のみ
- ROM 人数 `#rom_count_s`「ROM 0人」
- ログは新しい順に `#chatarea` の先頭へ追加。システム発言は名前欄が「おしらせ」で、例:
  - 「**〇〇**(28)(男)さん(Android 一時ID xxxxx)が入室しましたので、このチャットルームをロックしました。」
  - 「**〇〇**(28)(女)さん(iPhone 一時ID xxxxx)が新規部屋を作成して待機中です。」
  - 「ルーム作成者（待機者）によって発言がクリアされました。」
- 女性の名前は `<font class="chatcolor_f" color="#aa0088">`、時刻は `small.choiusu` に `(HH:MM:SS)`

### ページ内の変数と更新間隔（2shot.php のインライン script）

- `gRoomVars = { room_id, pwd, max_displine: 15, mugonLimit(無言制限の残り秒), roomLimit(部屋の残り秒), fromsize: 690, isFilledRoom: 1, loadAverage: 434, _auth: "guest"|"owner", _is_public: 1, _wp_endpoint(Web プッシュ購読先), ipush* }`
  - `fromsize` はログの読み出し位置（バイト数と思われる）。コールバック側は更新していないので、**ajax の応答本文が `gRoomVars.fromsize=…` も書き換えている**と推測（未確認）
- 更新間隔: `gReloadCountTimeIni = 5`（秒）、`gMaxCountTime = 600`。新着が無いたびに間隔が延び、2人そろった部屋（`isFilledRoom`）で新着があると 2 秒後に再取得。待機中で `loadAverage > 100` のときは延び幅を `ceil(loadAverage/100)` 秒にする
- `live=1` は自動更新時だけ付き、発言（POST）時は付かない
- 初回表示の `#chatarea` には直近 15 行（新しい順）がサーバー側で描画済み。入室者（guest）のフォームは発言・退室・画像投稿のみ

### ajax.php の実際の応答（Chrome DevTools の HAR、入室者側）

- 応答は `Content-Type: application/xml; charset=UTF-8`（**UTF-8**。ページ本体の Shift_JIS とは別）。1 行目に `<?xml ... ?>`、その後は JavaScript の代入文が並ぶだけ:
  ```
  gRoomVars.fromsize = size = 654;
  gMaxCountTime = 600;
  gRoomVars.mugonLimit = 28799;
  gRoomVars.roomLimit = 28761;
  gRoomVars.isFilledRoom = 1;
  gRoomVars.loadAverage = 368;
  gReloadCountTimeIni = 5;loglines = new Array('<div class="hello"\x3e…<\/div\x3e');not_show_rom = 1;aj_is_public=1;dolive=1;information='';
  ```
  - `fromsize` は次回リクエストに渡す読み出し位置（応答ごとに増える）。`loglines` は単一引用符の JS 文字列の配列で、`\x3e`（>）と `\/` がエスケープされている。1 行の HTML 構造は 2shot.php の `#chatarea` と同じ
  - `dolive=1` は live ポーリング時のみ。発言（POST）の応答には付かない
- **`live=1` は長時間ポーリング**: 新着が出るまでサーバーが応答を保留する（実測 14 秒・9 秒）。新着が来たら即応答し、ページはすぐ次の live リクエストを出す。→ アプリも「応答を待つ GET を 1 本だけ常に出しておく」形にすれば、ブラウザと同じ通信頻度でほぼリアルタイムに受信できる
- 発言: `POST ajax.php?&room_id=..&pwd=..&fromsize=<現在値>&kct=<ms>`、本文 `chat=<UTF-8 URL エンコード>`。応答は自分の発言を含む新着（即時、46ms）
- 退室: `POST /2shot.php` 本文 `room_id, pwd, genre_key, shotact=bye` → 200・空本文
- **入室**: `POST /PreEnterRoom` 本文 `room_id, pwd(入室前画面のトークン), genre_key, shotact=entry, name, sex(1=男), years(空可)` → 302 で `/2shot.php?room_id=..&pwd=<新しい本人用 pwd>&redirect=1`
  - **今回の入室ではロボット除け認証のトークンが送られておらず、Cloudflare への通信も無かった**。以前の入室前画面には Turnstile 欄があったので、条件次第で省略される。**Yuya 確認（2026-10-04）: 認証は毎回ではなく、前回使用から間が空いたときなどに入る**。アプリは入室前画面に認証欄があるときだけ WebView で認証を通してもらい、無ければそのまま入室する
- 未取得: 作成者側の `aj_alert_in`（入室者あり）、`die_msg`（部屋終了）、`aj_guest_off`（相手退室）の実例

### 待機画面のフォーム（すべて `POST /2shot.php`、hidden に room_id, pwd, genre_key）

| 操作 | 追加パラメータ |
|---|---|
| 発言（JS 無効時） | `uniqid`, `chat` |
| 部屋を閉鎖 | `shotact=close` |
| 発言クリア | `clearchatlog=1` |
| 相手を退室 | `shotact=ban`（相手入室時のみ表示） |
| 非公開に変更 | `set_is_public=0`（参加者が年齢確認済みの場合のみ） |
| 待機メッセージ変更 | `chat=` 空 + `message` |
| ROM 表示切替 | GET `not_show_rom=0/1` |
| 画像投稿 | `POST https://chat.shalove.net/?action=Distributed_LocationImgUp`（multipart, `upfile`, `return_host`）。メール `<pwd>@img.shalove.net` 宛添付でも可 |
| ログ全文 | GET `/RoomLog?room_id=..&pwd=..` |

- `2shot.php` は `charset=UTF-8` のページで、フォームも UTF-8 で送られる（入室前画面の名前も UTF-8 で送信されていた）。
- フォームは JS で横取りされず、普通に送信されて部屋の画面が再表示される。アプリは応答が部屋の画面なら読み直し、公開設定・待機メッセージ・「相手を退室」の有無を確かめる（`ShaloveClient.banGuest` など）。
- 「相手を退室」（`form#form_ban`）は相手がいないあいだ `display:none` で置かれている。`2shot.js` は新着取得の応答で `aj_alert_in`（入室者あり）なら表示し、`aj_guest_off` のときは `aj_can_ban_guest` に従って表示・非表示を切り替える。
- 発言クリアのフォームは `form#form_clear`。`2shot.js` には `ajax.php?…&shotact=clearchatlog` で送る経路もあるが、採取したページからは呼ばれていない。
- 非公開の部屋で「公開に変更」（`set_is_public=1` と推定）が出るかは未確認。アプリは `set_is_public` の入力欄があるときだけ切り替えを出す。

### 入室通知（Web プッシュ）

- `webpush.js`: Service Worker `/sw.js` を登録し、購読情報（endpoint, p256dh, auth）を `GET /Jsapi_SubscribeWebPush?endpoint=..&publicKey=..&authToken=..&room_id=..&pwd=..` で登録。会員は `/Ipush_SetQuick` もある
- VAPID 公開鍵はページ内に固定値で記載
- **アプリへの応用案**: Android アプリでも UnifiedPush（ntfy など）を使えば標準の Web プッシュを受け取れる。購読情報をこの API に渡せば、**アプリがポーリングせずにサーバー側から入室通知を受け取れる**可能性がある（サイト公式機能の利用なので規約面でも有利）。実現性は要検証

## まだ必要な取得

| 内容 | issue |
| --- | --- |
| `ajax.php` の作成者側の応答（入室者あり・相手退室・部屋終了） | #31 |
| 一時 ID・トリップの持続性 | #37 |
| 会員ログイン・年齢認証、`2shot.` / `lr.` サブドメインの一覧、スマホ UA でのレイアウト差 | #48 |
| 画像投稿の実際の通信 | #34 |

`/PublicRoom`（覗き画面）は #25 で解析を追加中。
