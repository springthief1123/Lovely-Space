# Quiet Rose デザインシステム（共通実装基準）

この文書は Lovely Space の **継続的な UI 実装・レビュー基準**。Claude Code、Codex、ChatGPT、人間の開発者が同じルールで判断するために使う。

- 共通の作業ルール: [`AGENTS.md`](../AGENTS.md)
- リデザインの背景、機能・画面ごとの意図: [`quiet-rose-redesign.md`](quiet-rose-redesign.md)
- 実装の出発点: `app/src/main/kotlin/io/github/springthief1123/lovelyspace/ui/`

文書間で矛盾があれば、明示的な最新のユーザー指示と `AGENTS.md` を優先し、矛盾を PR に記録する。この文書はデザイン原則を定めるもので、現状の全画面が適合済みという意味ではない。値やコンポーネントの追加は、実装とこの文書を同じ PR で更新する。

## 1. 目指す見た目と境界

- **Quiet Rose**: 温かいニュートラルの背景、ローズ系アクセント、落ち着いた文字色、読みやすい余白、柔らかい角丸、控えめな境界線。
- 通常のカード、検索パネル、フォーム、ダイアログは **不透明な面**を基本とする。Glass / Haze は主にメインヘッダー、下部ナビゲーション、作成 FAB と既存の意図された浮遊操作に限定し、画面内へ無秩序に増やさない。
- **Material 3 のライブラリ利用は禁止しない**。入力、アクセシビリティ、フォーカス、アニメーションや操作部品の基盤として使用してよい。ただし、利用者に見える標準の色、形状、余白、Elevation、選択・無効状態が Quiet Rose から浮くまま採用しない。
- 既存のカスタム UI と同じ役割の標準 UI を新設しない。どうしても例外が必要なら、理由と既存との比較を PR に記す。
- UI の装飾より操作の意味、安全性、視認性、読み上げ・タッチ操作を優先する。

## 2. トークンと適用順序

出発点は次の実装。色や半径の数値は **コンポーネント側で再定義せず**、テーマ・共有トークンに集約する。

| 区分 | 現在の基準 | 運用ルール |
| --- | --- | --- |
| 配色 | `ui/theme/Theme.kt` の `LightColors` / `DarkColors`、`LocalLovelyColors` | ハードコード色・デフォルト Material 色を安易に混ぜず、ライト・ダークで役割ごとに検証 |
| 書体 | `LovelySpaceTheme` の `lovelyTypography` と文字サイズ設定 | 既存の title / body / label ロールを使い、拡大表示でも切れないこと |
| 間隔 | `ui/theme/DesignTokens.kt` の `LovelySpacing` | 既存の画面余白・セクション間隔を優先 |
| 形状 | `DesignTokens.kt` の `LovelyShapes` | 用途ごとの角丸・形状を共通ロールとして整理し、画面ごとの場当たり指定を増やさない |
| 状態色 | `MaterialTheme.colorScheme` と `LocalLovelyColors` | 通常・選択・押下・無効・エラー・破壊的操作を意味で使い分ける |
| ガラス | `ui/components/Glass.kt` | Haze が使えないときの代替表示も含め、既存の使用箇所の意図を保持 |

### 2.1 Material 3 の色ロール（2026-10-08 #72 で整備）

使用中の Material 3 は Compose BOM `2024.12.01`（material3 1.3 系）。`lightColorScheme` / `darkColorScheme` は未指定のロールに標準の紫系の既定値（baseline）を入れるため、`Theme.kt` では **標準部品が暗黙に使うロールも含めて全て明示**する。主な使い分け:

| ロール | 使う部品 | ライト | ダーク | 以前の表示（既定値） |
| --- | --- | --- | --- | --- |
| `surface` / `surfaceContainer` / `surfaceContainerLow` | カード、`DropdownMenu`（Container）、検索パネル・`RoomActionMenu`・`ModalBottomSheet`（Low） | `#FFFDFB` | `#242229` | Low がライト `#F7F2FA`（薄紫）、ダーク `#1D1B20` |
| `surfaceContainerHigh` | `AlertDialog`、強調した面 | `#F4E7EB` | `#302B34` | 指定済み・変更なし |
| `surfaceContainerHighest` | `Switch` のオフの軌道、塗りの入力欄 | `#EFE3E7` | `#38323C` | `#E6E0E9` / `#36343B` |
| `surfaceContainerLowest` / `surfaceDim` / `surfaceBright` | 現状直接は未使用。将来の標準部品用 | `#FFFFFF` / `#EDE6E4` / `#FFFDFB` | `#131217` / `#141318` / `#36313A` | 紫系の既定値 |
| `inverseSurface` / `inverseOnSurface` / `inversePrimary` | `Snackbar`（「元に戻す」などの操作文字は `inversePrimary`） | `#3A3439` / `#F5EFF2` / `#EFABC1` | `#F5EFF2` / `#302C31` / `#994B64` | 操作文字がライト `#D0BCFF`（薄紫） |
| `error` / `onError` / `errorContainer` / `onErrorContainer` | 破壊的操作、エラー表示 | `#B3261E` / 白 / `#F9DEDC` / `#410E0B` | `#F2B8B5` / `#601410` / `#8C1D18` / `#F9DEDC` | **表示は変えず**、ライブラリ更新で変わらないよう固定 |
| `onSecondary`、`tertiary` 系、`scrim` | 現状直接は未使用 | Quiet Rose の温かい中間色で明示 | 同左 | 紫系の既定値 |

運用:

- 浮かぶ面（検索パネル、メニュー、ボトムシート）は **通常のカードと同じ不透明な面**（`surface` = `surfaceContainerLow` = `surfaceContainer`）にし、境界線と影で背景と分ける。面の色で階層を作らない。
- 新しく色ロールを使うときは、そのロールが `Theme.kt` で明示されているか確認する。`QuietRoseThemeTest` が既定値への逆戻りと主な文字のコントラスト（4.5:1 以上）を検査する。
- `LocalLovelyColors`（性別・状態・Glass・区切り線）は Material のロールと別に持つ。性別や部屋の状態の色を Material のロールで代用しない。

### 2.2 形状（Shapes）

`DesignTokens.kt` の `LovelyShapes` が役割、`LovelyMaterialShapes` がそれを `MaterialTheme(shapes = …)` へ渡したもの。

| `LovelyShapes` | 半径 | 対応する Material の Shapes | 標準部品の例 | 既存の独自 UI |
| --- | --- | --- | --- | --- |
| `control` | 12dp | `extraSmall`（既定 4dp）、`small`（既定 8dp） | `OutlinedTextField`、`DropdownMenu`、`Snackbar`、`FilterChip` / `AssistChip` | 設定のアイコン枠、チャットの通知 |
| `panel` | 16dp | `medium`（既定 12dp） | `Card` | 部屋カード、Quiet の一覧パネル |
| `menu` | 20dp | `large`（既定 16dp） | FAB・ドロワー（現状未使用） | `RoomActionMenu` |
| `sheet` | 24dp | `extraLarge`（既定 28dp） | `AlertDialog`、`ModalBottomSheet` の上端 | 検索パネル・検索ポップオーバー |
| `bottomGlass` | 上 22dp | なし | なし | Glass の下部ナビ |

- ボタン、`Switch`、`SegmentedButton`、バッジは Material 3 では Shapes ではなく全丸なので、この設定では変わらない。全丸は Quiet Rose でも維持する。
- `RoomActionMenu`（20dp）、`SearchPanel`（24dp）、`QuietPanel`（18dp）、`SettingsScreen` のカード（18dp）、Glass ナビの各形状は #72 では変えていない。18dp の 2 か所は後続フェーズで `panel` か `sheet` へ寄せるか判断する。
- 新しいコードで角丸が必要なら `LovelyShapes.*` か `MaterialTheme.shapes.*` を使い、`RoundedCornerShape(…dp)` を画面に書き足さない。

### 2.3 後続フェーズで使う API

- メニュー（Phase B）: 面は `MaterialTheme.colorScheme.surfaceContainerLow`、形は `LovelyShapes.menu`、境界は `LocalLovelyColors.current.glassBorder`（ポップアップ）または `outlineVariant`。`RoomActionMenu` の `RoomActionPanel` と同じ組み合わせ。
- チップ・入力（Phase C）: 形は `LovelyShapes.control`（既定でも適用される）。選択色は `secondaryContainer` / `onSecondaryContainer`、境界は `outlineVariant`、フォーカスは `primary`。
- ダイアログ・シート（Phase D）: 形は `LovelyShapes.sheet`（既定でも適用される）。ダイアログの面は `surfaceContainerHigh`、シートの面は `surfaceContainerLow`。破壊的な確定ボタンの文字は `error`。

形状と余白の値を確定・変更する際は、既存の部屋カード、`RoomActionMenu`、検索パネル、設定カード、Glass ナビとの関係を比較する。**「見た目を新しくする」より「同じ役割は同じ見た目」を優先する**。

## 3. 共通コンポーネント優先ルール

採用・新設する順序:

1. 既存の Quiet / Lovely コンポーネントを再利用する。
2. 既存で足りなければ、Material 3 を **共通ラッパーで Quiet Rose にスタイリング**する。
3. Material 3 で必要な見た目や動作を実現できない場合のみ、Compose `Popup` / `Surface` などで独自実装する。Dismiss、Back、フォーカス、IME、スクロール、意味情報、画面外はみ出しを検証する。

参考実装:
- `ui/components/QuietComponents.kt`: `QuietPanel`、`QuietListPanel`、`QuietTabs`、`QuietOverflowMenu`。**現状の全実装が適合済みとは限らない**（例: `QuietTabs` は標準 `FilterChip`、`QuietOverflowMenu` は標準 `DropdownMenu` を直接使用）。
- `ui/rooms/RoomActionMenu.kt`: 部屋名・状態・アイコン付き操作を備えた独自メニュー。**部屋固有のヘッダーや配置を一般用途へ無理に転用せず**、必要ならパネルの外観と操作行だけを共通化する。
- `ui/components/Glass.kt` と `ui/shell/LovelyAppShell.kt`: メインシェルの Glass UI。
- `ui/main/SearchPanel.kt`: カスタム検索パネル／ポップオーバー。内部の標準チップや入力欄は今後の統一対象。
- `ui/settings/SettingsScreen.kt`: 設定画面のカードと見出し。シェル外画面とのヘッダー差分を比較する。

新しい共有コンポーネントの名前は原則 `Lovely...` または既存の `Quiet...` に合わせ、用途、必須パラメーター、選択・無効・エラー状態、プレビュー／テストの方針を明らかにする。名前だけを変えた標準 Material の薄いラッパーではなく、外観・振る舞いの一貫性を担保する。

## 4. コンポーネント別の視覚基準

| UI | Quiet Rose で確認する点 |
| --- | --- |
| メニュー／ポップアップ | 不透明で柔らかな面、統一した角丸・境界・影・余白。アイコン、破壊的操作、無効状態を揃える。アンカー位置、画面端、Back・外側タップ、項目数増加に対応 |
| チップ／タブ／フィルター | 選択／非選択で色・境界・角丸・高さが揃う。スクロールや改行、大きな文字、押下領域を確認 |
| テキスト入力／選択欄 | 輪郭、ラベル、ヒント、エラー、フォーカス色、IME、キーボード表示時のスクロールを統一。読み取り専用選択も例外にしない |
| ボタン／スイッチ／チェック | 主・副・危険操作の優先度、色、形、状態を統一。操作の意味とタッチ領域を保持 |
| ダイアログ／ボトムシート | 面、角丸、スクリーン幅、見出し、アクション配置を統一。キャンセル・破棄確認・Back・スクリーンリーダーを保持 |
| ヘッダー／通知／バッジ | メイン Glass ヘッダー、サブ画面、設定ページで文字・戻る操作・背景・アイコンの規則をそろえる。役割による構造差は許容 |
| カード／セクション | 面の透明度、境界線、内外の余白、テキスト階層を統一。Glass を通常カードへ拡大しない |

**全ての Material 3 呼び出しが問題ではない**。`MaterialTheme`、`Text`、`Surface` などは通常の構成要素。実際に差が見える部品と設定されていないデフォルト値を優先して監査する。

## 5. 既存の不統一を直す順序（2026-10-08 静的監査）

次の順序は、**修正の優先順位であり実装済みの宣言ではない**。着手前に最新コードと進行中 PR を再確認し、タスクは段階ごとの issue / PR に分ける。

1. **土台**（#72 で実施）: `ui/theme/Theme.kt`、`DesignTokens.kt`。未設定の Material 3 色ロール・Shapes を明示し、共有ロールを定義（2.1〜2.3）。既存画面の意図的な半径やコントラストは変えていない。
2. **メニュー**: `QuietComponents.kt` の `QuietOverflowMenu`、`ui/shell/LovelyAppShell.kt` の通知メニュー、`ui/settings/RoomListSettingsScreen.kt` と `ui/main/SearchPanel.kt` のドロップダウン。`RoomActionMenu.kt` の視覚言語を参考に、一般メニュー用の共通外観・動作を整備。
3. **チップ／検索入力**: `QuietTabs`、`SearchPanel.kt` の `FilterChip`、`OutlinedTextField`、`ExposedDropdownMenu`、`ui/main/RadarScreen.kt` / `RadarPlanEditor.kt` のチップ等。共通 styled control を導入し既存の検索条件ロジックは維持。
4. **ダイアログ・シート・トグル**: `ui/main/ProfileScreen.kt`、`RoomDetailsSheet.kt`、`RadarScreen.kt`、`RadarPlanEditor.kt`、`ui/rooms/GenrePicker.kt`、`ui/main/SavedSearchControls.kt` など。`AlertDialog`、`ModalBottomSheet`、`Switch`、`Checkbox` 等の外観を用途別に統一。
5. **ヘッダーと全画面レビュー**: `QuietTopBar` を使用するチャット・入室・部屋作成・公開ルーム、`SettingsPageHeader` と `LovelyTopBar` の階層的統一。画面役割の違いは残す。進行中のアプリロック PR 等が取り込まれたら新画面も対象に加える。

機能仕様（検索の入力時に通信しないこと、部屋・追跡の確認、通信制限、保存、ロック、通知、Back 操作）は **外観整理のために変えない**。

## 6. 完了条件とレビュー

UI を含む PR は次を報告する。

- **差分**: どの標準 Material 外観をどの共通ロールに合わせたか。残る例外と理由。
- **静的確認**: 変更範囲に新規の無調整 `DropdownMenu` / `FilterChip` / `AlertDialog` 等がないか。対象とした既存呼び出しが置換されたか。
- **挙動**: タップ、長押し、選択解除、破壊的操作、ダイアログ外タップ／Back、入力とIME、スクロール、状態保存が従来通りか。
- **表示**: ライト／ダーク、狭い画面、文字拡大、長い日本語、エラー／空／無効状態、画面端に開くメニュー、TalkBack とタッチ領域。
- **自動検証**: `./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` と関連テスト。CI が未完了なら完了としない。
- **実画面確認**: 可能なら before/after の同条件スクリーンショットを比較。エミュレーターや実機を使えない環境では視覚確認済みと書かず、実機確認事項を PR に残す。

UI 改修は **1テーマ / 1 PR を基本**とし、差分を小さく、挙動の変更や機能の追加と混在させない。レビューコメント・未解決スレッド・CI 失敗／警告を確認し、結果と未解決項目を明示する。
