# Health Data Viewer 要件定義・技術方針

-   作成日: 2026-09-16
-   更新日: 2026-09-30
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
-   **件数表示の仕様はWBS 6.4で確定した（D-009、D-037）**: Weight/Steps/Sleepは全件走査した正確なレコード件数を表示する。走査中もレコード自体は保持せず、ソースごとの件数だけを集計しながら読み進める（D-031(3)で候補に挙がっていた方式）。この3データ型とHeart Rateを分ける基準は**レコード件数の多寡ではない**（Stepsは全期間で数十万件規模になり得る＝Weight/Sleepより2桁近く多い）。実際の基準は「1レコードが大きなサンプル配列を持たず、SDK内部の変換コストが低いか」で、Heart Rateだけがこれに該当しない（コードレビューで誤った分類基準の記述を指摘され訂正、lessons.md 6.7/6.19）
-   **Heart Rateは正確なソース別Record件数を表示しない**（D-036）。代わりにAggregateのソース別サンプル数（`MEASUREMENTS_COUNT`）を「サンプル数」と明記して表示する。採用条件だった実機確認（全件走査が安全な直近7日・30日の範囲でRawから数えたサンプル数と比較）は一致を確認できたため、採用を確定した（D-037）。ただし多年規模の全件走査はOutOfMemoryErrorのリスクがあるため行っておらず、この一致がより長い範囲でも成り立つかは未検証のまま残る。Records画面（Rawのページング表示）は対象外で、Heart Rateでも重複を含めてすべて表示する

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
-   短期間はRawの全点、長期間は自動集約とする設計を基本候補としていたが、Weightについては1M（1か月）もPoC 1でbucket集計（日平均）を採用した。~~より短い期間（1Wなど）でRawの全点を使うかどうかはWBS 6.2で改めて決める~~ → WBS 6.2で解決済み（D-034）。1Wを含む全期間でbucket集計を使い、Rawの全点は使わない
-   グラフには集計方法（例: 「月平均」）を画面上に明示する
-   具体的なルールはPoCで検証して確定する（§22）
-   **Weight（PoC 1で決定、D-027）**: 同日複数レコードはアプリ独自に平均／最新値を判定せず、公式のWeight Aggregate Metric（平均・最小・最大）を`aggregateGroupByPeriod()`でbucket集計して使う。グラフには平均を主系列、最小・最大を補助系列として表示する。ただしWEIGHT_AVGはCLAUDE.mdの「Health Connectで誤解しやすい点」通り複数ソースの同一測定をそのまま含む重複排除なしの平均であり、「日平均」という表示だけでは実態（例: 同じ値を記録した2ソースが2倍の重みを持つ）が伝わりにくい。**WBS 6.2では画面上に注記を追加しておらず、この点は引き続き未対応のまま残っている**（§27に追記）

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
-   ~~Sleep Sessionが日付境界をまたぐ場合の扱い~~ → PoC 4で確認済み（D-032、lessons.md 6.9）。日付境界をまたぐSessionは、両日のbucketに実時間の重なりに応じて按分される（丸ごと1つのbucketに計上されることはない。0時より前の時間が4分〜86.5分と幅のある6事例で確認）ため、アプリ独自の按分ロジックは不要。ただし按分の正確な計算根拠（Health Connectが実際にどう計算しているか）は未確認のまま残る。複数ソースが同じ夜を重ねて記録した場合の扱い（Source priority）は別の未解決事項として§27に残す（単一ソースのデータでしか検証していないため）
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
| ローカルDB | 当面なし（Roomは導入しない） | 暫定確定（D-031。APIレート制限の検証結果（WBS 4.2、未完了）次第で再検討） |

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
| Distance | 合計 | あり（Activity） | Activity系に分類され公式重複処理が適用されるとされているのは公式ドキュメント・本表の分類根拠のみで、Distance自体で複数ソース重複時の挙動を実機確認してはいない（Steps PoC 2で確認したのはStepsRecordのみ。WBS 6.10、要検証、D-042(8)） |
| Calories | 合計 | あり（Activity） | 「Total / Activeの区別あり」は、1つのRecord型内の2フィールドではなく、ActiveCaloriesBurnedRecord / TotalCaloriesBurnedRecordという2つの独立したRecord型を指す。どちらを優先するかをアプリが判断しないよう、2つの独立したデータ型（カード・詳細画面）として実装した（WBS 6.10、D-043(1)）。重複処理についてもDistanceと同様、公式ドキュメント上の分類のみで、ActiveCaloriesBurnedRecord/TotalCaloriesBurnedRecordそれぞれで複数ソース重複時の挙動を実機確認してはいない（要検証、D-043）。さらにTotalCaloriesBurnedRecord.ENERGY_TOTALは、レコードが1件もない期間でもnullを返さないことが分かっており、ホーム画面カードはhasAnyRecord()による実レコード確認で対応済み（詳細画面のChartは別の理由で未対応、§27参照） |
| Heart Rate | 平均・最小・最大・測定数 | なし | 測定数はサンプル数であり、レコード数ではない |
| Resting Heart Rate | 平均・最小・最大 | なし | |
| Blood Pressure | 収縮期・拡張期の平均・最小・最大 | なし | グラフでは収縮期・拡張期それぞれの平均のみを使う。最小・最大は取得しない（凡例のない既存チャート設計で、2指標分の最小・最大まで含めた6系列は判読できなくなるため。WBS 6.10、D-045(3)） |
| Sleep | 睡眠時間合計（`SLEEP_DURATION_TOTAL`。Session区間の単純合計ではなく、Stageのうち覚醒（`STAGE_TYPE_AWAKE`）区間を除いた時間の合計） | あり（Sleep） | 日付境界をまたぐSessionは、両日のbucketに実時間の重なりに応じて按分される（丸ごと1つのbucketに計上されることはない）。按分の正確な計算根拠、および複数ソースが同じ夜を重ねて記録した場合の重複処理（Source priority）は未確認（単一ソースのみで確認、PoC 4、D-032、lessons.md 6.9、§27） |
| Exercise | 運動時間合計（`EXERCISE_DURATION_TOTAL`、公式AggregateMetric<Duration>。Sleepの`SLEEP_DURATION_TOTAL`と同じ構造） | — | 重複処理の分類（Steps/Distance/Caloriesのような「あり（Activity）」、Sleepのような「あり（Sleep）」）に相当する公式ドキュメント上の根拠が見当たらないため「—」のまま要検証とする（WBS 6.10、D-051）。`exerciseType`・`segments`・`laps`・`exerciseRouteResult`（GPSルート。`HealthPermission`の権限文字列ではなく、`androidx.health.action.REQUEST_EXERCISE_ROUTE`というIntent経由でセッションごとに個別の同意を得る仕組み）・`title`/`notes`・`plannedExerciseSessionId`も持つが、表示するのは開始〜終了時刻・運動時間・`exerciseType`・ソースのみ（D-051）。`ExerciseSegment`の`EXERCISE_SEGMENT_TYPE_PAUSE`/`REST`区間が`EXERCISE_DURATION_TOTAL`の計算から除かれているか（Sleepの覚醒区間除外、D-032と同様の可能性）は未確認（要検証、§27） |
| Body Fat | 平均・最小・最大（自前集計） | なし | 公式AggregateMetricが存在しない（javap逆コンパイルで確認）。読み込んだRawレコードから、Weightと同じ平均・最小・最大をアプリ側でbucket集計する（WBS 6.10、D-046(3)、lessons.md 6.27） |
| HRV | 平均・最小・最大（自前集計） | なし | Health Connectが公開するHRV関連のRecord型は`HeartRateVariabilityRmssdRecord`（RMSSD）1つのみ。公式AggregateMetricが存在しない（javap逆コンパイルで確認）。Body Fatと同じ自前bucket集計を使う（WBS 6.10、D-047、lessons.md 6.28） |
| Oxygen Saturation（SpO2） | 平均・最小・最大（自前集計） | なし | 公式AggregateMetricが存在しない（javap逆コンパイルで確認。Blood Glucoseも同じ確認で存在しないことが分かっている）。読み込んだRawレコードから、Body Fat/HRVと同じ平均・最小・最大をアプリ側でbucket集計する（WBS 6.10、D-049、lessons.md 6.30） |
| Blood Glucose | 平均・最小・最大（自前集計、mg/dL） | なし | 公式AggregateMetricが存在しない（javap逆コンパイルで確認済み、D-049）。Body Fat/HRV/SpO2と同じ自前bucket集計で実装した（WBS 6.10、D-050）。specimenSource/mealType/relationToMealという分類用の値もあるが、他の単一値データ型と同じ方針で今回は表示しない（D-050(1)） |

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
-   PoC 3（Heart Rate）で、トリガー条件のうちAggregate応答性能の低下とRaw全件読み込みのクラッシュが実際に発生したが、Roomを導入せず、Raw一覧のページング表示化とグラフのbucket粒度調整で解消する方針とした（D-031）。もう一つのトリガー条件であるAPIレート制限はPoC 3では未検証のまま残った（WBS 4.2）。専用PoCとしての検証は打ち切り、WBS 6.3・6.4のMVP実装・実機確認にあわせて確認する方針とした。レート制限が問題になった場合はこの方針を再検討する

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

PoC 1〜4（Weight・Steps・Heart Rate・Sleep）で解決した事項（Weightのbucket集計方針＝D-027、Stepsの重複処理の実データ裏付け、Heart Rateの描画性能、Sleepの日合計睡眠時間がAWAKE区間を除いた値であること＝D-032）は解消済みのため、以下からは除いている。ただしSleepについては、日付境界をまたぐSessionの正確な按分方法と、複数ソース時の重複処理（Source priority）は未解決のまま下記に残している（PoC 4は単一ソースのデータのみで検証したため）。

### MVP実装時に確認する

-   タイムゾーン変更・夏時間の扱い（bucket境界。§10/§22.5。D-027・D-032で、確認できたデータが単一タイムゾーンでの記録のみだったため引き続き要検証と分かっている）
-   Sleepの複数ソース重複時の扱い（Source priority）。PoC 4（WBS 5.1）は単一ソースの実データのみで検証しており、複数ソースが同じ夜（またはその一部）を重ねて記録した場合に`SLEEP_DURATION_TOTAL`がどう重複処理するかは未確認（lessons.md 6.9）
-   Sleepの日付境界をまたぐSessionの按分の正確な計算根拠。両日のbucketに実時間の重なりに応じて按分されることはPoC 4で確認できたが（lessons.md 6.9）、Health Connectが実際にどう計算しているか（Stage単位で計算しているか、Session全体を一様分布とみなす近似的な計算か）は、UI表示の分単位切り捨てを読み取る検証方法の精度の限界で断定できていない
-   Sleepで「今日」の範囲を問い合わせた場合、前日夜に始まり日付境界をまたぐSessionが含まれるかどうか。PoC 4（`poc/SleepRawRecordsScreen`、WBS 6.3で削除）の検証時点では対象となる実例が手元になく未検証のまま残っている。ホーム画面のSleep「今日」カード（D-033、Aggregateのbucket境界に依存）に関わる疑問のため引き続き残す
-   ~~Sleepの月bucket（ALL表示）の集計方法~~ → WBS 6.2で解決済み（D-034）。月合計ではなく、実カバー日数で割った「1日あたり平均」にする（Stepsの週/月bucketにも同じ規則を適用）
-   ~~データ型×期間のグラフ集計ルールの残り（Stepsのグラフ集計ルール自体が未定）~~ → WBS 6.2でStepsの詳細画面グラフを新規実装し解決済み（D-034）。ただしHeart Rateの週bucket化（D-031の仮説）は実機で効果が限定的と判明した（次項参照）
-   ~~短期間（1Wなど）のグラフでRawの全点を使うかどうか~~ → WBS 6.2で解決済み（D-034）。1Wを含む全期間でHealth Connect公式Aggregateのbucket集計を使い、独自のRaw全点描画は行わない
-   **継続的にバックグラウンド記録される高密度データ型（Heart Rate等）で、3M以上の期間のグラフが数十秒規模の待ち時間になり得る問題**（WBS 6.2で新たに判明）。bucket粒度を粗くする対策（週bucket、D-031の仮説）は実機で効果が限定的と判明し（週bucketでも1年56.9秒・全期間96.4秒、同条件のStepsは1秒未満〜5秒）、所要時間の主要因はbucket数ではなく問い合わせ範囲の実データ量だと分かった（lessons.md 6.12）。読み込み中はクラッシュ・フリーズしないため現状は許容しているが、体感の改善が必要になった場合はRoom導入（D-031）を再検討する
-   **HRVの自前bucket集計（`readHrvAggregates()`、Body Fatの`readBodyFatAggregates()`と同じロジック）の高頻度記録時の性能は、Pixel 11実機（実データ、「Health」ソース、2025/07/15〜2026/10/04の約15ヶ月間に25,717件、1日平均約57件）で確認した結果、**懸念した規模では問題にならなかった**（WBS 6.10、コードレビュー指摘への追加確認）。一時的な計測ログ（`SystemClock.elapsedRealtime()`、確認後に削除済み）で測定したところ、Chart全期間の全件走査（`readAllRecords()`、25,717件・26ページ）は約3.2秒、Sourcesタブの全件走査（`countBySource()`、同じ25,717件・26ページ）は約3.5秒で、どちらも高速だった（全件メモリ保持・bucket割り当ての線形探索という2つの理論上のコストを持つChart側も、この規模では体感できる遅さにならなかった）。**当初、UI操作のポーリング中にSourcesタブが「2〜3分」かかったように見えたが、これは計測に使った`adb shell screencap`の連続実行がアプリの実際の性能に干渉した結果の誤測定だったと判明し、訂正した**（詳細はlessons.md 6.29）。より大規模なデータ（Heart Rateの継続記録相当、数十万件規模）でChart・Sources双方の性能がどうなるかは未確認のまま残る。その規模で問題が顕在化した場合、bucket割り当ての線形探索（`bucketStarts.indexOfLast { ... }`）を二分探索または日数/月数の直接計算に置き換える案がある（Body Fat側にも同じ改善が効く）
-   **TotalCaloriesBurnedRecordのALL期間グラフも、上記Heart Rateと同種の数十秒規模の待ち時間になり得る**（WBS 6.10、コードレビューで新たに判明、独立した既知の問題として記録）。ALL期間（MONTH bucket、約43個、約17万件・2ソース・3.5年分）のaggregateGroupByPeriod()単独で約77秒かかることを実機計測で確認した（Heart RateのALL期間96秒に匹敵）。15分間隔という記録頻度自体はHeart Rateの継続記録よりは疎だが、Heart Rateと同じく所要時間の主要因はbucket数ではなく問い合わせ範囲の実データ量と見られ、Total Caloriesも約17万件規模のため当てはまる。計測は実行のたびに大きくばらつき、安定した値ではなかった（詳細はlessons.md 6.26参照）。StepsとDistanceも同程度の実データ規模（約23万件）だが、この2つはALL期間の読み込みが数秒〜5秒程度に収まっており遅延は確認されていない（lessons.md 6.19、WBS 6.10）。Total Caloriesだけこの規模の遅延が出る理由は未調査（要検証）。対処方針は上記Heart Rateの項目と合わせて今後検討する
-   ~~ソース別件数の表示仕様~~ → WBS 6.4で解決済み（D-009、D-036、D-037）。Weight/Steps/Sleepは全件走査した正確なレコード件数、Heart RateはAggregateのソース別サンプル数（`MEASUREMENTS_COUNT`）。実機確認（直近7日・30日の範囲でRawから数えたサンプル数と比較、Fitbit単一ソースのみ）でも一致を確認した。複数ソースが同じ期間を重ねて記録した場合にも成り立つかは未確認のまま残る（lessons.md 6.18）
-   **Sourcesタブを開いたまま画面回転すると、全件走査（Stepsで実測約65秒）がやり直しになる**（WBS 6.4、2回目のコードレビュー指摘）。`tab`（選択中のタブ）は`rememberSaveable`で回転をまたいで保持されるが、Sourcesタブの読み込み結果（`screen/detail/DetailCommon.rememberLazyTabResult()`が保持する状態）は`remember`のため、画面回転によるActivity再生成で失われる。これはChart/Recordsタブも含め「画面回転時は状態を保持せず再取得する」という既存の前例（D-033、D-035(4)）に意図的に合わせた設計だが、Sourcesタブの所要時間が約65秒と判明した今、この前例をそのまま踏襲してよいかは再検討の余地がある。結果自体は小さい（ソースの数だけの名前と件数の組）ため、`rememberSaveable`やより上位の状態保持層で持たせる余地はあるが、これを行うとSources タブだけ同じ画面内の他タブと挙動が変わる非対称が生じる。優先度が上がった場合にWBS 6.5以降か、リリース前（WBS 8章）に再検討する
-   **横画面の全画面グラフ（WBS 6.5、D-038）により、「横向きにする」操作自体がChartタブのAggregate再取得・最古レコード再取得のトリガーを兼ねるようになった**。画面回転時に状態を保持せず再取得する設計自体は既存の前例（D-033、D-035(4)）を踏襲しただけで新規の問題ではないが、上記のSourcesタブの全件走査（約65秒）や、継続記録型データ型（Heart Rate）の3M以上・ALL（数十秒規模、本§27の別項目）を表示中に横画面へ回転すると、その待ち時間が毎回発生する形で顕在化しやすくなった（コードレビュー指摘）。解決にはViewModel等、設定変更をまたいで結果を保持する仕組みの導入が必要だが、4つのDetail画面すべてに影響する規模のアーキテクチャ変更になるためWBS 6.5の範囲では見送り、既知のコストとして受容した（D-038）
-   **レート制限（またはその他のIllegalStateException分類のエラー）が実際に発生した場合、現状はアプリがクラッシュする**（WBS 6.4、2回目のコードレビュー指摘）。`readWithHistoryFallback()`がRemoteException/IOException/SecurityException以外を意図的に捕まえない設計（D-035(7)、lessons.md 6.20）は、PoC・開発中の検知手段としては妥当だが、リリース後にユーザーの手元でレート制限（または`ERROR_DATA_SYNC_IN_PROGRESS`等）が発生した場合はクラッシュとして体験される。WBS 6.4でレート制限に最もかかりやすい処理（Stepsの約300回の連続`readRecords()`呼び出し）が追加されたため、この項目の重要度も上がっている。リリース前（WBS 8章）に、`IllegalStateException`を捕まえてエラー表示にするか、クラッシュさせたまま検知手段として残すかを決める
-   APIレート制限（WBS 4.2の専用PoCは打ち切り、WBS 6.3・6.4のMVP実装・実機確認にあわせて確認する方針としたが、いずれもレート制限らしき例外（RemoteException/IOException/SecurityException以外の型）は発生しなかったという消極的な確認にとどまり、確定的な判断材料はまだ得られていない。WBS 6.4のコードレビューで、connect-client 1.1.0の実装を確認したところ、プラットフォーム側のレート制限エラーはRemoteException/IOException/SecurityExceptionではなくIllegalStateExceptionに変換されることが分かった（lessons.md 6.20）。`readWithHistoryFallback()`はこの型を捕まえない設計のため、レート制限が発生すればクラッシュという形で表面化するはずだが、今回の大規模な全件走査・連続Aggregate呼び出しでもクラッシュは発生しなかった。ただしIllegalStateExceptionはレート制限以外の要因でも起こり得るため、これも確定的な証拠ではない。確認された場合、Roomを導入しないという結論（D-031）を再検討する）
-   Vico採用後の残課題: 10年規模（数千bucket）での操作感（Pixel 11実機でHeart Rateの365bucket規模までは描画・横スクロールとも確認済み）、ピンチズーム、`aggregateGroupByPeriod()`が値のないbucketを実際にどう返すか（WBS 6.2/6.5、lessons.md 7.2〜7.3）
-   3Y / 5Y期間を追加するか（WBS 6.1/6.2）
-   ~~Blood Glucose / SpO2 のAggregate対応状況~~ → SpO2・Blood Glucoseとも解決済み。SpO2はWBS 6.10（D-049）、Blood GlucoseもWBS 6.10（D-050）で、Body Fat・HRVと同じくAggregateMetricが存在しないことをjavap逆コンパイルで確認し（D-049で両型とも確認済み、lessons.md 6.30）、自前集計で対応した。Blood Glucoseの記録頻度は血圧・SpO2と同様この端末の実データが無く確認できないまま、低頻度想定で「最新値＋前回比」を暫定確定した（SpO2はその後2026-10-06にHealth Sync経由の実データで1分間隔の連続サンプルと判明し、この想定は外れ、D-052で「最新レコードがある日の平均＋前日比」に変更した。lessons.md 6.30参照。Blood Glucoseは未確認のまま）（D-050、下記の「ホームの『最新値＋前回比』カード」の項目参照）
-   Distanceの複数ソース重複処理（§22.2で「あり（Activity）」と分類）が実際に効いているかどうか。Steps PoC 2で確認したのはStepsRecordのAggregateのみで、DistanceRecordでの実機確認はまだ行っていない（WBS 6.10、D-042(8)）
-   Active Calories / Total Caloriesの複数ソース重複処理（§22.2で「あり（Activity）」と分類）が実際に効いているかどうか。DistanceRecordと同様、ActiveCaloriesBurnedRecord/TotalCaloriesBurnedRecordそれぞれでの実機確認はまだ行っていない（WBS 6.10、D-043）。加えて、ActiveCaloriesBurnedRecordの個々のレコードの記録頻度・1件あたりの値の大きさも未確認のまま残っている（Pixel 11実機にActiveCaloriesBurnedRecordのデータを書き込むソースが存在せず、Records/Sourcesタブとも空データでしか動作確認できていない。TotalCaloriesBurnedRecordは「Health」ソースが15分間隔・1件あたり約10〜60kcal、「Fit」ソースも含め計約17万件規模で記録されており、Records/Sourcesタブとも実データで動作確認済み。要検証、D-043(4)）
-   **TotalCaloriesBurnedRecord.ENERGY_TOTAL（Aggregate）は、レコードの無い期間でもnullを返さない**。実機確認の結果、レコードが1件も存在しない期間（2010年の1日分）でAggregateを試したところ、nullではなく非null値（約1,565kcal）が返った。他のAggregateMetric（Distance・Steps等）やこのアプリの他箇所のコメントが前提としてきた「レコードが無ければnullになる」という仕様が、このメトリクスには当てはまらない。Android 14+のプラットフォーム側でActive Calories・基礎代謝等から補完した推計値を返していると見られるが、正確な発生条件・計算根拠は未確認（非公開のプラットフォーム実装のため、このアプリ側の逆コンパイルでは確認できない）。この挙動により「データがない項目は非表示」の設定（`isTotalCaloriesCardHidden()`）が機能しない問題があったため、`HealthConnectManager`に範囲内の実レコードの有無を確認する`hasAnyRecord()`を追加し、ホーム画面カード（`readTotalCaloriesAggregateTotal()`）でAggregateを呼ぶ前に実レコードの有無を確認し、無ければnullとして扱うよう修正した。詳細画面のChart（`readTotalCaloriesAggregates()`）にも同じガードをbucketごとに追加することを試みたが、**実機計測の結果、ガードを入れる前の時点でALL期間（MONTH bucket、約43個）の`aggregateGroupByPeriod()`自体が単独で約77秒かかることが分かった**（Heart RateのALL期間96秒・lessons.md 6.12に匹敵する既存の遅さ。独立した既知の問題として本節に別項目を立てた）。ガードを実際に追加して計測すると合計約45秒で、数字の上ではガードを入れたほうが速いという結果になり、「悪化させる」根拠にはならなかった。2回の計測間で実行環境が変わった誤差と見られ、ガード追加自体の影響は計測が安定せず確認できていないが、既に77秒という遅さの操作に効果を確証できないまま追加すべきではないと判断し、Chart側へのガード追加は見送った（このグラフのALL期間は最古レコード時刻が開始点のため、影響は記録期間中に途切れた日・週・月に限られる）。そのため詳細画面のChartでは、記録が1件もないbucketでも「記録があるように」描画される問題が未解消のまま残る（要検証）。一方、実際にレコードがある日は、単一ソースに絞ったAggregateとRaw合計が完全に一致することも確認できた。ただしこの一致確認は「24時間すべてレコードがある日」「単一ソースに絞った」場合に限っており、ホーム画面・Chartが実際に使うソースを絞らない呼び出しや、レコードが一部だけ欠けた期間で同様に一致するか（推計による水増しが混ざらないか）は未確認のまま残る（要検証）。詳細はlessons.md 6.26参照（WBS 6.10、コードレビュー指摘、2026-10-01）
-   Exerciseの複数ソース重複処理（§22.2で重複処理欄を「—」のまま確定させていない）が実際にどう扱われるかは未確認のまま残る（WBS 6.10、D-051）。記録頻度・MetricDensity.LOWの妥当性・ホーム画面カードの期間合計方式は、Pixel 11実機（実データ、最古レコード2023/04/17、Fit 604件・Health 600件の計1,204件規模）で問題なく動作することを確認済み（1ヶ月タブのDAY bucket・全期間タブのMONTH bucketとも、クラッシュなく妥当な値で描画された）。ただし2ソースが近い期間に重なって記録しているかどうかの突き合わせまでは行っておらず、重複処理自体の検証はDistance（D-042(8)）と同様未着手のまま残る
-   **`ExerciseSessionRecord.EXERCISE_DURATION_TOTAL`（Aggregate）が、Rawレコードの`Duration.between(startTime, endTime)`（Recordsタブの表示値）と一致するとは限らない**（WBS 6.10、D-051、コードレビュー指摘）。`ExerciseSegment`には休憩中を示す`EXERCISE_SEGMENT_TYPE_PAUSE`/`EXERCISE_SEGMENT_TYPE_REST`という定数が存在し、Sleepの`SLEEP_DURATION_TOTAL`が覚醒区間（`STAGE_TYPE_AWAKE`）を除いた値になっている（D-032）のと同様に、これらの区間を除いて計算されている可能性がある。実際の計算はHealth Connectプラットフォーム側の非公開実装のため、このアプリ側の逆コンパイルでは確認できない。一時停止を含むセッションの実データが手に入った際、RecordsタブとChart/ホームカードの値を突き合わせて確認すること
-   ホームの「最新値＋前回比」カード（体重: WBS 6.1、D-033。安静時心拍数・血圧・体脂肪率: WBS 6.10、D-044〜D-046。Blood Glucose: WBS 6.10、D-050。HRV・SpO2はそれぞれWBS 6.11（D-048）・WBS 6.12（D-052）で別方式に変更済み、下記項目参照）の「前回比」は、Rawレコードを降順に並べた先頭2件の差として計算している。複数ソースが同じ測定を別々に書き込んでいる場合（例: 体重計アプリと連携先アプリが同時刻付近に書き込む）、前回比が常に0付近になったり、比較対象が体重計本体ではなく別ソースの値になったりし得る。重複ソースがある場合の前回比の意味づけは未決（WBS 6.2〜6.4で詳細画面と合わせて検討する）。なお「最新値」自体がいつの記録かはWBS 6.10（D-044(3)）で両カードに記録日の表示を追加して解消したが、前回比の比較相手（2件目）の記録日は表示していないため、2値の間隔が大きく空いている場合に気付きにくい点は残る。**血圧はこのアプリの実データが無いため記録頻度が未確認のまま「最新値＋前回比」を維持している（WBS 6.11、D-048(1)）。ユーザーの家族の利用実例（「朝晩毎日」）を踏まえた判断だが、このアプリ自身の実データでの裏付けはない。加えて、血圧は朝晩で系統的に異なる可能性があり前回比の符号が朝→晩・晩→朝で交互に入れ替わりやすいこと、家庭血圧計では1回の測定機会に続けて2回測り個別レコードとして書き込む機器があり得ること（その場合HRVと同じ「数十秒〜数分間隔の値の差」になる）は考慮できておらず、「意味を保てる」とまでは言い切れない（WBS 6.11、コードレビュー指摘、D-048）。朝晩の差・連続測定の扱いは要検証のまま残る**。**Oxygen Saturation（SpO2）も同じ理由でこのアプリの実データが無いまま「最新値＋前回比」とした（WBS 6.10、D-049）。血圧と異なりユーザー本人・家族の具体的な利用実例による裏付けもなく、パルスオキシメーターでの散発測定（低頻度、本パターンが妥当）とウェアラブルによる睡眠中の連続/バースト測定（高頻度、HRVに近く前回比の意味が薄くなる可能性）のどちらが実態に近いか未確認のまま、一般的な低頻度想定だけで決めている。Pixel 11実機ではHealth Connectの「データとアクセス」画面にOxygen Saturation自体の項目が存在せず（レコードが1件もない）、ユーザーがHealthアプリ側のHealth Connect同期設定を確認・変更した後も同期されなかった（呼吸数など他のバイタル項目は新たに同期された）。記録頻度プロファイルの実機確認は完全に持ち越しとなっており、D-048のようなカード方式の見直しが必要になる可能性が残る**。**Blood Glucoseも同じ理由でこのアプリの実データが無いまま「最新値＋前回比」とした（WBS 6.10、D-050）。血圧・SpO2と同様、具体的な利用実例による裏付けはなく、自己測定器による食前・食後などの散発測定（低頻度、本パターンが妥当）という一般的な想定のみで決めている。加えて、血糖値は食前・食後で意図的に系統的な差が生じる測定のため、血圧の朝晩差（D-048）と同種の「前回比の符号が測定文脈によって交互に入れ替わりやすい」問題がそのまま当てはまる可能性が高い。Pixel 11実機にはBlood Glucoseの実データが無く、記録頻度・測定文脈による差のいずれも確認できないまま残る**
-   ~~HRVの「最新値＋前回比」カードは、実際の記録頻度（1日平均約57件、5〜10分間隔のバースト）では前回比が隣接する2サンプルの差という意味の薄い値になっていた~~ → WBS 6.11で解決済み（D-047(3)で発覚、D-048で対応）。記録頻度プロファイル（低頻度型は前回比を維持、高頻度バースト型は前日比に変更）を分岐基準とし、HRVのみ「最新レコードがある日の平均＋前日比」（件数・最小〜最大を添える）に変更した
-   ~~SpO2の「最新値＋前回比」カードは、実際の記録頻度（Health Sync経由の実データで1分間隔の連続サンプル、約200件/日）では前回比が「直近1分の値とその1分前の値の差」という意味の薄い値になっていた~~ → WBS 6.12で解決済み（D-049(3)の懸念が当たったもの、D-052で対応）。HRVと同じ「最新レコードがある日の平均＋前日比」（件数・最小〜最大つき）に変更した。次の項目で述べるHRVの「最新日が今日の場合は途中経過になる」点は、SpO2も同様に当てはまる（睡眠中と日中で値が変わりうる指標のため。SpO2は実機で未検証）
-   **HRVホームカードの「最新レコードがある日の平均」は、最新日が今日の場合、今日の記録が揃いきる前の途中経過になる**（WBS 6.11、D-048のコードレビュー対応(b)）。HRVは時間帯（睡眠中・早朝は高い、日中は低い）で大きく変わる指標のため、前日比の比較相手（前日の24時間分の平均）と比べると系統的な偏りが生じうる。実機確認（2026/10/04、67件）は1日の大半が記録済みの時点の値だったとみられ、この偏り自体は検証できていない。対策の候補は「前日比を確定した日同士（最新日が今日なら、昨日と一昨日）で比較する」「比較する時間帯を揃える」の2つで、どちらも設計判断を伴うため未対応のまま残る
-   ホームの指標カード（WBS 6.1）は要件§6のカード例が示すSleepのステージ内訳（Awake | REM | Light | Deep）を含んでいない（スパークライン省略はD-033）。対応するかはWBS 6.10で検討する
-   「データありのみ表示／すべて表示」の設定（§6）はWBS 6.6で実装し、Stepsカード（データなし）で実機確認した。Weight/HeartRate/Sleepカードのデータなし時に隠れる経路はStepsと同一ロジックだが、確認時のテストデータにこれらの空データ状態がなく未検証のまま残っている（D-039）
-   上記の設定がOFFの状態で期間タブ（今日／週／月／年）を切り替えると、新しい期間の結果が届くまでの一瞬、データなしで隠れているはずのカードが「読み込み中」として出現してから消える（全カード非表示時の案内も同様に一瞬消える）。画面復帰時に同種のちらつきがあった問題はWBS 6.6で解消したが、期間タブ切替時はユーザー操作への読み込みフィードバックとして許容し、対応を見送った（D-039）
-   WeightのDetail画面グラフに、WEIGHT_AVGが複数ソースの同一測定を重複排除せずに含む平均であることの注記を出すか（§8。WBS 6.2では対応していない）
-   **Blood Glucoseの表示単位をmg/dLに固定しており、mmol/Lが標準の地域（英国・EU・カナダ・豪州など）のユーザーへの配慮が無い**（WBS 6.10、D-050、コードレビュー指摘）。このアプリは日本語／Englishの2言語に対応しているが、「English」設定が必ずしもmmol/L圏のユーザーを意味するわけではない（米国はmg/dLが標準）ため、言語設定への単純な連動では正確な対応にならない。ユーザーに方針を確認した結果、MVPフェーズで複雑さを増やさないことを優先し、今回はmg/dL固定のまま進め、地域差への未対応を本項目として要検証に残すことにした。言語・地域に応じた単位切り替え（言語設定への連動、端末ロケールでの判定、独立した単位設定の追加など、どの方式を採るかも未検討）は、海外ユーザーからの実際の要望が出た段階で改めて検討する

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
