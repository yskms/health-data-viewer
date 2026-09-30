# Health Data Viewer 要件定義・技術方針

-   作成日: 2026-09-16
-   更新日: 2026-09-24
-   ステータス: 企画・MVP要件検討（調整中）
-   対象: Android / Health Connect
-   進捗と決定ログ: [wbs.md](wbs.md)

> **Health Connectの中身を、ちゃんと見る。**

本書は第I部「要件」と第II部「技術方針」からなる。
第II部では「確定」と「PoCで検証する候補」を区別しており、PoC前の候補を確定事項として扱わない。

------------------------------------------------------------------------

# 第I部 要件

## 1. 背景

普段から複数の健康・フィットネスアプリをHealth Connectへ接続していると、Health Connect上で「同期された」ことは分かっても、次のことが把握しにくい。

-   実際にどの値が保存されているのか
-   複数アプリが同じデータを書いた場合、上書きなのか複数レコードなのか
-   どのアプリ／デバイスが書き込んだデータなのか
-   本当にHealth Connectへの書き込みが成功しているのか
-   過去数年〜10年以上のデータを、全期間で俯瞰するとどうなっているのか

Health Connect自体には個々のデータを確認する機能があるものの、長期推移の可視化やデータベースビューアとしての使い勝手は弱い。

本アプリは健康データを「入力・管理するアプリ」ではなく、**Health Connectに既に保存されているデータを読み取り、可視化・検証するためのアプリ**である。

## 2. Health Connectの仕様（調査結果）

### 2.1 生レコードは読み取り可能

Health Connect APIの `readRecords()` で、各データ型の個々のレコードを取得できる。
データソース（`DataOrigin`）やデバイス情報から、レコードの由来も表示できる。

``` text
2026/09/16 07:31  64.8 kg  Source: Withings
2026/09/16 07:32  64.8 kg  Source: Google Fit
```

### 2.2 「最後の値で単純上書き」ではない

Health Connectには複数アプリ由来のレコードが共存し得る。
「2つのアプリが同時刻付近の体重を書き込んだ」状態そのものをビューアで確認できる。

### 2.3 Raw RecordとAggregateは区別する必要がある

歩数などの累積型データについて、Googleは `readRecords()` の単純合計ではなく `aggregate()` の利用を推奨している。

-   Activity / Sleepでは、ユーザーが設定したデータソースの優先順位を考慮してAggregate APIが重複データを処理する
-   **公式資料上、この重複処理が適用されるのはActivity / Sleepのみ**。体重などのAggregate（平均・最小・最大）には、複数ソースの同一測定がそのまま含まれ得る
-   取得できる集計値はデータ型ごとに異なる（体重は平均・最小・最大、歩数は合計、心拍数は平均・最小・最大・測定数など）。Aggregateが提供されないデータ型もある

### 2.4 30日より古い履歴も取得可能

通常の読み取り権限では、権限付与時点より30日以上前の他アプリ由来データは読めない。
履歴読み取り権限 `READ_HEALTH_DATA_HISTORY` をユーザーに明示して要求すれば、Health Connectに存在する10年前のデータも対象にできる。

## 3. プロダクトコンセプト

### 3.1 立ち位置

健康管理アプリでも、データ入力アプリでも、Health Connect同期アプリでもない。
**Health Connectのデータを見るためのViewer / Explorer / Inspector** である。

### 3.2 基本思想

-   Read-only
-   Local-first
-   Account-less
-   Cloud-less（独自クラウドを持たない。広告SDKの通信はあるため「No network」ではない。§14）
-   Transparent
-   Long-term
-   Bilingual

### 3.3 名称

| 項目 | 名称 |
|---|---|
| 表示名（英語） | Health Data Viewer |
| 表示名（日本語） | 健康データビューア |
| applicationId | `com.yskms.healthdataviewer` |
| リポジトリ・ローカルフォルダ | `health-data-viewer` |

-   **アプリ名・ストアのタイトルに「Health Connect」を含めない。** Googleの商標ガイドラインは、Googleのブランドを自社の製品名に組み込むことを禁じている。また公式アプリと誤認させるタイトルはGoogle Playのなりすましポリシーに抵触し得る
-   Health Connectには、説明文の中で連携対象として触れる（例: 「Health Connectに保存された健康データを、読み取り専用で表示します」）
-   表記は必ず「Health Data Viewer」とし、「HealthViewer」「Health DataViewer」に縮めない（スペースなしの「HealthViewer」は既存製品が複数ある）
-   一般的・説明的な名称のため、検索上の独自性や商標としての強さは弱い。アイコン・スクリーンショットなどで独自性を出す

## 4. ターゲットユーザー

### Primary

-   複数の健康・フィットネスアプリをHealth Connectに接続しているAndroidユーザー
-   Fitbit / Pixel Watch / Withings / Garmin / Samsung Health等を組み合わせているユーザー
-   Health Connectへの同期結果を確認したいユーザー
-   数年〜10年以上の健康履歴を持つユーザー
-   「どのアプリが何を書いたのか」を確認したいユーザー

### Secondary

-   Health Connect連携アプリの開発者
-   健康データを自分で管理・分析したいユーザー
-   CSVに出すほどではないがRaw Dataを確認したいユーザー

## 5. Health Connect連携

### MUST

-   Health Connectからの読み取り
-   書き込み権限を一切要求しない
-   必要なデータ型だけユーザーが許可可能
-   履歴読み取り権限への対応
-   権限不足時の明確な案内
-   Health Connect未対応端末への案内
-   Android専用（iOSは対象外）。Health Connectが利用可能なAndroid 9（API 28）以上を対象とする

### MUST NOT

-   Health ConnectへのRecord追加・編集・削除
-   バックグラウンドでの不要な健康データ収集

## 6. ホーム画面

健康ダッシュボード型を基本とする。無理にDB Explorer風の一覧だけにしない。

### 期間タブ

今日／週／月／年を**すべて必須**とする。

### 表示候補

Activity、Weight、Body Fat、Heart Rate、HRV、Nutrition、Sleep、Blood Pressure、Blood Glucose、Oxygen Saturation、Exercise、Distance、Calories、その他対応データ。

### カード表示

各カードにはデータ型に適した情報を表示する。

``` text
体重                 心拍数               睡眠
64.8 kg              63 bpm 平均          7時間23分
前回比 -0.2 kg       46–109 bpm           Awake | REM | Light | Deep
──────╲___          ╲_╱╲__╱╲__
```

体重の比較は**前回比**（直前の測定との差）とする。

### データがない項目

設定で「データありのみ表示」「すべて表示」を切り替えられる。

## 7. 詳細画面

各データ型をタップすると詳細画面へ遷移する。

### 7.1 期間

| 必須 | 追加を検討 |
|---|---|
| 1W / 1M / 3M / 6M / 1Y / ALL / Custom | 3Y / 5Y |

**1Y（年）とALL（全期間）はMVP必須。これらが本アプリを作る最大の動機である。**
全履歴を1本のグラフとして俯瞰できる体験を、体重だけでなく複数の指標に広げる。

### 7.2 表示モード

グラフ／レコード／データソースの3ビューを基本とする。

**グラフ**: 長期トレンドを可視化する。

**レコード**: Health Connectに存在するRaw Recordを時系列で表示する。
アプリ独自の重複排除・重複判定は行わず、取得できたレコードをすべて表示する。
複数アプリが同じ測定を書き込んでいれば、それがそのまま見えることが本アプリの価値である。

``` text
2026年9月16日 07:31  64.8 kg  Withings
2026年9月16日 07:32  64.8 kg  Google Fit
```

**データソース**: データを書き込んだソースごとに集計する。

``` text
Withings        2,421 records
Google Fit        936 records
Fitbit            481 records
```

-   ソース一覧はAggregate結果の `dataOrigins` などから比較的軽量に取得できる見込み
-   **ソース別のレコード件数はAggregate APIから直接は得られず**、正確に出すにはRaw Recordを全件ページングして数える必要がある
-   心拍数など件数の多いデータ型では重いため、件数表示の仕様は性能検証後に確定する。候補:
    -   ソース名のみ表示し、件数は体重など軽いデータ型に限定
    -   「読み込み済み範囲の件数」として表示
    -   全件走査する場合は、進捗表示・キャンセル・キャッシュ戦略を用意

## 8. Raw / Aggregateの扱い

本アプリの重要仕様とする。

-   **Aggregate**: 「歩数」「睡眠」などの通常の健康指標はHealth ConnectのAggregate APIを尊重し、Health Connect側の優先順位・重複処理を反映した値を表示する
-   **Raw**: Health Connectに実際に存在しているレコードを、そのまま確認できるようにする
-   「集計値」と「保存されているレコード」が異なる場合があることを、専門用語に寄りすぎず説明する

### グラフの値

Raw画面とグラフは別の問題として扱う。例えば同日に次の3件がある場合、

``` text
07:31  64.8 kg  Withings
07:32  64.8 kg  Google Fit
21:00  65.2 kg  手動入力
```

Raw画面では3件とも表示するが、グラフに何をプロットするか（全点／日平均／日の最新値／Min–Max＋平均 など）は別途決める必要がある。

-   **グラフの集計方法はデータ型・表示期間ごとに定義する**
-   Health Connectが公式のAggregate Metricを提供する場合は原則として利用する
-   短期間はRawの全点、長期間は自動集約とする設計を基本候補としていたが、Weightについては1M（1か月）もPoC 1でbucket集計（日平均）を採用した。より短い期間（1Wなど）でRawの全点を使うかどうかはWBS 6.2で改めて決める
-   グラフには集計方法（例: 「月平均」）を画面上に明示する
-   具体的なルールはPoCで検証して確定する（§22）
-   **Weight（PoC 1で決定、D-027）**: 同日複数レコードはアプリ独自に平均／最新値を判定せず、公式のWeight Aggregate Metric（平均・最小・最大）を`aggregateGroupByPeriod()`でbucket集計して使う。グラフには平均を主系列、最小・最大を補助系列として表示する。ただしWEIGHT_AVGはCLAUDE.mdの「Health Connectで誤解しやすい点」通り複数ソースの同一測定をそのまま含む重複排除なしの平均であり、「日平均」という表示だけでは実態（例: 同じ値を記録した2ソースが2倍の重みを持つ）が伝わりにくい。注記を画面に出すかはWBS 6.2で検討する

## 9. 重複の扱い

**アプリ独自の重複判定は行わない。**

ユーザーの目的は「Health Connectに何が入っているか」を見ることなので、重複しているレコードも含めてRaw画面にすべて表示する。
「重複の可能性」のような独自判定・警告表示は持たない。
独自判定をHealth Connect公式の重複処理（Activity / SleepのAggregate）と誤認させるリスクも避けられる。

## 10. 長期グラフ

本アプリのコア機能。10年以上のデータでも全体像を確認できることを目標とする。

-   心拍数や歩数は数十万〜数百万Recordになり得るため、ALL表示でRaw Recordをそのまま全点描画しない
-   期間に応じて日・週・月・年へbucket集計して描画する
-   Raw Recordsは別画面でページング／遅延ロードする
-   日単位Raw・月平均・移動平均・Trendなどを選択できる余地はあるが、MVPでは**長期間でも読みやすい自動集約**を優先する

PoCで検証する論点:

-   ~~体重の同日複数レコードを平均にするか、最新値にするか~~ → PoC 1で決定（D-027、§8）
-   月・年bucketで値がない期間の表示
-   タイムゾーン変更・夏時間の扱い
-   Sleep Sessionが日付境界をまたぐ場合の扱い
-   ~~ALLの期間開始日（最古のレコード）を効率的に特定する方法~~ → PoC 1で確認済み（§22.4）

## 11. 横画面

**対応必須。** 特にグラフ詳細画面では、横向きにすると画面幅を最大限使った長期グラフを表示する。
単なるレスポンシブ対応ではなく、10年単位のデータを確認する本アプリと相性の良い機能として扱う。

## 12. テーマ

システム設定／ライト／ダークの3種類を必須とする。ダーク固定にはしない。
Material 3 / Dynamic Colorの採用は別途検討する。

## 13. 言語

MVPから日本語／Englishに対応する。端末言語に合わせて自動選択し、設定から変更可能にする。

## 14. Privacy / Security

健康データを扱うため、プロダクトの中心価値として扱う。

-   Health Connect Read-only（Write permissionなし）
-   アカウント不要、独自クラウド不要
-   健康データを外部サーバーへ送信しない
-   Analytics・クラッシュログにも健康データ・閲覧内容を送らない
-   必要以上の権限を要求しない

### スクリーンショット

-   スクリーンショットと画面録画は既定で禁止しない（`FLAG_SECURE` を一律には使わない）。健康グラフを利用者自身が保存・共有する用途が想定されるため
-   Recent Appsのプレビュー保護や、任意で有効にするスクリーンショット防止は、必要性を確認して将来検討する。Android 12以前ではプレビューだけを隠す手段がなく、スクリーンショットも同時に禁止される点に注意（[lessons.md](lessons.md) 5.2）
-   共有ボタンなどの共有機能はMVPの要件に含めない

### 広告を入れることによる訴求の制約

AdMob（§15）を導入するため、INTERNET権限が必要になり、広告SDKは広告IDなどを送信する。
したがって「No network」「完全に端末内だけ」「No cloud」という訴求は**できない**。訴求は次のように限定する。

> **Health Connectから読み取った健康データを、広告事業者や外部サーバーへ送信しません。**

### 健康データと広告の分離（必須）

Google Playのポリシーでは、Health Connectから得た健康データを広告プラットフォームへ転送したり、広告目的で利用したりすることは禁止されている。技術的に以下を保証する。

-   広告リクエストに健康指標・閲覧中の指標・データソース名を付加しない
-   画面名やAnalyticsイベントに健康データの内容を入れない
-   クラッシュログやbreadcrumbにRaw値を出さない
-   広告SDKから健康データ層を参照できない構造にする
-   Health Connectのデータを広告のパーソナライズに利用しない

パーソナライズ広告自体は許可する（UMPの同意に従う）。パーソナライズに使われるのは広告IDなどGoogle側のデータであり、健康データではない。

## 15. 広告・課金

### 広告

-   Google AdMobを使用する
-   形式は**画面下部のバナー広告のみ**。インタースティシャル・全画面・リワード広告は使用しない
-   広告は健康データと視覚的に明確に分離できる場所に限定する（ホーム下部を基本とする）
-   パーソナライズ広告は許可する（UMPの同意結果に従う）

広告を表示しない画面:

-   権限要求・同意説明画面
-   Rawレコード詳細
-   全画面グラフ
-   横画面
-   エラー画面
-   Health Connectにデータがない状態
-   課金処理画面

### 同意管理（UMP）

-   EEA・英国・スイスなど、同意が必要な地域では広告SDKの初期化・広告要求の前にUMPを処理する
-   設定画面からプライバシー設定（同意内容）を変更できるようにする
-   同意しない場合や広告を読み込めない場合も、アプリ本体は制限なく利用できる
-   地域ごとの規制メッセージはAdMob側で管理する

### 課金（広告削除）

-   広告削除は**非消費型の買い切り商品**を第一候補とする（サブスクリプションは将来検討）
-   MVPはクライアントのみで開始する。改ざん耐性、返金・取り消しの反映、購入状態の確実な管理を重視する必要が出たらサーバー検証を検討する

クライアントのみの場合も以下は必須。

-   `PENDING` と `PURCHASED` の区別
-   購入の復元
-   起動時などに `queryPurchasesAsync()` で購入状態を再確認する（返金・取り消しもここで反映される）
-   購入後3日以内のacknowledge（行わないと自動返金される）と、失敗時の再試行
-   広告削除状態をDataStoreだけで永続化しない（DataStoreはオフライン起動用のキャッシュとし、正はPlay側の購入状態）

## 16. 対応データ型

MVPではHealth Connectの全データ型に最初から対応する必要はない。

| 優先度 | データ型 |
|---|---|
| A | Weight、Body Fat、Steps、Distance、Heart Rate、Resting Heart Rate、HRV、Sleep、Exercise、Blood Pressure、Blood Glucose、Oxygen Saturation、Calories |
| B | Nutrition、Hydration、Respiratory Rate、Body Temperature、Height、VO2 Max、その他Body Measurements |
| 将来 | Cycle Tracking、Menstruation、Sexual Activity、Reproductive Health、Medical Records / FHIR関連 |

センシティブなデータ型は、権限要求・UI・Google Playポリシーを確認した上で段階的に対応する。

## 17. MVP対象外

健康データ入力、Health Connectへの書き込み、データ編集・削除、目標管理、ダイエット指導、AI健康診断、医療アドバイス、SNS、アカウント、独自クラウド同期、ウェアラブルとの直接接続、アプリ独自の重複判定、インタースティシャル・全画面広告、iOS版。

これらを捨てることで、**Viewerとしての単純さと安心感を守る。**

## 18. 画面構成

``` text
Home
├── 今日 / 週 / 月 / 年
└── 指標カード（Activity / Weight / Body Fat / Heart Rate / HRV / Sleep / ...）

Metric Detail
├── Chart
│   ├── 1W / 1M / 3M / 6M / 1Y / ALL / Custom
│   └── (3Y / 5Y: 検討)
├── Records
└── Sources

Settings
├── Health Connect permissions
├── Visible metrics
├── Theme（System / Light / Dark）
├── Language（System / 日本語 / English）
├── 広告を削除（購入 / 購入の復元）
├── プライバシー設定（広告の同意変更）
└── Privacy / About
```

## 19. UX方針

**Home = Health Dashboard、Detail = Health Connect Explorer** の二層構造にする。

-   ホーム: 「データベース管理ツール」っぽくしすぎず、開いた瞬間に自分の健康データが分かる体験を優先する
-   詳細: 一段深く入ると、Health Connectに何が保存されているか分かるInspector的な体験を提供する

これにより一般ユーザーにも使いやすく、Health Connectを深く確認したいユーザーにも価値を提供できる。

## 20. 成功条件

MVPは次の質問に明快に答えられること。

-   今日のHealth Connectには何が入っている？
-   この10年間で体重はどう変化した？
-   この値はどのアプリから来た？
-   同じ日に複数アプリが書いていない？
-   Health Connect上のRaw Recordは実際どうなっている？
-   集計値とRaw Dataはどう違う？

------------------------------------------------------------------------

# 第II部 技術方針

## 21. 技術スタック

| 領域 | 採用 | 状態 |
|---|---|---|
| プラットフォーム | Android専用（iOSは対象外） | 確定 |
| Android package（applicationId） | `com.yskms.healthdataviewer` | 確定 |
| 表示名 | 英語: Health Data Viewer／日本語: 健康データビューア（`app_name` を端末言語でローカライズ） | 確定 |
| 言語・UI | Kotlin / Jetpack Compose / Material 3 | 確定 |
| Health Connect | `androidx.health.connect:connect-client`（Google公式のJetpackライブラリ） | 確定 |
| minSdk | 28（Android 9） | 確定 |
| 非同期処理 | Coroutines / Flow | 確定 |
| 設定の保存 | DataStore（テーマ・言語・表示指標・購入状態のキャッシュ） | 確定 |
| 言語切替 | `AppCompatDelegate.setApplicationLocales()`（Android 13以降のアプリ別言語設定と連動） | 確定 |
| グラフ | Vico 3.x（`compose` + `compose-m3`）。全期間グラフの表現や操作に不足があればCompose Canvasで自作 | 確定（D-028。Pixel 11実機（数年分・千件規模の実データ）で横スクロール・複数系列描画・period切替を確認済み。10年規模・大量bucketでの操作感とピンチズームは未確認、§27） |
| 広告 | Google Mobile Ads SDK（AdMob）＋ UMP SDK | 確定 |
| 課金 | Google Play Billing Library | 確定 |
| DI | 当面はコンストラクタインジェクション。依存が増えたらHiltを導入 | 確定 |
| ローカルDB | 当面なし（Roomは導入しない） | PoC結果で判断 |

### Kotlinネイティブにした理由

-   iOSが不要なため、React Native / Flutterを選ぶ最大の利点（クロスプラットフォーム）がない
-   心拍数など数十万件規模のレコードを扱うため、React NativeのJSブリッジ経由だとシリアライズがボトルネックになる
-   Health ConnectはAPI追加が続いており（履歴読み取り権限、Synthetic Package Nameなど）、公式Kotlinライブラリなら即座に追従できる
-   AdMob・Billing・横画面・アプリ別言語設定がすべてAndroid標準の方法で実装できる

### minSdk 28 の理由

Health Connect SDKの最低APIは26だが、Health Connect自体が利用可能なのはAndroid 9（API 28）以上。
本アプリは全機能がHealth Connectの閲覧なので、Android 8でインストールできても非対応案内しか出せない。

## 22. データ取得方針

### 22.1 Raw と Aggregate

-   **Rawレコード画面**: `readRecords()` を `pageToken` でページング／遅延ロードする。アプリ独自の重複排除はしない
-   **グラフ**: 集計方法をデータ型・表示期間ごとに定義する。公式のAggregate Metricがあれば原則それを使う（`aggregateGroupByPeriod()` / `aggregateGroupByDuration()`）

### 22.2 データ型ごとの集計値（PoCで確定）

| データ型 | Aggregate Metric | 公式の重複処理 | 備考 |
|---|---|---|---|
| Weight | 平均・最小・最大 | なし | 同日複数レコードは独自判定せず、公式Aggregate Metric（平均・最小・最大）をbucket集計して使う（PoC 1で決定、D-027） |
| Steps | 合計 | あり（Activity） | 実データでは、単一ソースだけを指定したAggregateがそのソースの生データ単純合計と一致しないことがあった。同一ソース内で秒単位で実際に重なっている区間を時間按分している可能性が高いことを定量的に確認したが、正確な計算式は未確定（PoC 2、lessons.md 6.6、要検証） |
| Distance | 合計 | あり（Activity） | |
| Calories | 合計 | あり（Activity） | Total / Activeの区別あり |
| Heart Rate | 平均・最小・最大・測定数 | なし | 測定数はサンプル数であり、レコード数ではない |
| Resting Heart Rate | 平均・最小・最大 | なし | |
| Blood Pressure | 収縮期・拡張期の平均・最小・最大 | なし | |
| Sleep | 睡眠時間合計 | あり（Sleep） | 日付境界をまたぐSessionの扱いはPoC 4 |
| Exercise | 運動時間合計 | — | |
| Body Fat / Blood Glucose / SpO2 / HRV | 要確認 | — | Aggregateがなければ、読み込んだRawから自前で集約する |

グラフの基本候補:

-   短期間（1W〜1M程度）はRawの全点、という基本候補だったが、PoC 1ではWeightの1Mも含めてbucket集計（日平均、D-027）を採用した。1Wなど、より短い期間でRawの全点を使うかどうかはWBS 6.2で確定する（§8にも同旨を記載）
-   長期間（1Y・ALL）は日・週・月・年bucketに自動集約する
-   どの集計方法で描いているか（例: 「月平均」）を必ず画面に表示する

### 22.3 データソース

-   ソース一覧は `AggregationResult.dataOrigins` や `dataOriginFilter` 付きのAggregateで比較的軽量に取れる見込み
-   ソース別のレコード件数はAggregateでは取れない（§7.2）
-   アプリ名は `DataOrigin.packageName` から `PackageManager` で解決する。アンインストール済みアプリやSynthetic Package Nameの表示方法はPoCで確認する

### 22.4 全期間（ALL）の開始日

`readRecords()` を昇順（`ascendingOrder = true`）・`pageSize = 1`で呼べば、全件ページングせずに最古のレコードを1件だけ取得できる（`HealthConnectManager.findOldestWeightRecordTime()`として実装済み）。PoC 1ではエミュレータ（レコード0件）とPixel 11実機（実データ、複数年分）の両方でクラッシュなく動作し、実機では実際の最古レコードを正しく取得できることを確認した。取得した時刻はALLグラフの開始日（bucket集計の起点）としても実際に使っている（§8、D-027）。

### 22.5 その他の注意点

-   Health ConnectにはAPIのレート制限がある。大量ページングや全件走査は制限にかかる可能性がある
-   タイムゾーン変更・夏時間の扱い（bucket境界）はPoCで確認する
-   権限は必要なデータ型だけを要求する。書き込み権限は一切宣言しない

## 23. キャッシュ方針

-   MVPは**健康データをアプリ内に永続的に複製しない**方針で始める（Roomを使わない）
-   PoCでAggregateの応答性能やレート制限が問題になった場合のみ、キャッシュを検討する
-   導入する場合は、先に次を定義する: キャッシュ対象、削除タイミング、暗号化、権限取り消し時の破棄

## 24. 広告・課金の実装方針

要件は§14・§15。実装上は次を守る。

-   広告関連のコードは健康データ層に依存させない。広告モジュールからHealth Connectのリポジトリを参照できない構造にする
-   広告リクエストにキーワードターゲティング等で健康関連の情報を付加しない
-   UMPの同意取得は、広告SDKの初期化より前に行う
-   課金は非消費型の買い切り商品を1つ用意し、クライアントのみで実装する。広告削除のみで改ざん時の損害が小さいため

## 25. 技術上の重要課題

実装前にPoCで確認する。

1.  Health Connectの各Record TypeとAggregate APIの対応差
2.  `READ_HEALTH_DATA_HISTORY` の権限UX
3.  数十万件以上のHeart Rate Recordの読み取り性能
4.  All timeグラフのbucket戦略
5.  DataOriginから人間向けアプリ名をどう取得・表示するか
6.  2026年6月以降の端末内歩数におけるSynthetic Package Nameへの対応
7.  Sleep / Activityの優先順位とAggregate結果の説明
8.  Raw Record pagination
9.  横画面でのチャート操作
10. Google Play Health Connect permissions declaration / Data Safety対応（広告SDKの収集データ申告を含む）
11. データ型・表示期間ごとのグラフ集計ルール（§8）
12. ソース別レコード件数の取得コスト（§7.2）
13. ALLの開始日の特定、タイムゾーン・夏時間、日付境界をまたぐSleep Session（§10）
14. 健康データ層と広告SDKの分離構造、UMP・Play Billingの組み込み（§24）

## 26. PoCの順序

最初から全データ型を実装しない。次の4種類が成立すれば、アプリ全体の技術的な見通しが立つ。

| PoC | 対象 | 検証内容 |
|---|---|---|
| 1 | Weight | 10年規模のAll time、Raw Record、DataOrigin、横画面、History permission、同日複数レコードのグラフ上の扱い、ALLの開始日の特定、Vicoの評価 |
| 2 | Steps | RawとAggregateの差、複数Source、Health Connect公式の重複処理の結果、大量データ |
| 3 | Heart Rate | 大量Record、bucket集約、描画性能、ソース別件数の全件走査コスト、APIレート制限、ローカルキャッシュの要否 |
| 4 | Sleep | Session / Stage、Source priority、日付境界をまたぐSession、可視化 |

## 27. 未決事項

-   グラフ集計ルールの詳細（データ型×期間。Weightのbucket集計方針はPoC 1で決定済み、§8/§22.2）
-   ソース別件数の表示仕様
-   Vico採用後の残課題: 10年規模・大量bucketでの操作感（Pixel 11実機で数年分・千件規模までは確認済み）、ピンチズーム、`aggregateGroupByPeriod()`が値のないbucketを実際にどう返すか（lessons.md 7.2〜7.3）
-   Roomを導入するか
-   3Y / 5Y期間を追加するか
-   Body Fat / Blood Glucose / SpO2 / HRV のAggregate対応状況

## 28. 参考資料

-   Health Connect: Read raw data — https://developer.android.com/health-and-fitness/health-connect/read-data
-   Health Connect: Read aggregated data — https://developer.android.com/health-and-fitness/health-connect/aggregate-data
-   Health Connect: Architecture（対応環境） — https://developer.android.com/health-and-fitness/health-connect/architecture
-   Google Play: health permissionsのポリシー — https://support.google.com/googleplay/android-developer/answer/12991134
-   AdMob: 同意要件（EEA・英国・スイス） — https://support.google.com/admob/answer/13554020
-   AdMob: 同意の撤回導線 — https://support.google.com/admob/answer/10113915
-   Play Billing: 購入ライフサイクル（買い切り） — https://developer.android.com/google/play/billing/lifecycle/one-time
-   Play Billing: 組み込み手順 — https://developer.android.com/google/play/billing/integrate

------------------------------------------------------------------------

## Appendix: 最重要要件

``` text
[必須]
✓ Read-only
✓ 日本語 / English
✓ Light / Dark / System
✓ Portrait / Landscape
✓ 複数Health Connectデータ型
✓ 今日 / 週 / 月 / 年 Dashboard
✓ 1W / 1M / 3M / 6M / 1Y / ALL / Custom（3Y・5Yは検討）
✓ Raw Records（重複も含めてすべて表示）
✓ Data Source
✓ 長期履歴 / History Permission
✓ Aggregate / Rawの区別、グラフの集計方法を明示
✓ No account
✓ 健康データを外部送信しない
✓ Android 9（API 28）以上

[収益化]
✓ AdMob（画面下部バナーのみ、表示場所を限定）
✓ 広告削除: 非消費型の買い切り

[やらない]
× Health Connectへの書き込み・データ編集・削除
× アプリ独自の重複判定
× 広告への健康データ・閲覧内容の送信
× インタースティシャル・全画面広告
× 健康指導 / AI診断 / SNS
× 独自クラウド
× iOS
```
