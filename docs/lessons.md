# 実装知見・テスト観点

既存のAndroidアプリ開発（Health Connect連携を含む）で得た知見のうち、**Viewerの設計・テストを実際に変えるものだけ**を記録する（D-017）。
公式仕様の写経や失敗談の記録はしない。Viewerに当てはまらなくなった項目は削除する。

## 根拠の区分

各項目の「根拠」は次のいずれか。既存アプリで確認した挙動を、Health Connect全体の保証として扱わないこと。

| 区分 | 意味 |
|---|---|
| **公式** | 公式ドキュメント・ポリシーで定められている仕様 |
| **実機確認** | 既存アプリで実機確認した挙動。環境やバージョンが変われば変わり得る |
| **要検証** | Viewerで改めて確認が必要な仮説 |

------------------------------------------------------------------------

## 1. Google Play の申告

### 1.1 申告が必要になる条件は、Manifestに宣言した権限で決まる

-   **知見**: Health Connectのデータ型の申告は、配布するAABのManifestに含まれる権限によって必要になる。アプリ内で機能をOFFにしていても、権限が宣言されていれば申告と審査の対象になる
-   **Viewerへの適用**: Manifestには実際に読むデータ型の `READ_*` 権限だけを宣言する。データ型を追加するときは申告の更新と審査がセットになるので、リリース計画に審査期間を含める
-   **根拠**: 公式／実機確認（既存アプリでの申告作業）
-   **確認日**: 2026-09-21

### 1.2 Health apps declaration はすべてのアプリで提出が必要

-   **知見**: Google Playで公開するすべてのデベロッパーが対象で、クローズド・オープンテストを含むすべてのトラックに適用される（例外はシステムサービスとprivate appのみ）
-   **Viewerへの適用**: WBS 8.3で提出する。読み取るデータ型ごとに用途の説明が必要になる
-   **根拠**: [Google Play「Health apps declaration」](https://support.google.com/googleplay/android-developer/answer/14738291)（公式）
-   **確認日**: 2026-09-24

### 1.3 ストア掲載情報はテキストも画像も審査される

-   **知見**: 掲載文・翻訳・スクリーンショット・アイコン・フィーチャーグラフィックがすべて審査対象。医療上の効果の主張や、実装していない機能の記載は避ける
-   **Viewerへの適用**: ストア文言は「健康データを外部送信しない」（requirements.md §14）と整合させ、診断・助言を連想させる表現を使わない。スクリーンショットは実データではなくデモ用データで撮る
-   **根拠**: 公式／実機確認（既存アプリの審査対応）
-   **確認日**: 2026-09-23

------------------------------------------------------------------------

## 2. 権限説明画面（Permissions rationale）

### 2.1 Android 13以前と14以降で必要な仕組みが違う

-   **知見**: Health Connectの権限画面にある「プライバシーポリシー」リンクから起動される画面が必要。単なるリンク先ではなく、**この画面を処理できるActivityが宣言されていないと、権限リクエスト自体をHealth Connect側が拒否する**（`PermissionsActivity`が"App should support rationale intent, finishing!"のログを出して即終了し、権限ダイアログが表示されない。Android 14以降の`VIEW_PERMISSION_USAGE`経路で実機確認。Android 13以前の`ACTION_SHOW_PERMISSIONS_RATIONALE`経路は未確認）
    -   **公式ドキュメント（Get started）が示す構成**: Android 13以前向けに`androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE`を処理する専用Activity（例: `PermissionsRationaleActivity`）、Android 14以降向けに`android.intent.action.VIEW_PERMISSION_USAGE`（category `android.intent.category.HEALTH_PERMISSIONS`）を`android:permission="android.permission.START_VIEW_PERMISSION_USAGE"`で保護した`activity-alias`（`targetActivity`は同じ専用Activity）。この権限保護は、この起動経路をシステム（Health Connect）以外から呼ばせないためのもの
    -   **公式サンプル（[android/health-samples](https://github.com/android/health-samples)）の構成**: 上記を簡略化し、両方のintent-filterをLAUNCHER Activity（MainActivity）に直接重ねている。`activity-alias`も`android:permission`もない
    -   Viewerでは公式サンプルと同じ簡略構成をMainActivityに実装し、Android 14以降の実機（Android 17 / Health Connect Controller）で権限ダイアログが正しく開き、許可まで完了することを確認した（2026-09-29）。ただし、これは「公式ドキュメントの推奨構成が不要」という意味ではなく、**MainActivityがもともとLAUNCHERでexportedなため実害が小さいと判断し、保護なしの簡略構成を暫定的に選んだ**という位置づけ
-   **Viewerへの適用**: 現状（Activityが1つのみ、表示内容も仮のステータス画面）は公式サンプル同様の簡略構成で対応済み（WBS 1.3）。専用の説明Activityに分離するタイミング（実際のプライバシーポリシー文言を出すWBS 8.1、または画面数が増えたとき）で、公式ドキュメント通りのactivity-alias＋`START_VIEW_PERMISSION_USAGE`保護に切り替えるか再検討する
-   **根拠**: 公式（Health Connect Get started。activity-alias＋permission保護の推奨構成）／公式（[android/health-samples](https://github.com/android/health-samples)。MainActivity直接宣言でも動く簡略構成）／実機確認（Viewer、Android 17 / Health Connect Controller、Android 14以降の`VIEW_PERMISSION_USAGE`経路のみ、2026-09-29）／要検証（Android 13以前の`ACTION_SHOW_PERMISSIONS_RATIONALE`経路、およびintent-filterを片方だけ宣言した場合の挙動。常に両方を宣言した状態でしかテストしていない。lessons.md 3.3の方針に沿ってPixel 3などでの確認が必要）
-   **確認日**: 2026-09-29（初出2026-09-21から更新）

### 2.2 説明文はプライバシーポリシーと一致させる

-   **知見**: 表示内容はPlay Consoleに登録するプライバシーポリシーと同一でなければならない
-   **Viewerへの適用**: プライバシーポリシーの該当箇所を変えたら、説明画面の文言（strings.xml、日英）も同時に更新する
-   **根拠**: 公式
-   **確認日**: 2026-09-21

### 2.3 説明画面の言語はアプリ内の言語設定に従うか

-   **知見**: この画面はHealth Connectアプリから外部起動される。既存アプリ（React Native）ではアプリ内の言語設定を参照できず、OSの言語に従った
-   **Viewerへの適用**: Viewerは `AppCompatDelegate.setApplicationLocales()` を使うので、アプリ別言語が外部起動のActivityにも効く可能性がある。PoCで確認する
-   **根拠**: 要検証
-   **確認日**: —

------------------------------------------------------------------------

## 3. 権限と状態

### 3.1 権限の状態は、保存した値ではなく毎回Health Connectに問い合わせる

-   **知見**: 権限はHealth Connectの設定画面などアプリの外から取り消される。「接続済み」をアプリ内の値だけで判断すると、取り消し後も接続済みと表示され続けた
-   **Viewerへの適用**: 起動時と画面復帰時に `getGrantedPermissions()` で確認する。権限の拒否・未付与は例外ではなく通常の状態として画面を設計する
-   **根拠**: 実機確認
-   **確認日**: 2026-09-21

### 3.2 UIを隠しても、その状態にならないとは限らない

-   **知見**: 設定画面の項目を隠しただけでは、アップグレード時に残った設定値やdeep linkなど、別の経路でその状態に到達できた
-   **Viewerへの適用**: 「表示しない」と「その状態になり得ない」を区別する。特に広告削除の購入状態や権限状態は、UIの表示有無ではなく実際の状態を確認して分岐する
-   **根拠**: 実機確認
-   **確認日**: 2026-09-21

### 3.3 Android 9〜13 と 14以降は別物としてテストする

-   **知見**: Android 13以前はHealth ConnectがPlayから入れる別アプリ、14以降はOSに統合されており、内部の経路が異なる。既存アプリの**書き込み操作**では、エラーの出方の差を実機で確認した（存在しないレコードの削除が、14以降では成功し、Android 12ではエラーになった）
-   **Viewerへの適用**: 読み取り・権限・ページング・Aggregateでも差が出る可能性があるため、両方の環境で実機検証する（手元の端末: Pixel 11 / Android 14以降、Pixel 3 / Android 12）。Android 13以前では「Health Connect未インストール」「要アップデート」の案内もテストする
-   **根拠**: 公式（[提供形態の違い](https://developer.android.com/health-and-fitness/health-connect/availability)）／実機確認（書き込み操作。Pixel 3 / Android 12、Health Connect v2026.08.06.00）／要検証（Viewerの読み取り）
-   **確認日**: 2026-09-21

### 3.4 端末のHealth Connectが履歴読み取りに対応していない場合、権限リクエストから除外する

-   **知見**: `client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)`で、この端末のHealth Connectが履歴読み取りに対応しているか（`FEATURE_STATUS_AVAILABLE`か`FEATURE_STATUS_UNAVAILABLE`か）を同期的に確認できる（IPC不要、`client.features`はプロパティ）
-   **Viewerへの適用**: 未対応の端末で`READ_HEALTH_DATA_HISTORY`をリクエストセットに含め続けると、何度リクエストしても「未許可」から抜け出せず、ユーザー操作で解決できる状態だと誤認させる。未対応時はリクエストセットから除外し、UI上も「未許可」ではなく「この端末では対応していない」という固定の案内を出す（`MainActivity.kt`の`historyPermissionStatusTextRes`）
-   **根拠**: 公式（APIの型定義。`androidx.health.connect:connect-client:1.1.0`のclassファイルをjavapで直接確認）／実機確認（Pixel 11 / Android 17では`FEATURE_STATUS_AVAILABLE`になり、権限ダイアログにHISTORY_READが正しく含まれることを確認、2026-09-29）／要検証（Pixel 3等、未対応環境が実在するかどうか自体が未確認）
-   **確認日**: 2026-09-29

### 3.5 データ型の読み取り権限と履歴読み取り権限は、別々に許可・拒否できる

-   **知見**: 権限リクエストのダイアログは、まずデータ型ごとの読み取り権限（例: Weight）を選ばせる画面が出て、`READ_HEALTH_DATA_HISTORY`は別画面（日本語では「(アプリ名)に過去のデータへのアクセスを許可しますか？」、英語では"Allow «アプリ名» to access past data?"）で個別にAllow / Don't allowを選ばせる。片方だけ許可・片方だけ拒否という組み合わせが実際に起こり得る
-   **Viewerへの適用**: データ型の読み取り権限と履歴読み取り権限は、それぞれ別の状態として保持・表示する。`containsAll`のような単一のAND判定でまとめると、片方だけ拒否されたときに誤表示になる
-   **根拠**: 実機確認（Viewer、Android 17 / Health Connect Controller、Android 14以降の経路のみ、2026-09-29）／要検証（Android 13以前でも同じく別画面で問われるか）
-   **確認日**: 2026-09-29

------------------------------------------------------------------------

## 4. エラー処理

### 4.1 Health Connectの呼び出しを他の処理と同じ失敗単位にしない

-   **知見**: Health Connectの呼び出しと他の読み込み処理をまとめて待つと、Health Connect側が失敗しただけで全体が失敗し、関係のない表示まで空になった
-   **Viewerへの適用**: Health Connectの呼び出しは個別に例外を処理する。複数のデータ型を並列に読むときも、1つのデータ型の失敗でホーム全体を失敗にしない（カード単位でエラー表示する）
-   **根拠**: 実機確認
-   **確認日**: 2026-09-21

### 4.2 エラーメッセージの文字列で分岐しない

-   **知見**: 同じエラー種別が複数の原因で返ることがあり、メッセージ文字列はライブラリやHealth Connectのバージョンで変わり得る
-   **Viewerへの適用**: 例外の型で分岐し、メッセージ文字列には依存しない
-   **根拠**: 実機確認
-   **確認日**: 2026-09-21

### 4.3 長い読み込みを途中で止められるか

-   **知見**: 既存アプリ（React Nativeのライブラリ経由）では、ネイティブ側の呼び出しを途中でキャンセルできなかった
-   **Viewerへの適用**: Viewerは公式クライアントのsuspend関数を直接呼ぶので、コルーチンのキャンセルで止められる可能性がある。全件走査（ソース別件数など）のキャンセル設計の前にPoC 3で確認する
-   **根拠**: 要検証
-   **確認日**: —

------------------------------------------------------------------------

## 5. プライバシー

### 5.1 リリースビルドでもログはlogcatに残る

-   **知見**: リリースビルドでもエラーログは端末のlogcatに出る。例外のメッセージには入力値やデータの値が含まれることがある
-   **Viewerへの適用**: ログ出力は共通の関数に集約し、リリースビルドでは例外のクラス名だけを出す。レコードの値・データ型・データソース名はログに出さない（requirements.md §14の広告・Analyticsとの分離と同じ境界）
-   **根拠**: 実機確認
-   **確認日**: 2026-09-21

### 5.2 Recent Appsのプレビューだけを隠せるのはAndroid 13以降

-   **知見**: Recent Appsのプレビューだけを隠すAPI（`Activity.setRecentsScreenshotEnabled(false)`）はAndroid 13（API 33）以降にしかない。それより前は `FLAG_SECURE` しかなく、スクリーンショットと画面録画も同時に禁止される
-   **Viewerへの適用**: 既定ではスクリーンショットを禁止しない（D-018）。将来Recent Appsの保護を入れる場合、Android 9〜12では副作用としてスクリーンショットも禁止されることを前提に仕様を決める
-   **根拠**: 公式／実機確認
-   **確認日**: 2026-09-18

------------------------------------------------------------------------

## 6. データ読み取りAPI

### 6.1 履歴読み取り権限がない状態で30日より古いレコードを読むとエラーになる

-   **知見**: `READ_HEALTH_DATA_HISTORY`がない状態で`readRecords()`に30日より古い範囲を含む`TimeRangeFilter`を渡すと、結果が単に0件になるのではなく`SecurityException`が発生する。また公式ドキュメント上、この制限の起点は「権限を**最初に許可した時点**から30日前」であり、「**現在**から30日前」ではない
-   **Viewerへの適用**: 全期間読み取りを実装する際は、事前に把握している権限の許可状態に応じて範囲を選ぶ（未許可なら最初から直近30日に絞る）。加えて、取得中に権限が外部から取り消される可能性（3.1参照）に備え、`SecurityException`を捕捉して直近30日にフォールバックする防御的処理を入れる（`HealthConnectManager.readAllWeightRecords()`で実装）。アプリからは「最初に許可した時点」を直接取得する手段がないため、`Instant.now().minus(30日)`を安全側（常に許可される範囲の部分集合になり、エラーにならない）の近似として使っている。許可からの経過時間が長いほど、実際に読める範囲より狭く見せる可能性がある点は既知の簡略化
-   **根拠**: 公式（「Read raw data」ガイド）／実機確認（Pixel 11。履歴読み取り権限なし・体重の読み取り権限のみありの状態で、`historyPermissionGranted`の値を無視して強制的に全期間の`TimeRangeFilter`を試みるよう一時的に変更し、実際に`SecurityException`が発生して`readSafely`のフォールバックに落ちること、フォールバック後は直近30日分（数十件）が「直近30日分のみ表示しています」の案内付きでクラッシュなく表示されることを確認。検証後は元のロジック（`historyPermissionGranted`に応じて最初から範囲を選ぶ）に戻した、2026-09-29）
-   **確認日**: 2026-09-29

### 6.2 全期間を読むには`TimeRangeFilter.before(Instant.now())`を使う

-   **知見**: `TimeRangeFilter`には引数なしのコンストラクタも存在するが、公式ドキュメントに明示的な説明がなく意味を断定できない。`before(x)`は開始側が無制限・終了側`x`で排他的、`after(x)`は終了側が無制限・開始側`x`で包含的と明記されている
-   **Viewerへの適用**: 「全期間」は`TimeRangeFilter.before(Instant.now())`、「直近N日」は`TimeRangeFilter.after(Instant.now().minus(N, ChronoUnit.DAYS))`のように、意味が明記された`before`/`after`を組み合わせて使う。無引数コンストラクタには依存しない
-   **根拠**: 公式（APIドキュメント）／実機確認（Pixel 11、履歴権限許可済みの状態で`readAllWeightRecords()`が実データ（千件超）をページングで全件取得できることを確認、2026-09-29）
-   **確認日**: 2026-09-29

### 6.3 `ReadRecordsRequest`の`deduplicateStrategy`は実験的APIで、通常の呼び方では触れない

-   **知見**: `ReadRecordsRequest(recordType = X::class, timeRangeFilter = ..., ...)`という通常の呼び方（公式サンプルが使っている形。`recordType`を除いた6引数のコンストラクタに解決される）で呼ぶと、`deduplicateStrategy`を指定しなくても内部的に`DEDUPLICATION_STRATEGY_DISABLED`（重複排除なし）になる。`deduplicateStrategy`を実際に指定できる7引数のコンストラクタは`@androidx.annotation.RestrictTo(LIBRARY)`（ライブラリ内部専用）と`@ExperimentalDeduplicationApi`（要`@OptIn`）が付いており、通常のアプリコードからは到達しない
-   **Viewerへの適用**: Raw画面はD-007（重複も含めてすべて表示）が絶対条件のため、`ReadRecordsRequest`は通常の呼び方（6引数、recordType/timeRangeFilter/dataOriginFilter/ascendingOrder/pageSize/pageTokenのみ）のままにする。`@OptIn(ExperimentalDeduplicationApi::class)`でこの制限付きコンストラクタに`deduplicateStrategy`を明示的に渡すような変更はしないこと。CLAUDE.mdの「Health Connectで誤解しやすい点」にも落とし穴として記録済み
-   **根拠**: 公式（`androidx.health.connect:connect-client:1.1.0`のclassファイルをjavapで逆コンパイルし、各コンストラクタの注釈と、実際に渡す`deduplicateStrategy`の値をbytecodeレベルで確認。加えて、Viewer自身のコンパイル済み`HealthConnectManager.class`を逆コンパイルし、実際に6引数コンストラクタ経由で`deduplicateStrategy=0`（DISABLED）が渡っていることも確認、2026-09-29）
-   **確認日**: 2026-09-29

### 6.4 DataOriginからアプリ名を解決するには、Manifestの`<queries>`宣言に依存している

-   **知見**: `PackageManager.getApplicationInfo(packageName, 0)` → `getApplicationLabel(appInfo)`を`PackageManager.NameNotFoundException`でtry-catchするだけでよく、`QUERY_ALL_PACKAGES`のような権限は不要（公式ドキュメント「Data display and attribution」に明記）。ただし**Android 11+のパッケージ可視性制限は別に効いている**。Manifestの`<queries><intent><action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"/></intent></queries>`を一時的に外して実機で検証したところ、DataOriginのアプリ名解決が失敗し、D-025のフォールバックでpackageNameがそのまま表示されるようになった。Health Connectと連携する多くのアプリ（今回確認したソースアプリを含む）が同じ`ACTION_SHOW_PERMISSIONS_RATIONALE`のintent-filterを自身のために宣言しているため、この`<queries>`宣言がこちらにも可視性を与えていたと分かった。**一方、権限説明画面（自分のActivityが処理する側、lessons.md 2.1）はこの`<queries>`に依存していない**。同じく一時的に外して実機で確認したところ、権限説明画面（rationale intent）は影響を受けずに開いた。`<queries>`は「自分が他アプリを見る」ための宣言であり、Health Connect側が自分のintent-filterを見つけられるかどうかとは別の仕組みのため、両者が独立して動くのは道理に合う
-   **Viewerへの適用**: `DataOriginNameResolver`で実装済み。アンインストール済みアプリ・**可視性がなく解決できないアプリ**は例外を捕捉してpackageNameをそのまま表示する（詳細画面は「Inspector的体験」という要件§19の方針に合わせ、素性不明として隠さない）。この`<queries>`宣言はDataOrigin解決にのみ使われている（権限説明画面には使われていない）ため、削除・変更時はDataOrigin解決への影響を確認すること（AndroidManifest.xmlにもコメントを追記済み）。`ACTION_SHOW_PERMISSIONS_RATIONALE`を宣言せず`VIEW_PERMISSION_USAGE`（Android 14以降向け）のみを宣言しているソースアプリは、現状の`<queries>`では見えない可能性がある（要検証）
-   **根拠**: 公式（「Data display and attribution」ガイド。QUERY_ALL_PACKAGES等の権限が不要という点）／実機確認（Pixel 11。既存の`<queries>`宣言がある状態ではソースアプリ名が正しく解決され、`<queries>`の該当箇所を一時的に外すと同じソースアプリの名前解決が失敗してpackageNameへフォールバックすること、および権限説明画面は同じ変更の影響を受けず正常に開くことを確認、2026-09-29）
-   **確認日**: 2026-09-29

------------------------------------------------------------------------

## 7. テスト観点チェックリスト

実装・リリース前に確認する。

-   [ ] 権限未付与／一部のデータ型だけ許可／履歴読み取り権限なし
-   [ ] アプリ使用中にHealth Connectの設定から権限を取り消し、アプリに戻る
-   [ ] Health Connect未インストール・要アップデート（Android 13以前）
-   [ ] Android 9〜13 と 14以降の両方
-   [ ] 権限説明画面を両方の経路（Android 13以前・14以降）から開く
-   [ ] データが0件のデータ型／読み込み失敗（データなしと区別できるか）
-   [ ] 端末のタイムゾーン変更・夏時間の境界・日付境界での日／月／年bucket
-   [ ] テーマ（System / Light / Dark）と言語（日本語 / English）の切り替え
-   [ ] リリースビルドのlogcatに健康データの値が出ていないか
-   [ ] 広告リクエストに健康データ由来の情報が含まれていないか
-   [ ] 同一日・同一ソースなど複数の重複レコードが、実装変更（`ReadRecordsRequest`の呼び方の変更など）でRaw一覧から消えていないか（6.3）

------------------------------------------------------------------------

## 8. Viewerには適用しない知見

既存アプリで得たが、技術スタックやドメインの違いからViewerには関係しないもの。Viewerの方針と混同しないよう明記しておく。

-   Expo / React Native固有: config plugin、Gradleデーモンのキャッシュによるネイティブモジュールの未リンク、`EXPO_PUBLIC_*` 環境変数の反映、LogBoxの警告バナーがadbのタップを奪う件、ダーク/ライトの色定数を3か所で手動同期する必要
-   書き込み系: `clientRecordId` による冪等化、削除・再作成、同期ジョブと再試行キュー
-   アプリ内DB（暗号化DB、スキーマ変更時の再インストール）
-   Health Connectの有無によるビルドの出し分け（Viewerは常にHealth Connectを使う）
-   iOS固有の回避策
