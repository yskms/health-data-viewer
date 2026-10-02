# 実装知見・テスト観点

既存のAndroidアプリ開発（Health Connect連携を含む）で得た知見のうち、**Viewerの設計・テストを実際に変えるものだけ**を記録する（D-017）。
公式仕様の写経や失敗談の記録はしない。Viewerに当てはまらなくなった項目は削除する。

## 根拠の区分

各項目の「根拠」は次のいずれか。既存アプリで確認した挙動を、Health Connect全体の保証として扱わないこと。

| 区分 | 意味 |
|---|---|
| **公式** | 公式ドキュメント・ポリシーで定められている仕様 |
| **実機確認** | 実機（Pixel 11 / Pixel 3など）で確認した挙動。環境やバージョンが変われば変わり得る |
| **エミュレータ確認** | Androidエミュレータで確認した挙動。実機のHealth Connect実装と差異があり得るため、実機確認とは区別する（レビュー指摘） |
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

### 3.6 テスト時、Health Connectの読み取り権限は`adb shell pm grant`で直接許可できる

-   **知見**: `android.permission.health.READ_*`はAndroidの通常のruntime permissionとして扱われており、`adb shell pm grant <package> <permission>`で実際に許可状態（`dumpsys package`上で`granted=true`）にできることをPixel 11実機で確認した。2.1で確認済みの「権限説明画面（rationale intent）を処理できるActivityがないと権限リクエスト自体が拒否される」制約は、Health Connectアプリ自身が出す同意ダイアログ経由のリクエスト（`PermissionController.createRequestPermissionResultContract()`）にのみ関わるもので、`pm grant`による直接付与はこの経路を通らないため影響を受けない
-   **Viewerへの適用**: 新しいデータ型のPoC・MVP実装時、実機での動作確認のたびにHealth Connectの同意ダイアログをタップして許可する必要はなく、`adb shell pm grant com.yskms.healthdataviewer android.permission.health.READ_<TYPE>`で直接許可してテストを進められる（取り消しは既存の`pm revoke`、6.1参照）。ただしこれはテスト手順上の近道であり、実際のユーザーが辿る同意ダイアログの経路（文言・フロー）自体の確認は別途必要
-   **根拠**: 実機確認（Pixel 11、Android 17。PoC 4（Sleep）でREAD_SLEEP・READ_HEALTH_DATA_HISTORYの付与・取り消しに`pm grant`/`pm revoke`を使い、アプリが実際にその権限を認識して動作することを確認、2026-09-30）
-   **確認日**: 2026-09-30

### 3.7 手元の端末に実データが無いデータ型は、Google公式の「Health Connect Toolbox」でテストレコードを書き込んで実機確認できる

-   **知見**: WBS 6.10（Blood Pressure追加）で、この端末にBlood Pressureの実データが無かったため、Health Connect本体の開発元が配布する公式テストツール「Health Connect Toolbox」（`developer.android.com/health-and-fitness/health-connect/test/health-connect-toolbox`、APKをZIPで配布、Play Storeには出ない）を使い、`Insert Health Record`からBloodPressureRecordのテストレコードを直接Health Connectへ書き込んで実機確認した。導入は`adb install`でのサイドロードだが、この端末ではPlay Protectが`INSTALL_FAILED_VERIFICATION_FAILURE`でインストール自体をブロックした。Play ストアアプリの「Play Protect」設定で「アプリを確認するためのPlay Protectによるスキャン」を一時的にオフにすることで回避できた（ユーザー本人の端末設定変更が必要。確認後はオンに戻す）。書き込みには対象データ型のWRITE権限（例: `android.permission.health.WRITE_BLOOD_PRESSURE`）が別途必要で、こちらも3.6と同じ`adb shell pm grant`で直接付与できた
-   **Viewerへの適用**: このアプリはRead-onlyが原則（CLAUDE.md）のため、テストデータの書き込みはアプリ自身ではなく外部の公式ツールに行わせ、確認後は必ず削除する。削除はHealth Connect本体アプリの「データとアクセス」→対象データ型→エントリを選択→削除アイコンから行える（アプリ別ではなくデータ型別の削除画面）。この手順は、手元の端末に実データが無い他の優先度Aデータ型（Body Fat、HRV、Exercise、Blood Glucose、Oxygen Saturation等）を今後追加する際にも、同じ考え方（Toolboxで投入→実機確認→削除）でそのまま使い回せる
-   **根拠**: 実機確認（Pixel 11、Android 17。Health Connect Toolbox ToolboxApp-2.3.5でBloodPressureRecordを2件（2026-09-30、2026-10-02）挿入し、Viewer側のホームカード・グラフ・Records・Sourcesタブでの表示を確認後、Health Connect本体アプリの「データとアクセス」から全件削除・Toolbox自体もアンインストールして確認、2026-10-02）
-   **確認日**: 2026-10-02

### 3.8 adbで実機を操作する際、スクリーンショットを撮る前にフォアグラウンドアプリが対象アプリであることを確認する

-   **知見**: WBS 6.10（Blood Pressure追加）の実機確認中、`adb shell input tap`の座標誤り・`am force-stop`後の再起動タイミングにより、意図せず対象アプリ以外（端末の持ち主が普段使っている別のアプリ）がフォアグラウンドにある状態でスクリーンショットを撮ってしまう事故が複数回発生した。1回目は撮影後に気付いてファイルを削除、2回目は撮影前に`adb shell dumpsys activity activities | grep topResumedActivity`（または`mFocusedWindow`）でテキストベースにフォアグラウンドのパッケージ名を確認する運用に切り替え、対象アプリでないことを画像を見る前に検知できた
-   **Viewerへの適用**: 実機確認でスクリーンショットを使う場合、`adb exec-out screencap`の直前に必ず`dumpsys activity activities`等でフォアグラウンドが確認対象のパッケージ名（`com.yskms.healthdataviewer`等）であることを確認する。この確認を省略すると、`input tap`の座標計算ミス（画面解像度と表示解像度の取り違え等）や、画面遷移中のタイミングのずれにより、端末の持ち主の私的なデータ（他アプリの画面）を意図せず記録してしまうおそれがある
-   **根拠**: 実機確認（Pixel 11、Android 17、2026-10-02）
-   **確認日**: 2026-10-02

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

### 6.5 `aggregateGroupByPeriod()`にInstantベースの`TimeRangeFilter`を渡すとクラッシュする

-   **知見**: `aggregateGroupByPeriod()`（`AggregateGroupByPeriodRequest`、bucket幅を`Period`で指定するAPI）は、`timeRangeFilter`に`TimeRangeFilter.before(Instant)`/`after(Instant)`のようなInstantベースのfilterを渡すと`IllegalArgumentException: Either use TimeRangeFilter with LocalDateTime or AggregateGroupByDurationRequest`で即クラッシュする。`readRecords()`や`aggregateGroupByDuration()`（bucket幅を`Duration`で指定するAPI）はInstantベースのfilterを前提にしており、同じ`TimeRangeFilter`型でもAPIによって受け付ける生成方法（`before/after(Instant)` vs `before/after(LocalDateTime)`）が違う
-   **Viewerへの適用**: `HealthConnectManager.readWeightAggregates()`の`timeRangeFilter`は必ず`LocalDateTime`ベースで構築する（呼び出し元の`WeightGraphScreen.GraphPeriod.timeRangeFilter()`で対応済み）。同じ関数内でSecurityException発生時にフォールバックする「直近30日」の範囲も、`readAllWeightRecords()`等が使うInstantベースの`recentRangeFilter()`とは別に、LocalDateTimeベースの`recentRangeFilterLocal()`を用意して使い分けている。今後Aggregate系のAPI（`aggregate()`/`aggregateGroupByDuration()`/`aggregateGroupByPeriod()`）を追加する際は、関数ごとに要求される`TimeRangeFilter`の構築方法（Instant vs LocalDateTime）を毎回確認すること
-   **根拠**: 実機確認（Pixel 11、Android 17。WBS 2.2〜2.4のPoC実装中にエミュレータ（Pixel API 36）で実際にこの`IllegalArgumentException`でクラッシュすることを確認し、修正後は同じ画面が1M/1Y/ALLの全期間でクラッシュしなくなったことを確認。その後Pixel 11実機（実データ、複数年分）でも1M/1Y/ALLの全期間でクラッシュしないことを確認、2026-09-30）
-   **確認日**: 2026-09-30

### 6.6 単一ソースだけを指定したAggregate合計が、そのソースの生データ単純合計と一致しないことがある。原因は「秒単位で実際に重なっている区間の時間按分」で定量的にほぼ説明できる

-   **知見**: Steps（Activity系）で`aggregate()`に`dataOriginFilter`を単一ソースだけに絞って呼んでも、そのソースの生レコードを単純合計した値と一致しない場合がある（Pixel 11実機・実データで確認。具体的な歩数・レコード件数は個人の健康データのため公開しない。`docs/private/poc2-steps-raw-data.md`参照）。対抗仮説として、表示精度による誤読（分単位表示では区間の重なりを誤認しかねない）、ゼロ長・不正な区間のレコードがAggregate側で除外されている可能性、クエリ境界を一部だけまたぐレコードの扱いの違いを検討した
-   加えて、この確認の過程で公式ドキュメント（[Track steps](https://developer.android.com/health-and-fitness/health-connect/features/steps)）から、2026年6月更新以降の端末内歩数（on-device step counting）がSynthetic Package Name（SPN）という、アプリごとに異なりデバイスごとに安定する識別子で記録される仕様を確認した（requirements.md §25項目6で想定していた論点）。SPNはデバイス固有の安定識別子であるため、公開ドキュメントには実際の値を書かない
-   **切り分けの結果**:
    1.  クエリ境界の按分については、全期間（`before(now)`、開始側無制限）のfilterはRaw・Aggregate双方で終了側を`now`に統一する修正（D-030）の前後で変わっておらず、按分が影響するとすればそれは終了側（`now`）をまたぐレコード程度に限られるはずである。実際には期間を空けた複数回の測定で単一ソースの差の絶対値がほとんど変化せず、この消去法からも単一の境界レコードの按分が主因ではないと判断できる
    2.  ゼロ長レコード（`startTime == endTime`）の件数・合計をソース別に診断表示したところ、確認した期間・全ソースで0件だった。この仮説は否定された
    3.  同一ソース内で、開始時刻順に並べたときにInstant精度で他のレコードと区間が実際に重なっているレコードを診断表示したところ、単純合計と単体Aggregateが完全に一致する唯一のソースだけが「重なっているレコードが0件」で、差が生じている他の全ソースは「重なっているレコードあり」だった
    4.  3をさらに定量化するため、各レコードの歩数がその区間内で一様に発生していると仮定し、単純合計に「区間の和集合の長さ ÷ 区間の延べ長さ」（重なっていない時間の割合）を掛けた推定値を計算し、実際の単体Aggregate合計と比較した。重なりが0件のソースは推定値が単純合計と自動的に一致するため意味を持たず、重なりがあるソースに限って比較したところ、確認した複数の期間でいずれも、推定値は実際の値と非常に近い値（差は単純合計と実際の値との差の1%未満）になった
-   4の結果から、差の原因は「同一ソース内で秒単位で実際に重なっている区間を、Health Connectが時間按分で処理していること」である可能性が高いと判断する。残る差（推定値と実際の値のわずかなずれ）は、歩数が区間内で一様に発生するという単純化した仮定による近似誤差、または実際のアルゴリズムとの細かな違いによるものと考えられ、正確な計算式までは特定していない。多くのソースがスライディングウィンドウ的に頻繁に重なる区間でレコードを書き込んでいる可能性があり、これ自体はソースアプリの記録方式であって、Health Connect側の不具合ではないと考えられる
-   **Viewerへの適用**: 単一ソース単体のAggregateと生データ単純合計の差を、原因を断定する形では表現しない。「同一ソース内の秒単位の重複区間の時間按分」が定量的にも裏付けられた有力な説明だが、正確な計算式は未確定のまま扱う。差がレコード件数に比例するとまでは言えない
-   **根拠**: 実機確認（Pixel 11、実データ。複数の期間で、重なりのあるソースに限り、時間按分による推定値が実際の単体Aggregate合計と近い値になることを定量的に確認。ゼロ長レコードは全期間・全ソースで0件だったことを確認、2026-09-30）／要検証（Aggregateの正確な計算式、残差の原因。推定計算はTODAY・LAST_7_DAYSでレコード区間をfilterの範囲に切り詰めていないため、境界をまたぐレコードがある場合は推定値がわずかにずれ得る。同じ時間按分の考え方を全ソース合算に適用し、ソース間の重複除去が時間按分によるものか別の優先順位方式によるものかを切り分けることも今後の余地として残る）
-   **確認日**: 2026-09-30

### 6.7 継続的に記録されるデータ型（Heart Rate等）でRawを全件ページング読み込みすると、実機のヒープが枯渇しOutOfMemoryErrorでクラッシュし得る

-   **知見**: `readRecords()`をページングで全件呼び出し、結果を1つの`List`に溜め込む設計（`readAllWeightRecords()`/`readStepsRecords()`と同じパターンを`readHeartRateRecords()`にもそのまま適用）は、スマートウォッチ等から継続的に同期されるHeart Rateの「全期間」のような大量データでは実機のヒープを使い切り、`OutOfMemoryError`でアプリごとクラッシュする。例外はアプリのコードではなく、Health Connect SDK内部の`readRecords()`実装（プラットフォーム表現からSDKの`HeartRateRecord`への変換処理）で発生しており、アプリ側の`try/catch`（`RemoteException`/`IOException`/`SecurityException`）では捕捉できない（`Error`のサブクラスであり、そもそも捕捉を試みるべきでもない）
-   **Viewerへの適用**: WeightやStepsのPoCで確認できた「全期間をページングで全件取得してもクラッシュしない」という結果は、Heart Rateのように1レコードに複数サンプルを含み、かつ記録頻度が非常に高いデータ型には当てはまらない。「Rawレコードは重複も含めてすべて表示する」（D-007）という原則と、大量データ型での安全な表示方法の両立は、Roomを導入せず（WBS 4.3、D-031）、Raw一覧をページング表示化する（画面に見えている分だけメモリに保持する）方針で解決する。**（WBS 4.2時点の暫定対応だったサンプル数上限打ち切りは、WBS 6.3でJetpack Paging 3によるページング表示に置き換わり撤去済み。以下はWBS 4.2〜6.3の変遷の記録として残す）**。暫定対応として、`HealthConnectManager.HEART_RATE_RAW_SAMPLE_LIMIT`（サンプル数の上限）に達した時点でページングを打ち切り、`HeartRateRecordsResult.LimitReached`として画面に「全件ではない」旨を表示するようにした（レビュー指摘）。これはクラッシュを避けるための粗い安全弁であり、「全件表示」という原則そのものの解決ではない。WBS 6.3では、`PagingConfig.maxSize`を設定しないと最後までスクロールした時点で結局全件がメモリに残り同じ問題が再発し得る点、Health Connectの`pageToken`が前方向にしか進めず破棄したページに戻るには保持したtokenでの再取得が必要な点（tokenの有効期限・安定性は未確認のまま）、SDK内部変換（本項参照）に対してどのpageSizeなら安全かが未検証な点を含めて設計し、サンプル数上限は不要になった（D-035、`HealthRecordsPagingSource`、lessons.md 6.16）
-   **根拠**: 実機確認（Pixel 11、実データ（継続的な心拍数データ、複数年分）。「過去7日間」はクラッシュなく正常に表示できたが、「全期間」を選択すると`OutOfMemoryError`で実際にアプリがクラッシュすることを確認、2026-09-30。具体的なレコード数・サンプル数は個人の健康データのため非公開、`docs/private/poc3-heartrate-raw-data.md`参照）。上限導入後は「全期間」でもクラッシュせず、上限到達の打ち切り表示になることを実機で再確認した（2026-09-30）。WBS 6.3のPaging3化後も、大量スクロール（1,000件超）でOutOfMemoryErrorが再発しないことを実機で確認した（2026-10-01）
-   **確認日**: 2026-09-30（WBS 6.3でのPaging3化後の再確認は2026-10-01）
-   **既知の制約（PoC 3レビュー指摘、WBS 6.3で解消）**: `HEART_RATE_RAW_SAMPLE_LIMIT`（2,000,000サンプル）という値自体がクラッシュしないことは、Pixel 11でのみ確認していた。メモリの少ない端末では、この上限に届く前に同じ`OutOfMemoryError`が発生する可能性があり、どの端末でも安全な値かは未検証のままだったが、この上限自体がWBS 6.3で撤去されたため、この制約自体が解消した

### 6.8 `aggregateGroupByPeriod()`の所要時間はbucket数が多いほど長くなる傾向がある（単純比例ではない）。総データ量そのものは主要因ではない

-   **知見**: 当初は「体感」でしか比較できておらず（レビュー指摘）、`HeartRateGraphScreen`に`SystemClock.elapsedRealtime()`による実測を追加して測り直した。ほぼ同じ合計サンプル数（両者とも約600万サンプル、全期間の方がわずかに多い）に対し、日bucket・1年分（365bucket）は月bucket・全期間分（28bucket）よりも一桁近く遅かった。ただし所要時間の比（実測で約6.9倍）はbucket数の比（365対28、約13倍）ほど大きくなく、単純な比例関係ではなかった。全期間の方が合計データ量がわずかに多いにもかかわらず大幅に速いことから、**合計データ量そのものは所要時間の主要因ではない**と言える一方、bucket数（粒度）と所要時間の正確な関係式（比例か、それ以外の関数か）は特定できていない。Rawの全件読み込み（6.7）と異なりクラッシュはせず、正常に結果が返る
-   **Viewerへの適用**: 「1Y」を日bucketで集計する設計（`WeightGraphScreen`/`HeartRateGraphScreen`の現在の`GraphPeriod`定義）は、Weightのようにレコード数が少ないデータ型では問題にならなかったが、Heart Rateのように記録頻度が高いデータ型では、1Yの表示が実用に耐えないほど遅くなり得ることを実測で確認した。ローカルキャッシュ（Room）は導入せず（WBS 4.3、D-031）、bucket粒度をデータ型ごとに変える（例: Heart Rateの1Yは週bucketにする）方針で対応する。ただし今回実測したのは日bucket・1年分（365bucket）と月bucket・全期間分（28bucket）の比較のみで、週bucket（52bucket）そのものの所要時間は未実測であり、bucket数と所要時間が単純比例でないことも踏まえると「週bucketにすれば十分速くなる」という見込みは検証前の仮説にとどまる。WBS 6.2で実際に週bucketの所要時間を実測して確認する
-   **根拠**: 実機確認（Pixel 11、実データ（継続的な心拍数データ、複数年分）。`readHeartRateAggregates()`の呼び出し前後を`SystemClock.elapsedRealtime()`で計測し、日bucket・1年分と月bucket・全期間分の所要時間を実測して比較、2026-09-30。具体的な所要時間・サンプル数は個人の健康データのため非公開、`docs/private/poc3-heartrate-raw-data.md`参照）／要検証（bucket数と所要時間の正確な関係式。1年・日bucketと全期間・月bucketの比較は、bucket粒度と対象期間の長さの両方が同時に異なるため、粒度単体の影響を完全に分離できていない）
-   **確認日**: 2026-09-30

### 6.9 `SleepSessionRecord.SLEEP_DURATION_TOTAL`はAWAKE区間を除いた時間の合計。日付境界をまたぐSessionは、非AWAKE時間を実時間の重なりに比例して両日のbucketに按分している

-   **知見**: Pixel 11実機の実データ（**単一ソースのみ**、約775件のSleep Session、2024年5月〜）で、`aggregateGroupByPeriod()`が返す日bucketの値を、アプリ側でSessionのStage内訳（Stage型ごとの合計時間。当時のPoC専用画面`poc/SleepRawRecordsScreen`が表示していたもので、WBS 6.3で削除され、同じ内訳表示は`SleepDetailScreen`のRecordsタブに引き継がれている。表示はいずれも分単位に切り捨て済みで、秒精度のデータは保持していない）から独自に計算した値と突き合わせた。
    1.  **日付境界をまたがない単純なケース**（同一暦日に2 Sessionある日、`STAGE_TYPE_AWAKE`除外後のStage別合計時間の総和がSession区間の長さと分単位で完全一致する事例）を2件（2026/09/23の2 Session、2026/05/20の1 Session）確認したところ、いずれも`STAGE_TYPE_AWAKE`区間を除いた時間の合計が、bucketの値と分単位で一致した（差0〜1分）。Session区間の単純合計（AWAKE込み）とは、前者で57分の差があった
    2.  **日付境界（0時）をまたぐSession**を、0時より前の時間の長さが異なる6件（0時より前が4分・8分・70.5分・86.5分の事例を含む、2026年5月・9月の計6夜）で確認したところ、いずれの日も「Session区間のうち各暦日側の実時間（wall-clock）の割合」を、そのSessionのAWAKE除外後の非AWAKE時間に掛けた値（＝AWAKEがSession全体に一様に分布していると仮定した比例按分）が、観測されたbucketの値と差0.5〜8分で一致した。0時より前がわずか4〜8分と短い事例だけでなく、70.5分・86.5分と長い事例でも同様に一致したため、「終了日側にほぼ丸ごと計上される」という単純な見方（0時より前の時間が短い事例だけでは区別できなかった、後述）は当初のレビュー指摘通り誤りで、実際には日をまたいで按分されていることが確認できた
-   1は分単位で一致しており（差0〜1分）、「`SLEEP_DURATION_TOTAL`はAWAKE区間を除いた時間の合計である」という結論を高い精度で裏付けている。2は、当初0時より前が8分しかない1事例だけで確認した際、「終了日側にほぼ丸ごと計上される」という見え方と「実時間で比例按分される」という見え方を区別できなかった（レビュー指摘）。0時より前の時間がもっと長い事例を追加で5件確認した結果、比例按分の仮説が一貫して観測値に近い値を予測し、他の仮説（開始日側に丸ごと計上／終了日側に丸ごと計上）はいずれか1件以上の事例で明確に外れた（例: 0時より前が86.5分ある事例で、終了日側に丸ごと計上する仮説は観測値より60分以上少ない値しか予測できなかった）。ただし「Stage単位での按分」（各Stageの実際の開始・終了時刻をもとに0時で切り分ける）は、Stageごとの正確な時刻を取得していないため今回の検証では棄却も裏付けもできていない（後述）
-   **確認できたこと**: 境界をまたぐSessionは、開始日側・終了日側の両方のbucketに、実時間の重なりに応じて按分された形で計上される（丸ごと1つのbucketに計上されることはない）。按分は「AWAKE時間がSession全体に一様に分布している」と仮定した比例計算で近い値（差0.5〜8分、6事例）を予測できる
-   **確認できていないこと**: 上記の比例按分の一致は、分単位表示の丸め誤差（1つの値につき1分未満、比較に使う数個の値を合計しても数分程度）だけでは説明しきれない幅（最大8分）があり、「AWAKEがSession全体に一様に分布している」という仮定自体の近似誤差（実際のAWAKEの位置が一様でないことによるずれ）を含んでいる可能性が高い。つまり、Health Connectが実際に行っている計算は、Stageごとの実際の時刻を使った按分（Stage単位の按分）である可能性を積極的に示唆しており、一様分布の仮定はあくまで「Stageの正確な時刻が分からない中での近似モデル」として観測値をおおむね説明できた、という位置づけにとどまる。Stageごとの正確な開始・終了時刻（秒精度）を取得できていないため、Stage単位の按分そのものを直接確認することも棄却することもできていない。境界をまたぐStage自体がある場合の扱い、`STAGE_TYPE_AWAKE`以外の非睡眠系Stage（`STAGE_TYPE_OUT_OF_BED`・`STAGE_TYPE_AWAKE_IN_BED`）も同様に除外されるか（実データにこれらのStage型が出現しなかったため未確認）も未確認
-   **未確認（重要）**: 複数ソースが同じ夜（またはその一部）を重ねて記録した場合の重複処理（Source priority、WBS 5.1の項目名の一部）。今回の実データはすべて単一ソースのみで、複数ソースが重なるケースは一度も確認していない
-   **副産物**: この検証中に、当時のPoC専用画面`poc/SleepGraphScreen`（WBS 6.2で削除、`SleepDetailScreen`に置き換え済み）のbucket別数値一覧（`SleepBucketList`）が、外側の`Column`に`verticalScroll()`を付け忘れていたためスクロールできず、1Y/ALLで多くの行が実質到達不能になっていたバグを発見し、`verticalScroll(rememberScrollState())`を追加して修正した（アプリの他の画面には影響しない、このPoC専用の一覧に限定した不具合）
-   **Viewerへの適用**: グラフの「日合計睡眠時間」は、Raw画面が示すSession区間の長さの合計（AWAKE込み）とは一致しない前提で設計・説明する。「睡眠時間」（グラフ、AWAKE除外・日ごとに按分済み）と「Session区間の長さ」（Raw、按分なし）が別概念であることをMVPのUI文言で明示する（WBS 6.2）。日付境界をまたぐSessionについても、アプリ側で独自に日ごとの按分ロジックを実装する必要はない（公式Aggregateが暦日の境界に応じて按分済みの値を返している）という限りではD-032の判断を維持できるが、按分の正確な計算根拠（実際にStage単位で行っているか、Session全体の一様分布近似か）と複数ソース時の重複処理は未確認のまま残る
-   今後さらに精度良く確認する場合は、PoC画面に秒精度のduration表示を一時的に追加してから再検証するとよい（現状は分単位切り捨てのUI表示を読み取る方法に依存しており、これが精度の上限になっている）
-   Sleepの生レコード行（当時は`poc/SleepRawRecordsScreen`、WBS 6.3以降は`SleepDetailScreen`のRecordsタブの`SleepRecordRow`）はRecordの`startZoneOffset`/`endZoneOffset`（レコードごとのタイムゾーン）で時刻を表示する一方、bucketの起点計算・最古レコード表示（当時は`poc/SleepGraphScreen`、WBS 6.2以降は同じ`SleepDetailScreen`のChartタブ）は既存のWeight/HeartRateと同じく`ZoneId.systemDefault()`（端末のタイムゾーン）を使っている。WBS 6.3でRecords/Chartの2タブが同じ`SleepDetailScreen.kt`に同居するようになった後も、この基準の違いは解消せず残っている。今回確認したデータは単一タイムゾーンでの記録のみのため両者の差は表面化していないが、タイムゾーン変更・夏時間の扱い（§10、D-027から引き継ぎの要検証事項）を実際に調べる際は、同じ画面内でもRecords（Recordごとの基準）とChart（端末の基準）でタイムゾーンの扱いが異なる点を踏まえる必要がある
-   **根拠**: 実機確認（Pixel 11、実データ、単一ソースのみ。2026/09/23・2026/05/20の非跨ぎ事例、2026/09/21〜22・2026/05/15〜16・05/17〜18・05/18〜19の跨ぎ事例（計6夜、0時より前の時間が4分〜86.5分の範囲）を、独立したHealth Connect本体アプリ（「データとアクセス」→「睡眠」→日別エントリ、`android.health.connect.action.HEALTH_HOME_SETTINGS`経由）の表示ともSession件数・時刻・長さを突き合わせて確認、2026-09-30）／要検証（比例按分がHealth Connectの実際の計算式そのものか近似的な一致か、境界をまたぐStage自体の扱い、AWAKE以外の非睡眠系Stageが同様に除外されるか、複数ソースでの重複処理＝Source priority）
-   **確認日**: 2026-09-30（初出、コードレビュー指摘を受けて3度訂正。1度目は根拠の計算誤りを修正、2度目は事例を追加してより強く裏付けられる仮説に更新、3度目は「Stage単位での按分」を検証もせずに棄却したものとして書いていた点と、按分の残差（最大8分）を丸め誤差だけで説明していた点を訂正）

### 6.10 期間の「1日あたり平均」は、分母の日数計算と実際のクエリ範囲の境界を暦日で揃えないと、境界付近でずれる

-   **知見**: ホーム画面（WBS 6.1）のSleepカードで、週/月/年タブの表示を「期間合計 ÷ 経過日数」の単純平均にした際、日数の数え方によって次の2種類のずれが実機で見つかった。(1) 経過時間（`Duration`）を単純に切り捨てて日数にすると、睡眠が明け方に集中するため、期間の途中（例: 週タブを水曜の朝に見る）では分子（月・火・水の約3晩分）に対して分母（切り捨てで2日）が小さすぎ、平均が実際より大きく（水曜朝で約1.5倍、火曜朝で約2倍）出た。(2) 日数を「開始日から今日までの暦日数（両端含む）」に直しても、履歴読み取り権限がない場合のクランプ開始時刻が「現在時刻から30日前」（時刻が日の途中）のままだと、分母の暦日数にはその境界日を含める一方、実際のクエリ範囲からはその日の朝の睡眠（境界時刻より前）が漏れ、平均が実際よりわずかに（約3%）小さくなった
-   **Viewerへの適用**: 単純平均の分母は「開始日から今日までの暦日数（両端含む）」で数える。あわせて、クランプが発生する場合はクランプ後の開始時刻そのものを暦日境界（翌日0時、切り上げ）に揃え、実際にクエリする範囲と分母の暦日数が指す範囲を一致させる（`HealthConnectManager.recentRangeFilterLocal()`と同じ「安全側に倒し切り上げる」考え方をInstantベースでも踏襲する）。「1日あたり平均」のような単純平均を実装する箇所全般に当てはまる注意点
-   **根拠**: 実機確認（Pixel 11、実データ。(1)は週タブを平日の朝に開いた際の表示から発見。(2)は`pm revoke`で履歴読み取り権限を外した状態の年タブと、範囲がほぼ一致するはずの月タブを比較し、分母を暦日境界に揃える前は両者の値が食い違い、揃えた後は一致することを確認、2026-09-30）
-   **確認日**: 2026-09-30

### 6.11 単一区間のAggregate（`aggregate()`、bucket分割なし）は、bucket分割版より高速だが、Yearタブ規模でも体感できる待ち時間がある

-   **知見**: ホーム画面（WBS 6.1）のHeartRate/Sleepカードは、グラフ用の`aggregateGroupByPeriod()`（6.8で計測）とは別に、選択期間全体を1区間として集計する`aggregate()`（`readHeartRateAggregateSummary()`/`readSleepAggregateSummary()`）を使っている。Pixel 11実機（履歴読み取り権限あり、年タブ＝当年1月から約9か月分の実データ）で計測したところ、両カードとも5〜11秒程度で読み込みが完了した（UIのテキスト変化をポーリングして確認したため誤差は数秒単位）
-   **Viewerへの適用**: 6.8が懸念していた「bucket数に比例して遅くなる」ケースには該当せず、クラッシュや実用に耐えないほどの遅さでもなかったが、数秒程度の待ち時間は体感できる。ホーム画面は画面復帰のたびに再取得する設計（lessons.md 3.1）のため、Graph画面から戻るたびにこの待ち時間が発生する。数百万件規模のHeart Rateデータ（6.7）などさらに記録頻度が高いデータやソース数が多い場合の所要時間は未計測
-   **根拠**: 実機確認（Pixel 11、実データ、履歴読み取り権限あり、年タブ＝約9か月分。UIのテキスト変化のポーリングで5〜11秒程度と確認、2026-09-30）／要検証（記録頻度がさらに高いデータでの所要時間、正確なミリ秒単位の計測）
-   **確認日**: 2026-09-30

### 6.12 週bucketへの粗粒度化は、`aggregateGroupByPeriod()`の所要時間をほとんど改善しない。所要時間の主要因はbucket数ではなく問い合わせ範囲の実データ量（total span）

-   **知見**: WBS 6.2で、6.8が示唆した「Heart Rateの1Yを週bucketにすれば十分速くなる」という仮説を、`HeartRateDetailScreen`に一時的な`SystemClock.elapsedRealtime()`計測（Logcat出力のみ、UIには表示しない）を追加してPixel 11実機（実データ、継続的な心拍数データ、Health Connectの継続バックグラウンド記録あり）で検証した。結果は次の通りで、bucket粒度を粗くしても所要時間は大きく改善しなかった:
    -   1ヶ月・日bucket（約30bucket）: 8.9秒
    -   6ヶ月・週bucket（約26bucket）: 30.1秒
    -   1年・週bucket（約52bucket）: 56.9秒
    -   全期間（約2.3年分）・月bucket（約28bucket）: 96.4秒
-   1年・週bucket（52bucket）が6ヶ月・週bucket（26bucket）のほぼ2倍の所要時間である一方、全期間・月bucket（28bucket、1年よりbucket数は少ないが問い合わせ範囲は2倍以上）はさらに長い。bucket数だけを見れば「全期間の28bucketは1年の52bucketより速いはず」と予想したくなるが、実際には全期間の方が遅い。これは、6.8で確認した「bucket数が多いほど遅い」という関係よりも、**「問い合わせ範囲（span）に含まれる実データ量」が所要時間の主要因である**ことを強く示唆する（6.8の「総データ量そのものは主要因ではない」という結論は、当時比較した2つの条件がほぼ同じ合計サンプル数だった特殊なケースに基づくもので、range長が異なる一般のケースには当てはまらない）。bucket粒度（DAY/WEEK/MONTH）は所要時間に対して二次的な影響しかなく、同じ範囲を粗い粒度で問い合わせても大幅な改善は見込めない
-   **Stepsとの対比**: 同じ実機・同じbucket粒度ロジック（`MetricDensity.HIGH`）で計測したStepsは、3ヶ月・週bucket 221ms、6ヶ月・週bucket 335ms、1年・週bucket 685ms、全期間・月bucket 5.0秒と、いずれもHeart Rateより2桁以上高速だった。Stepsは活動トリガーで記録される一方、このHeart Rateデータはバックグラウンドで継続的に記録されており、同じ「高頻度」データ型カテゴリでも実際のレコード密度は大きく異なり得る
-   **Viewerへの適用**: `MetricDensity.HIGH`（3M/6M/1Yで週bucket、ALLで月bucket）は実装として維持する（粒度を粗くすること自体は無意味ではなく、bucket数由来の追加コストは避けられる。また同じロジックでStepsは実用上十分高速だった）が、**継続的にバックグラウンド記録される高密度データ型（Heart Rate等）では、3M以上の期間で数十秒規模の待ち時間が生じ得ることは、bucket粒度の調整だけでは解消できない既知の制約として受け入れる**。読み込み中はスピナー＋「読み込み中…」表示のままで、メインスレッドはブロックされない（この待ち時間中もperiodタブの切替・操作は正常に反応することを確認済み）ため、ANRやフリーズには至らないが、体感の改善（キャッシュ・事前集計・進捗表示など）が必要になった場合はRoom導入（D-031）を再検討する材料とする
-   **根拠**: 実機確認（Pixel 11、実データ。`HeartRateDetailScreen`/`StepsDetailScreen`に一時的な`SystemClock.elapsedRealtime()`計測を追加し、Logcatで実測。計測後にコードは削除、2026-10-01）
-   **確認日**: 2026-10-01

### 6.13 ALLの最初のbucketを「1日あたり平均」に正規化する際、bucket自体の日数（暦月境界への切り捨てを含む）をそのまま分母にすると、実際の最古レコードより前の期間まで「記録なし」として薄めてしまう

-   **知見**: 決定事項5（Sleep/Stepsの週・月bucketを実カバー日数で割った1日あたり平均にする）を、`AggregationResultGroupedByPeriod`の`periodStart`/`periodEnd`（javapで`getEndTime()`の存在を確認済み）から計算した日数をそのまま分母にして実装したところ、Pixel 11実機の実データ（Sleep、最古レコード2024/05/27）のALL表示で、最初のbucket（2024年5月）が0.6時間/日という不自然に低い値になった。原因は、bucket境界を暦月に揃えるための`startOfMonth(oldestStart)`切り捨て（lessons.md 7.5）により、問い合わせ範囲自体が2024-05-01から始まるため、Health Connectが返す最初のbucketは`periodStart=2024-05-01`・`periodEnd=2024-06-01`（31日間）になり、実際にレコードが存在し得た5/27〜5/31（5日分）ではなく31日で割ってしまっていたため
-   これは決定事項5がそもそも解消しようとしていた問題（§27「記録開始月・当月など日数不足月が不自然に低く見える」）を、暦月境界への切り捨てという別の理由で再現してしまっていた
-   **Viewerへの適用**: 各Detail画面は、ALLの最古レコード時刻（`oldestStart`、`LocalDateTime`）を`SleepLoad`/`StepsLoad`に保持し、日数計算の際にbucketの開始時刻を`maxOf(bucket.periodStart, oldestStart)`にクランプしてから`daysCoveredBy()`に渡す（`SleepDetailScreen.perDayDuration()`/`StepsDetailScreen.StepsAggregateChart`）。ALL以外のperiod、および2番目以降のbucket（通常`periodStart >= oldestStart`のためクランプは実質無効）には影響しない。修正後、実機で同じ月が3.9時間/日相当に修正されることを確認した
-   **確認できていないこと**: 現在の実装は「最古レコードより前は記録がないと確定している」ALLの開始bucketだけをクランプ対象にしており、3M/6M/1Yのようなトレイリングウィンドウの範囲内でユーザーが単に記録していない期間（データの欠落なのか、記録自体をしていない期間なのか区別できない）は対象外のまま。これはHealth Connectのデータモデル上区別する手段がなく、意図的に対象外としている
-   **根拠**: 実機確認（Pixel 11、実データ、Sleep ALLの最初のbucketの表示値を修正前後で比較、2026-10-01）
-   **確認日**: 2026-10-01

### 6.14 「1日あたり平均」の日数計算は、進行中の最新bucketを「経過時間の割合」で扱うと整数丸めでも小数丸めでも別の不具合が起きる。「その日に少しでもかかっていれば1日と数える暦日数（両端含む）」が正しい

-   **知見（2回のコードレビュー指摘を経て訂正）**: `daysCoveredBy()`の日数計算は、進行中の最新bucket（問い合わせ終了時刻`now`が日の途中であるために生じる、端数を含むbucket）の扱いを2回間違えた。
    1.  **1回目の指摘**: 初版は`ChronoUnit.DAYS.between(periodStart, periodEnd)`で日数を整数に「floor」しており、実際のカバー期間に端数（例: 1.99日）があると切り捨てられて1日として扱われていた。分母が実際より小さくなるぶん、値が最大で約2倍近くまで高く出る
    2.  **1回目の対応（不十分）**: `Duration.between().toMillis()`ベースの小数日数（`Double`）に変更した。これにより整数floorの問題は解消したが、今度は進行中bucketのごく短い経過時間（例: 今日の8時間分＝0.33日）で割ることになり、その時点までの実績を1日分に外挿した値（例: 7時間の睡眠が21時間/日）が出る不具合を新たに生んだ
    3.  **2回目の指摘**: この外挿は、ホーム画面（D-033、lessons.md 6.10）で既に確立済みの「暦日数（両端含む）」という考え方（睡眠は明け方に集中するため、経過時間の割合で割ると境界付近で大きくぶれる。切り捨てでも小数でもなく、その日に少しでもかかっていれば1日と数える）と逆行していた
-   **Viewerへの適用**: `daysCoveredBy()`を、開始時刻は日付に切り捨て、終了時刻はちょうど0時でない限り翌日に繰り上げてから、2つの日付の差を数える方式（`Long`を返す）にした。進行中bucketは経過時間に関わらず必ず1日以上として数えるため外挿にならず、端数を切り捨てずに常に繰り上げるため分母が小さくなりすぎることもない
-   **根拠**: 実機確認（Pixel 11、実データ。Sleepの進行中の当月bucket（2026/10、8時間程度経過した時点）が、修正前は約21時間/日という明らかに異常な値になり、修正後は5:27という前後の月と同程度の値になることを確認、2026-10-01）。この結果は副次的に、`aggregateGroupByPeriod()`が進行中の最新bucketの`endTime`を問い合わせ終了時刻（`now`）に切り詰めて返している（月末まで延長した仮の値を返すのではない）ことも裏付けている。仮に切り詰めずに返していれば、暦日数（両端含む）方式でも分母が31日相当になり、5:27ではなく10分前後の値になっていたはずである（レビュー指摘）
-   **確認日**: 2026-10-01（初出2026-10-01、同日中にコードレビューで再訂正）

### 6.15 Customの開始日・終了日から機械的に決めた粒度がMONTHになる場合、開始日を暦月初へ切り捨てないと、グラフの月ラベルと実際のbucket境界がずれる。ALLで履歴読み取り権限がない場合も、フォールバック先のbucket粒度を問い合わせ範囲に合わせて再計算しないと同じズレが起きる

-   **知見**: WBS 6.2のコードレビューで指摘。(1) Customで1年を超える範囲を選ぶとMONTH bucketになるが、開始日を暦日切り捨て（`startOfDay`）にしか揃えていなかったため、bucketが「10/15〜11/15」のような月の途中区切りになり、x軸・一覧のラベル（`YearMonth`から生成）が示す「2023/10」等と実際の集計範囲が食い違っていた。(2) ALLで履歴読み取り権限がない場合、`oldestStart`から素直に月初へ切り捨てた開始日は直近30日より古くなりやすく、`HealthConnectManager`内部のSecurityException→直近30日フォールバックに入るが、フォールバック先でも呼び出し時に固定したbucket粒度（このケースではMONTH）がそのまま使われ続けるため、暦月に整列しない範囲を月bucketとして問い合わせてしまっていた
-   **Viewerへの適用**: (1) `resolveDetailGraphRange()`のCUSTOM分岐で、暦日切り捨てだけの開始日から仮のspanDaysを計算して粒度を先に決め、粒度がMONTHの場合のみ開始日を暦月初へ切り捨て直す2段階の解決にした。(2) ALL分岐に`historyPermissionGranted`を渡し、falseの場合は`oldestStart`の月初切り捨てと直近30日floorの遅い方（`maxOf`）を開始日に使うことで、`HealthConnectManager`内部のフォールバックに入る前に正しい（短い）spanDaysで粒度を決め直せるようにした。実機で、履歴読み取り権限を取り消した状態のStepsのALLが、月bucketではなく日bucketで正しく表示されることを確認した
-   **根拠**: 実機確認（Pixel 11、実データ。Customで2023/10/15〜2026/10/01（3年弱）を選び月bucket・暦月ラベルが一致することを確認。`pm revoke android.permission.health.READ_HEALTH_DATA_HISTORY`でStepsのALLが日bucketにフォールバックすることを確認、2026-10-01）
-   **確認日**: 2026-10-01
-   **2回目のコードレビューで見つかった残りの2ケース（修正済み）**:
    1.  **Customは履歴読み取り権限を考慮していなかった**: 上記(2)の修正はALLだけに入っており、Customでは未対応のままだった。権限がない状態でCustomに「選んだ範囲とは無関係な直近30日」が表示され（範囲は無関係なのに「履歴が制限されています」の通知だけは出る、という食い違った状態になる）、加えて1年を超える範囲を選んだ場合は(1)と同じ月境界のずれも再発していた。ALLと同じく、Customの開始日も履歴権限がない場合は直近30日floorにクランプするよう修正し、クランプの結果選んだ範囲が完全に表示不可能になった場合（`start >= end`）は、空の結果とともに「履歴読み取り権限がないため直近30日分のみ表示しています」の通知を出すようにした（`DetailGraphRange.Empty`に`historyLimited`フラグを追加）。実機で、権限を取り消した状態でCustomに2024年の範囲（直近30日から完全に外れる）を選ぶと、この通知とともに「この期間にレコードがありません」と正しく表示されることを確認した
    2.  **ALLで最古レコードの取得自体が失敗し、かつ履歴権限もない場合**: `oldestStart`がnull（`findOldestXxxRecordTime()`自体の失敗、既存の別経路）のときは開始無制限（`before(now)`）にフォールバックする設計だったが、これは履歴権限の有無を考慮していなかった。権限がない場合は、`oldestStart`が分からなくても直近30日floorを開始日として使うよう修正した（権限がある場合は従来通り開始無制限のまま、lessons.md 7.4の既存の考え方を維持）
-   **確認日（2回目）**: 2026-10-01

### 6.16 Jetpack Paging3の`PagingConfig.initialLoadSize`は既定で`pageSize`の3倍。Health Connectのpageトークンのように前方向限定のtokenを扱う自前`PagingSource`では、揃えないとページ境界がずれてレコードが欠落する

-   **知見**: `PagingConfig`の`pageSize`と`initialLoadSize`は別のパラメータで、`initialLoadSize`の既定値は`pageSize * 3`。初回load（REFRESH、ページ0）だけこの大きいサイズで読み込まれ、2回目以降のappend/prependは`pageSize`で読み込まれる。`HealthRecordsPagingSource`（WBS 6.3）はページ番号（Int）をkeyにし、「そのページを読むために渡したHealth Connectのtoken」を`pageStartTokens`に記録する設計だが、`initialLoadSize`を`pageSize`に揃えないと、「ページ0」の実際の件数（`pageSize * 3`件）と、`PagingConfig.maxSize`でページ0がUIから破棄された後にprependで再読み込みする際の件数（`pageSize`件）が食い違う。その結果、両者の差分に当たるレコード（例: pageSize=20なら21〜60件目）が、エラーも出さず静かに表示から欠落する
-   **Viewerへの適用**: `screen/detail/*DetailScreen.kt`のRecordsタブ用`PagingConfig`は、必ず`initialLoadSize`を`pageSize`と同じ値に明示する（`HealthRecordsPagingSource.kt`のコメント参照）。WBS 6.4以降で新たにPaging3を使う画面を追加する場合も同様に指定すること
-   **根拠**: 公式（Jetpack Paging3の`PagingConfig`APIリファレンス、`initialLoadSize`の既定値）／実機確認（Pixel 11、Heart Rateの実データ。`initialLoadSize`未指定のままHeart Rateの「全期間」で大きくスクロールしてから先頭へ戻ると、コードレビューの指摘通りレコードが欠落することを確認。`initialLoadSize`指定後は、同じ操作（最新から13時間超・1,000件超を下方向にスクロールしてから先頭まで戻す）を行っても、先頭の21件が1件も欠落せず元の内容と完全に一致することを確認、2026-10-01）
-   **確認日**: 2026-10-01
-   **関連・別問題**: この時点の確認は「スクロール後に先頭へ戻ったときの最終状態が一致するか」のみで、スクロールの途中経過（表示中の行がスクロール量に応じて連続的に変化しているか）までは確認していなかった。`LazyColumn`の`key`を省略していたことによる別の問題（スクロール中に表示内容が静かに入れ替わる）がコードレビューで指摘され、合わせて修正・確認した。詳細はlessons.md 6.17参照

### 6.17 `PagingConfig.maxSize`でページが破棄・再読込されるLazyColumnで`key`を省略すると、スクロール中に表示内容が静かに入れ替わり、一部のレコードを画面に表示しないまま通過しうる

-   **知見**: Jetpack ComposeのLazyColumn（`LazyPagingItems`経由も含む）は、`items()`に`key`を渡さない場合、各行を「現在の位置（index）」だけで識別する。`PagingConfig.maxSize`（`enablePlaceholders = false`）で先頭側のページが破棄されると、破棄された分だけ後続の全レコードのindexが詰まる（例: 50件のページが破棄されると、元のindex 200のレコードが新しいindex 150になる）。逆に先頭へのprependでページが挿入されると、既存レコードのindexが後ろにずれる。どちらの場合も、`LazyListState`（スクロール位置の保持）はkeyを渡さない限りindexだけを基準に「どのレコードを画面に表示しているか」を追従し続けるため、indexの意味が変わった後も同じindexを表示し続けてしまい、結果として画面内の表示内容がユーザーの操作なしに静かに入れ替わる（スクロール位置＝ピクセル位置は連続しているように見えても、表示される中身が別のレコードに変わる）。全件を検証可能にするというViewerの目的上、これによって一部のレコードが画面に表示されないまま通過しうる点が問題になる
-   **Viewerへの適用**: `metadata.id`はD-007（重複も含め全件表示）によりページをまたいで重複しうるため、そのままkeyには使えない。代わりに、`HealthRecordsPagingSource`（`healthconnect/HealthRecordsPagingSource.kt`）の各レコードを同ファイル内の`PagedRecord(pageIndex, indexInPage, value)`でラップし、`RecordsTab`側で`pagingItems.itemKey { "${it.pageIndex}:${it.indexInPage}" }`をkeyに使う。`(pageIndex, indexInPage)`はtoken境界が固定されている限り破棄・再読込をまたいでも安定するペアのため、重複IDでクラッシュせず、かつComposeがスクロール位置を正しいレコードに追従させられる。**ただし「token境界が固定されている限り」が前提で、閲覧中に過去時刻のレコードが後から同期・削除された場合はこの前提が崩れ、prependで読み直したページの中身が最初と変わりうる（同じキーの行に別のレコードが表示される）。この制約自体は未対応・未検証のまま残る**
-   **根拠**: 公式（Jetpack ComposeのLazyColumn `key`パラメータの役割に関するドキュメント。「keyを指定すると、要素の追加・削除があってもスクロール位置をそのkeyの要素に追従させる」という趣旨の記載）／実機確認（Pixel 11、Heart Rateの実データ。`key`省略のまま`maxSize`を超えるappend・prependを行った場合に表示内容が入れ替わりうることを設計上確認し、`(pageIndex, indexInPage)`のkey導入後、append方向に約280分（複数回のmaxSize破棄を含む）・prepend方向に同じ約280分を、画面の小刻みなスクロールごとにスクリーンショットを撮って1分刻みの連続性を確認したところ、欠落・重複・内容の入れ替わりが一度も発生しないこと、最終的に元の先頭21件と完全に一致する内容へ戻ることを確認、2026-10-01）
-   **確認日**: 2026-10-01

------------------------------------------------------------------------

### 6.18 Heart Rateのソース別`MEASUREMENTS_COUNT`（サンプル数）は、全件走査が安全な範囲ではRawから数えたサンプル数の単純合計と完全に一致する

-   **知見**: WBS 6.4（D-036の採用条件）で、継続記録される主ソース（Fitbit）について、`dataOriginFilter`で1ソースを指定したAggregateの`MEASUREMENTS_COUNT`と、同じ範囲・同じソースのRaw Recordをページングで全件読み、各レコードの`samples.size`を単純合計した値を比較した。全件走査が安全な直近7日間（9,895レコード・253,082サンプル）・直近30日間（41,376レコード・1,065,015サンプル）のいずれでも、両者は完全に一致した（差分0）。確認は一時的な診断コード（`HealthConnectManager.debugVerifyHeartRateSourceSampleCounts()`、確認後に削除済み）で行った
-   **Viewerへの適用**: D-036の「採用前に実機確認する」という条件を満たしたため、Heart Rateのデータソース画面（Sourcesタブ、WBS 6.4）は`MEASUREMENTS_COUNT`をそのまま採用し、ソース名のみへのフォールバックは発動させない（D-037）
-   **根拠**: 実機確認（Pixel 11、実データ。直近7日間・直近30日間の2つの範囲で完全一致を確認、2026-10-01）／要検証（多年規模の全件走査はOutOfMemoryErrorのリスクがあるため行っておらず（6.7）、この一致がより長い範囲でも成り立つかは未確認のまま残る。また複数ソースが同じ期間を重ねて記録した場合の挙動は、この検証時点で2つ目のソース「Google Fit」がこの2つの範囲に1件もレコードを持たなかったため確認できていない）
-   **確認日**: 2026-10-01

### 6.19 継続記録型の高密度データ型（Steps）でも、ソース別の正確なレコード件数を求める全件走査（Rawレコード自体は保持せず件数だけ集計）は、数十万件規模でも安全に完了する

-   **知見**: WBS 6.4で、Stepsの全期間（約2.5年分、5ソース、合計約30万件）のソース別レコード件数を、`Map<DataOrigin, Long>`に件数だけを集計しながら全件ページングして求めた（`HealthConnectManager.countBySource()`、レコード自体は各ページの処理後に参照を残さない）。実機でクラッシュせず、プロセスのメモリ使用量（RSS）も走査前後で大きな増加なく安定していた。一方で所要時間は軽視できず、Sourcesタブのタップから一覧が表示されるまで実測で約65秒かかった（約300回の`readRecords()`呼び出しを直列に発行するため）
-   **Viewerへの適用**: 6.7で確認したOutOfMemoryErrorは、Heart Rateのように1レコードに大量のサンプル配列を含みHealth Connect SDK内部の変換コストが高いデータ型・読み込み方法（全件をListに保持するRaw一覧）に起因するものであり、「レコード自体を保持せず件数だけを集計する」走査であれば、Stepsのような数十万件規模でも成立することを確認した。Weight/Steps/Sleepのデータソース画面（Sourcesタブ）は、Heart Rateのような代替指標（`MEASUREMENTS_COUNT`）に頼らず、全件走査による正確な件数を表示する設計にした（D-009、D-037）。**この3データ型を選んだ基準はレコード件数の多寡ではなく、サンプル配列を持たずSDK変換コストが低いかどうかである点に注意**（コードレビューで、初版のコメント・ドキュメントが「件数が少ないから全件走査する」という誤った基準を書いていたと指摘され訂正した。Stepsの約30万件はWeight・Sleepより2桁近く多い）。約65秒という所要時間は軽くはないため、コードレビューを受け、Sourcesタブの読み込み状態をタブ切替で破棄せず画面滞在中は保持する設計に変更した（タブを一度でも開けば、以降の往復では再走査しない。`screen/detail/DetailCommon.rememberLazyTabResult()`）
-   **根拠**: 実機確認（Pixel 11、実データ。Stepsの全期間Sourcesタブ表示でクラッシュなし、`ps`でのRSSが走査前後で大きく変化しないことを確認、タップから一覧表示までの所要時間を目視で計測し約65秒、2026-10-01）
-   **確認日**: 2026-10-01

### 6.20 connect-client 1.1.0は、プラットフォーム側のレート制限エラー（`ERROR_RATE_LIMIT_EXCEEDED`）をRemoteException/IOException/SecurityExceptionではなく`IllegalStateException`に変換する

-   **知見**: WBS 6.4のコードレビューで、「RemoteException/IOException/SecurityException以外の例外が出なかった」というこれまでの確認方法が、レート制限の検知手段として妥当かを問われた。`androidx.health.connect:connect-client:1.1.0`の実装クラス（`ExceptionConverterKt.toKtException()`）をbytecodeレベルで確認したところ、プラットフォーム側`android.health.connect.HealthConnectException.errorCode`から各Kotlin例外への変換は固定のswitchで行われており、`ERROR_INVALID_ARGUMENT`→`IllegalArgumentException`、`ERROR_IO`→`IOException`、`ERROR_SECURITY`→`SecurityException`、`ERROR_REMOTE`→`RemoteException`の4つ以外（`ERROR_UNKNOWN`・`ERROR_INTERNAL`・`ERROR_DATA_SYNC_IN_PROGRESS`・`ERROR_RATE_LIMIT_EXCEEDED`・`ERROR_UNSUPPORTED_OPERATION`を含む）は、すべてdefault分岐で`IllegalStateException`に変換されることが分かった。この変換は`wrapPlatformException()`という共通ヘルパー経由で`readRecords()`・`aggregate()`・`aggregateGroupByPeriod()`など主要なsuspend関数すべてに適用されている（同クラス内で13箇所から呼ばれている）
-   **Viewerへの適用**: `HealthConnectManager.readWithHistoryFallback()`（および`readStepsRecords()`等の個別のtry/catch）はRemoteException/IOException/SecurityExceptionの3種類しか捕まえない設計を意図的に維持している（D-035(7)と同じ「レート制限らしき例外を握りつぶさず表に出す」方針）。この調査により、レート制限が実際に発生した場合は`IllegalStateException`として未捕捉のままクラッシュする形で表面化するはずだと具体的に裏付けられた。したがって「大規模な操作（Stepsの全件走査約30万件・Heart Rateの複数回Aggregate呼び出し等）を行ってもクラッシュしなかった」ことは、レート制限が発生していないことの一定の根拠になる（クラッシュは目立つため見逃しにくい）。ただし`IllegalStateException`はレート制限以外の要因（`ERROR_INTERNAL`・`ERROR_DATA_SYNC_IN_PROGRESS`等）でも起こり得る分類のため、「クラッシュしなかった」ことも確定的な証明にはならない
-   **根拠**: 逆コンパイル（`javap -c`でconnect-client 1.1.0の`ExceptionConverterKt.toKtException()`のtableswitchを確認。errorCodeの整数値は`android-37.0/android.jar`の`android.health.connect.HealthConnectException`のconstant poolから取得: UNKNOWN=1, INTERNAL=2, INVALID_ARGUMENT=3, IO=4, SECURITY=5, REMOTE=6, RATE_LIMIT_EXCEEDED=7, UNSUPPORTED_OPERATION=9。`wrapPlatformException()`の呼び出し箇所をbytecodeで確認、2026-10-01）。非公開の実装詳細の逆コンパイルに基づくため、将来のライブラリバージョンで変換ロジックが変わる可能性がある点に注意
-   **確認日**: 2026-10-01

### 6.21 Health Connectへの誘導ボタンは、Playストアで実際に解決できるUPDATE_REQUIRED状態でのみ出す（旧NOT_INSTALLED＝UNAVAILABLE状態では出さない）

-   **知見**: 初版は`HealthConnectAvailability.NOT_INSTALLED`（未インストール）・`UPDATE_REQUIRED`（要アップデート）のどちらにもPlayストア誘導ボタンを出していたが、コードレビューで「`NOT_INSTALLED`は本当に『未インストール』なのか」と指摘され、6.23の`getSdkStatus()`のbytecode確認で誤りだったと判明した。実際にPlayストアへの誘導で解決するのは`UPDATE_REQUIRED`（API 28〜33限定、未インストール・無効化・バージョン古いのいずれか）のときだけで、`UNAVAILABLE`（旧名`NOT_INSTALLED`。minSdk 28のこのアプリでは実質的にAPI 34以降のwork profile・system service不在でしか発生しない）はPlayストアでインストール操作をしても解決しないシステム側の制約のため、ボタンを出すこと自体が誤った案内になる。UPDATE_REQUIRED側のボタンについては、公式のHealth Connect codelab/サンプルで使われているIntentの形（`Intent(Intent.ACTION_VIEW)`に`setPackage("com.android.vending")`、`data`に`market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding`、`putExtra("overlay", true)`、`putExtra("callerId", 自アプリのpackageName)`）1つで足りる（未インストール・無効化・バージョン古いのどのケースも、Playストア自身が端末の状態を見て「インストール」「アップデート」「開く」のボタンを出し分けるため）
-   **Viewerへの適用**: `HealthConnectManager.createOpenInPlayStoreIntent()`として実装し、`screen/common/HealthConnectUnavailableNotice.kt`が`availability == UPDATE_REQUIRED`の場合のみボタンを描画する（WBS 6.9、D-040・D-041）。`market://`のパッケージ名（`com.google.android.apps.healthdata`）は`AndroidManifest.xml`の`<queries>`宣言と同じ値のため、変更時は両方揃えて直す（manifest側にコメントで相互参照を追記済み）
-   **根拠**: 公式（Android公式のHealth Connect codelab/サンプルで広く使われている実装パターン）／実機確認（Pixel 11、`availability`を一時的にUNAVAILABLE・UPDATE_REQUIREDそれぞれへ固定する診断コードで、UNAVAILABLEではボタンが出ないこと、UPDATE_REQUIREDではボタンが出てタップ時に実際にIntentが発行されること（Health Connectインストール済みのためそのままHealth Connect本体へ遷移）を確認。確認後に診断コードは削除済み。2026-10-01）
-   **確認日**: 2026-10-01

### 6.22 `setPackage()`を指定した明示的なIntentでの`startActivity()`は、対象パッケージを`<queries>`で宣言していなくてもAndroid 11+のパッケージ可視性制限の影響を受けない

-   **知見**: 6.21のPlayストア誘導Intent（`setPackage("com.android.vending")`）をコードレビューで指摘され、「`AndroidManifest.xml`の`<queries>`は`com.google.android.apps.healthdata`のみ宣言しており`com.android.vending`は宣言していないため、Playストア経由でインストールされていないビルド（`installerPackageName`が自動的に`com.android.vending`にならない場合）ではAndroid 11+のパッケージ可視性制限により`ActivityNotFoundException`で失敗するのではないか」という懸念が出た。実機で意図的に最悪条件（`adb install`でインストールしたデバッグビルド、`dumpsys package`で`installerPackageName=null`・`targetSdk=37`を確認済み＝Playストアを発行元とする自動可視性の例外が効かない条件）を作り、`availability`を一時的にUPDATE_REQUIREDへ固定してアプリ内の実際のボタンをタップしたところ、`<queries>`に`com.android.vending`を宣言していないにもかかわらず、例外なく`com.android.vending`側のActivity（`MarketDeepLinkHandlerActivity`）へ正常に遷移した（logcatで`result code=0`、クラッシュなしを確認）。パッケージ可視性制限は`queryIntentActivities()`等のPackageManager照会メソッドの結果を絞り込む仕組みであり、`setPackage()`で対象を明示した`startActivity()`（対象が1つに定まる明示的なIntent）はこの絞り込みの対象外と考えられる
-   **Viewerへの適用**: `HealthConnectManager.createOpenInPlayStoreIntent()`に対し、`com.android.vending`を`AndroidManifest.xml`の`<queries>`へ追加する対応は行っていない（上記の実機確認により、Viewerの配布形態＝Google Play経由でのインストールが前提の実運用ではなおのこと問題にならないと判断）。ただし2回目のコードレビューで「Playストアが無効化・未搭載の端末ではこのIntent自体が解決に失敗し、ボタンが無反応になる」という別の懸念を受け、`setPackage()`なしの`https://play.google.com/store/apps/details?id=...`へのフォールバックと、それも失敗した場合の案内テキストを追加した（`HealthConnectManager.createOpenInPlayStoreWebIntent()`、`HealthConnectUnavailableNotice.kt`）。**同種の「明示的package名を指定したIntentが`<queries>`未宣言で失敗するのでは」という指摘を今後受けた場合、まずこの知見を参照し、必要なら同じ方法（一時的な診断コードでの実機確認）で確かめてから対応すること**（`<queries>`の追加自体は害がないが、不要な変更を加える前に実際に問題が起きるか確認する）
-   **根拠**: 実機確認（Pixel 11、Android 17、`adb install`でのサイドロードビルド＝`installerPackageName=null`・`targetSdk=37`という、パッケージ可視性制限が最も厳格にかかる条件下で確認。2026-10-01）。Android公式ドキュメントの記述まで裏付けを取ったものではなく、この実機確認1件に基づく理解のため、将来のAndroidバージョンで挙動が変わる可能性は残る
-   **確認日**: 2026-10-01
-   **注意**: Android 13以前かつGoogle Playが使える環境でのUPDATE_REQUIRED状態そのものの実機確認（ボタン表示・タップ後の遷移）は、手元に該当環境がなく未確認のまま残る（チェックリスト8参照）

### 6.23 `HealthConnectClient.getSdkStatus()`の戻り値の意味はAndroidバージョンで大きく異なる。`SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED`はAPI 28〜33限定、`SDK_UNAVAILABLE`はminSdk 28の本アプリでは実質API 34以降専用

-   **知見**: WBS 6.9のコードレビューで「NOT_INSTALLED（旧実装、現UNAVAILABLE）が本当に『未インストール』を意味するのか」という指摘を受け、`connect-client-1.1.0.aar`内の`classes.jar`から`HealthConnectClient$Companion.class`・`HealthConnectClient$Api34Impl.class`を`javap -c -p`で逆コンパイルして確認した。結果、`getSdkStatus()`の分岐は次の通りだった。(1) **API 34以上**: `Api34Impl.getSdkStatus()`に委譲し、`UserManager.isProfile()`（work profile内）が`true`、または`context.getSystemService("healthconnect")`が`null`（Health ConnectのSystem Service自体が存在しない）のいずれかなら`SDK_UNAVAILABLE`(1)を返す。それ以外は`SDK_AVAILABLE`(3)。**`SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED`(2)を返す分岐はAPI 34以上には一切存在しない**。(2) **API 28〜33**: `isPackageInstalled()`でHealth Connectアプリの状態を確認する。`PackageManager.getPackageInfo()`で見つからない（`NameNotFoundException`）、見つかっても`ApplicationInfo.enabled == false`、パッケージ名がデフォルトのプロバイダ名と一致する場合はさらにバージョンコードが最小要件未満、のいずれかなら即座に`false`。ここまでを満たしても、**最後に`hasBindableService$connect_client_release()`（`action = "androidx.health.ACTION_BIND_HEALTH_DATA_SERVICE"`で`setPackage()`した`Intent`を`PackageManager.queryIntentServices()`に渡し、bindできるServiceが1件以上見つかるか）を必ず確認しており、これが`false`なら他の条件をすべて満たしていても`false`になる**。`isPackageInstalled()`が`false`なら`SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED`(2)、`true`なら`SDK_AVAILABLE`(3)。**`SDK_UNAVAILABLE`(1)を返す分岐はAPI 28〜33には一切存在しない**（この範囲内では必ず2か3のどちらか）。(3) **API 28未満**: 常に`SDK_UNAVAILABLE`(1)（ただし本アプリはminSdk 28のためこの分岐には到達しない）。まとめると、本アプリ（minSdk 28）が`SDK_UNAVAILABLE`(1)を観測するのは実質的にAPI 34以降のwork profile・system service不在の場合のみで、`SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED`(2)はAPI 28〜33限定で「未インストール」「無効化」「バインド可能なサービスが無い」「バージョン古い」のすべてを一纏めに表す
-   **Viewerへの適用**: `HealthConnectAvailability`のenum値を`NOT_INSTALLED`→`UNAVAILABLE`に改名し、SDK_AVAILABLE→INSTALLED、SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED→UPDATE_REQUIRED（Playストア誘導が有効）、それ以外（SDK_UNAVAILABLE）→UNAVAILABLE（Playストア誘導は無効、テキストのみ）という対応に修正した（WBS 6.9、D-041。6.21参照）。`HealthConnectManager.availability`・`HealthConnectAvailability.kt`にこの対応関係をコメントで残してある
-   **根拠**: 逆コンパイル（`javap -c -p`で`androidx.health.connect:connect-client:1.1.0`の`HealthConnectClient$Companion.getSdkStatus(Context, String)`・`HealthConnectClient$Api34Impl.getSdkStatus(Context)`のbytecodeを確認。AARは`~/.gradle/caches/modules-2/files-2.1/androidx.health.connect/connect-client/1.1.0/`配下から取得。2026-10-01）。非公開の実装詳細の逆コンパイルに基づくため、将来のライブラリバージョンで分岐が変わる可能性がある点に注意（D-034等、他のバイトコード確認知見と同じ留意点）
-   **確認日**: 2026-10-01

### 6.24 Health Connect SDKの単位クラス（`Mass`/`Length`等）のKotlinプロパティ名は、`javap`で見えるJVMメソッド名と異なることがある

-   **知見**: WBS 6.10でDistance用のAggregateMetric（`DistanceRecord.DISTANCE_TOTAL`、型は`Length`）をkmへ変換するプロパティ名を確認するため、`connect-client-1.1.0-api.jar`の`Length.class`を`javap -p`で逆コンパイルしたところ、`getKilometers()`/`getMeters()`しか見つからず、既存コード（`WeightAggregateBucket`等）が使っている`Mass.inKilograms`と同じ命名規則（`inXxx`）のメソッドが存在しないように見えた。`strings`コマンドで`.class`ファイルのバイナリを直接検索したところ、`kotlin.Metadata`アノテーションの文字列プール内に`inKilograms`/`inKilometers`/`inMeters`という文字列が実際に埋め込まれていることが分かった。これはKotlinコンパイラが`@JvmName`等でJVM上のメソッド名（`getKilograms()`）とKotlinソース上のプロパティ名（`inKilograms`）を意図的に分けているためで、`javap`はJVMメソッド名しか表示しないためこの分離を見落とす
-   **Viewerへの適用**: Health Connect SDKの単位クラス（`Mass`/`Length`/`Energy`/`Power`等）のプロパティ名を`javap`だけで調べると誤った結論（「`inXxx`という名前のプロパティは存在しない」）に至る。正しいKotlinプロパティ名を確認するには、(1) 既存コードで同じ系統のクラス（`Mass.inKilograms`等）がどう呼ばれているか確認する、(2) `.class`ファイルのバイナリを`strings`/`grep -a`で直接検索し、`kotlin.Metadata`の文字列プールに含まれる名前を探す、のいずれかを使う。`Length`の場合は`inMeters`/`inKilometers`/`inMiles`/`inInches`/`inFeet`、`Energy`の場合は`inCalories`/`inKilocalories`/`inJoules`/`inKilojoules`、`Pressure`の場合は`inMillimetersOfMercury`が実際のKotlinプロパティ名だった（`Energy`はWBS 6.10のCalories実装時にD-043(3)、`Pressure`はWBS 6.10のBlood Pressure実装時にD-045(4)で確認）
-   **根拠**: 実機非依存の逆コンパイル確認（`androidx.health.connect:connect-client:1.1.0`の`units/Mass.class`・`units/Length.class`・`units/Energy.class`・`units/Pressure.class`を`javap -p`、および`grep -a -o`でバイナリ内の文字列を直接検索。2026-10-01〜2026-10-02）
-   **確認日**: 2026-10-01（`Pressure`は2026-10-02追記）

### 6.25 Health Connect上の「Calories」は`ActiveCaloriesBurnedRecord`/`TotalCaloriesBurnedRecord`という2つの独立したRecord型で、実機では記録頻度・ソース構成がDistance/Stepsと異なる

-   **知見**: WBS 6.10でCalories（requirements.md §22.2の「Total / Activeの区別あり」）を実装するにあたり、`javap`でHealth Connect SDKの`records`/`permission`パッケージを逆コンパイルして確認したところ、1つのRecord型にTotal/Active 2つのフィールドがあるのではなく、`ActiveCaloriesBurnedRecord`（`ACTIVE_CALORIES_TOTAL`）と`TotalCaloriesBurnedRecord`（`ENERGY_TOTAL`）という、それぞれ独立したAggregateMetricを持つ2つのRecord型だと判明した（D-043(1)）。権限文字列も`READ_ACTIVE_CALORIES_BURNED`/`READ_TOTAL_CALORIES_BURNED`とそれぞれ別になる。Pixel 11実機の権限リクエストダイアログでも、両方とも「アクティビティ」カテゴリの権限として個別に表示されることを確認した。この端末の実データでは、`TotalCaloriesBurnedRecord`は「Health」ソース（15分間隔、1件あたり約10〜60kcal）・「Fit」ソースの計約17万件（Health 90,722件・Fit 81,738件）が最古2023/04/18から存在したが、`ActiveCaloriesBurnedRecord`を書き込むソースはこの端末になく、実データでの記録頻度・1件あたりの値の大きさは確認できなかった。`TotalCaloriesBurnedRecord`の15分間隔という記録頻度は、Distance/Steps（Fitソースで約30秒間隔、6.6・WBS 6.10参照）より大幅に粗く、同じ「継続記録型のActivity系データ」でもデータ型・ソースによって粒度が大きく異なることが分かった
-   **Viewerへの適用**: 表示単位はDistanceのkm/m使い分け（D-042(2)）のような対応をせず、集計値・Recordsタブの個々のレコードともkcalに統一し、Recordsタブは小数1桁で表示する設計にした（D-043(4)）。`TotalCaloriesBurnedRecord`の実データ（1件あたり約10〜60kcal）ではこの小数1桁で「0.0 kcal」のような潰れは起きないことを確認できたが、`ActiveCaloriesBurnedRecord`は未確認のまま残っており、将来実データで確認できた際に1件あたりの値が極端に小さい場合は同じ潰れが起きる可能性がある（要検証）。Sourcesタブの全件走査（`readSourceRecordCounts<T>()`の再利用）は`TotalCaloriesBurnedRecord`の約17万件規模でクラッシュ・メモリ増大なく完了することを確認したが、`ActiveCaloriesBurnedRecord`では未確認のまま残る
-   **根拠**: 逆コンパイル（`connect-client-1.1.0-api.jar`の`records/ActiveCaloriesBurnedRecord.class`・`records/TotalCaloriesBurnedRecord.class`を`javap -p`、`permission/HealthPermission$Companion.class`を`javap -p -c`で一般的な権限文字列の組み立てロジック（`"android.permission.health.READ_"`+マップから引いた接尾辞）を確認した上で、`permission/HealthPermission.class`を`grep -a`でバイナリ内の文字列を直接検索し実際の接尾辞`ACTIVE_CALORIES_BURNED`/`TOTAL_CALORIES_BURNED`を確認）と実機確認（Pixel 11、実データ、権限ダイアログのカテゴリ表示、ホーム画面カード・詳細画面のChart/Records/Sourcesタブの動作、2026-10-01）
-   **確認日**: 2026-10-01

### 6.26 `TotalCaloriesBurnedRecord.ENERGY_TOTAL`（Aggregate）は、レコードが1件も存在しない期間でもnullではなく非null値を返すことがある

-   **知見**: コードレビューで「Health Connectのプラットフォーム側がTotal CaloriesをActive Calories＋基礎代謝（BMR）から補完している可能性があり、その場合レコードが無い期間でもAggregateがnullを返さないのではないか」という指摘を受け、一時的な診断コード（確認後に削除済み）で検証した。(1) 実際のレコードが存在しない期間（`TotalCaloriesBurnedRecord`の最古レコードは2023/04/18だが、2010年の1日分を指定）で`client.aggregate(AggregateRequest(metrics = setOf(ENERGY_TOTAL), timeRangeFilter = ...))`を呼んだところ、nullではなく約1,565kcalという非null値が返った。(2) 一方、実際にレコードがある日（1ソース、直近の1日、96件・15分間隔）で、その1ソースに`dataOriginFilter`で絞ったAggregateの値と、同じ範囲のRawレコード（`readRecords()`）の`energy.inKilocalories`単純合計を比べたところ、完全に一致した（約2,019.6kcal、小数点以下まで一致）。**この(2)の一致は「24時間すべてレコードで埋まっている1日」「`dataOriginFilter`で1ソースに絞った」という狭い条件でのみ確認したもので、過大な一般化はできない**（2回目のレビュー指摘）。ホーム画面・詳細画面が実際に使うのは`dataOriginFilter`を指定しない呼び出しであり、そちらでも同様に一致するかは未確認。また、ソースが途中で止まった日のようにレコードが一部だけ欠けている期間で、欠けた部分が推計で補われて合計が底上げされないかも未確認。後者の推計がAndroid 14+のプラットフォーム側でActive Calories・基礎代謝等から補完されたものかどうかは、Health Connect本体（非公開実装）の挙動のため、このJetpackクライアント側の逆コンパイルでは確認できず未確定のまま残る
-   **Viewerへの適用**: 他のAggregateMetric（Distance、Steps等）やこのアプリの既存コメントが前提としてきた「問い合わせ範囲にレコードが1件もなければAggregateはnullになる」（`AggregationResult.get()`の仕様として説明していた挙動）が、`TotalCaloriesBurnedRecord.ENERGY_TOTAL`には当てはまらない。この前提に依存していた`isTotalCaloriesCardHidden()`（HomeScreen.kt、「データがない項目は非表示」設定）は、実際にはレコードが無い期間でも非null値が返るせいで意図通りカードを隠せず、Records/Sourcesタブには何もないのにホームのカードには値が出る、という食い違いが起きる。この食い違いは「推計の正確な発生条件」が分からなくても「その期間に実レコードが1件でもあるか」という事実で切り分けられるため、`HealthConnectManager`に`hasAnyRecord()`（`readRecords(pageSize = 1)`で存在確認するだけの軽量な関数）を追加し、`readTotalCaloriesAggregateTotal()`（ホーム画面カード用、1回の呼び出しで済む）でAggregateを呼ぶ前に範囲内の実レコードの有無を確認するよう修正した（2回目のレビュー指摘を受けて対応、D-043(6)）。
    詳細画面のChart用`readTotalCaloriesAggregates()`にも同じガードをbucketごとに追加することを試みたが、**実機で計測したところ、ガードを入れる前の時点でALL期間（MONTH bucket、43個）の`aggregateGroupByPeriod()`自体が単独で約77秒かかっており**（`System.currentTimeMillis()`による計測、一時的な診断コードは確認後に削除済み。Heart RateのALL期間96秒・lessons.md 6.12に匹敵する遅さで、同じく「bucket数ではなく問い合わせ範囲の実データ量が主要因」という知見と整合する。独立した既知の問題としてrequirements.md §27に記録した）。この77秒という素の状態に対し、bucketごとのガードを実際に追加して計測したところ合計約45秒となり、**数字の上ではガードを入れたほうが速いという結果になった**。これはガードの効果を示すものではなく、2回の計測の間で実行環境が変わったことによる誤差と見られ、「並行化しても悪化するだけ」という当初の判断根拠は誤りだった（3回目のレビュー指摘）。ガード追加の所要時間への実際の影響は、計測が安定せず確認できていない。それでも追加を見送ったのは、素の状態で既に77秒という遅さの操作に、効果を確証できないままbucket数分のIPC呼び出しを足すべきではないと判断したため。このグラフのALL期間はfindOldestTotalCaloriesRecordTime()で求めた最古レコード時刻が開始点のため、影響は「記録を始める前」ではなく「記録期間中に記録が途切れた日・週・月」に限られる。そのため、詳細画面のChartでは記録が1件もないbucketでも非null値が描画され得る問題が未解消のまま残っている。`ActiveCaloriesBurnedRecord`はこの端末にデータがなく、同じ推計が起きるかを確かめたこと自体がない（未確認。何も問題が観測されていない、という意味ではない）。未確認のため根拠のないガードは足さないという考えで、`hasAnyRecord()`のガードはTotal Caloriesのホーム画面カードにのみ適用している
-   **根拠**: 実機確認（Pixel 11、実データ。一時的な診断コードで`AggregateRequest`の結果・所要時間をlogcatへ出力し確認、2026-10-01）。非公開のプラットフォーム側実装の挙動を外部から観測した結果であり、正確な計算式・発生条件・所要時間は未確定（計測のたびに大きくばらついた）
-   **確認日**: 2026-10-01

### 6.27 `BodyFatRecord`には公式の`AggregateMetric`が存在しない。Chartのbucket集計はRawレコードを自前で行う

-   **知見**: WBS 6.10でBody Fatを実装するにあたり、`WeightRecord.WEIGHT_AVG/MIN/MAX`に相当するAggregateMetricが`BodyFatRecord`にあるかを確認するため、`connect-client-1.1.0-api.jar`の`BodyFatRecord.class`・そのCompanionを`javap -p`で逆コンパイルしたが、`AggregateMetric`型のstatic fieldが一切見つからなかった。jarファイル全体を`AggregateMetric<Percentage>`のシグネチャで`javap -p`＋`grep`検索しても該当クラスは0件で、`BodyFatRecord`には本当に公式Aggregate Metricが存在しないと確認できた（requirements.md §22.2が「Body Fat / Blood Glucose / SpO2 / HRV」の行で「要確認」としていた内容がBody Fatについて確定した）。これはWeight/Steps/Distance/Heart Rate/Resting Heart Rate/Sleep/Calories/Blood Pressureまでの8データ型すべてに公式AggregateMetricが存在していたのとは異なる、初めての例外。あわせて`Percentage`型（`percentage: Percentage`が`BodyFatRecord`の唯一の値フィールド）のKotlinプロパティ名を`.class`バイナリの文字列直接検索で確認したところ、`Mass.inKilograms`等と異なり`@JvmName`差し替えが**されておらず**、単純に`value`（javapで見える`getValue()`のまま）だった。`<init>`/`<clinit>`のバイトコードから、`percentage.value`は0〜100の範囲（`requireNonNegative`・`requireNotMore(..., 100)`で検証）で、0〜1のfractionではないことも確認した
-   **Viewerへの適用**: 詳細画面のChart用集計（`HealthConnectManager.readBodyFatAggregates()`）は、`aggregateGroupByPeriod()`を呼ぶ既存7データ型の実装（Weightの`readWeightAggregates()`等）を複製できず、Rawレコードを全件走査してアプリ側でbucket集計する新規ロジックとして実装した（D-046(3)）。`resolveDetailGraphRange()`が返す`TimeRangeFilter`はLocalDateTimeベース（`aggregateGroupByPeriod()`向け、lessons.md 6.5）だが、`readRecords()`での全件走査にはInstantベースのfilterが必要なため、`TimeRangeFilter.localStartTime`/`localEndTime`（公開プロパティ、javapで`isBasedOnLocalTime$connect_client_release()`のような`$`付きinternal関数ではないことを確認済み）を`ZoneId.systemDefault()`でInstantへ変換してから読み取る。bucket境界は`aggregateGroupByPeriod()`の実際の挙動（lessons.md 7.5: 開始時刻を起点にPeriod単位で機械的に等間隔区切り）を自前で再現し、レコードが1件もないbucketもnullのまま残す（`WeightAggregateBucket`と同じ「0で埋めない」方針）。この自前集計パターンは、今後Blood Glucose / SpO2 / HRV（同じく§22.2で「要確認」）を実装する際、javapで同じAggregateMetric有無チェックをした上でそのまま再利用できる見込み。なお`percentage.value`が既に0〜100スケールのため、0〜1スケール前提の`NumberFormat.getPercentInstance()`は使わないこと（22.5%のつもりが2250%になる）
    **コードレビュー指摘（2026-10-02、1回目）**: 読み取り範囲のInstant変換は端末のタイムゾーン（`ZoneId.systemDefault()`）基準だが、bucket割り当てはレコード自身の`zoneOffset`基準のため、両者が食い違うレコード（旅行先での記録、オフセットをUTCで書き込むアプリ等）では、本来含めるべきレコードが読み取り範囲の端で漏れる、または範囲外のレコードが最後のbucketに混入しうるという指摘を受けた。Health Connectのタイムゾーンオフセットの取り得る範囲（UTC-12〜UTC+14、最大スプレッド26時間）分だけ読み取り範囲を前後に広げて読み取り、bucket割り当て時にレコード自身のzoneOffsetで計算した現地時刻が実際にrangeStart〜rangeEndへ収まるものだけを対象にするよう修正した。
    **コードレビュー指摘（2026-10-02、2回目）**: 上記の「26時間広げる」修正が、履歴読み取り権限が無い場合に新たな不具合を生んでいるという指摘を受けた。権限が無い場合の開始時刻（`resolveDetailGraphRange()`の`recentFloorLocal()`、または`readWithHistoryFallback()`のfallbackFilter＝`recentRangeFilterLocal()`）は、`Instant.now() - HISTORY_FALLBACK_DAYS`という境界ぎりぎりまで寄せた値（安全マージンは最大24時間、`now`の時刻帯によっては0時間に近い。lessons.md 6.1）になっているため、26時間を無条件に引くと境界を超えて`readRecords()`が`SecurityException`になりうる。しかもfallback自体も同じ`readAllRecords()`を通って同様に広げられるため、`readWithHistoryFallback()`の安全網（30日分へのフォールバック）まで一緒に失敗し、`BodyFatAggregatesResult.Failure`（Chartがエラー表示）になっていた。実機確認が済んでいたPixel 11は権限許可から日数が経っていたため再現せず、**権限を許可した直後（インストール直後の初回体験を含む）でのみ顕在化する**、実機確認でも見逃しやすい不具合だった。`historyPermissionGranted`がfalseの間は、広げた開始時刻を`Instant.now().minus(HISTORY_FALLBACK_DAYS, DAYS)`でクランプ（下限）するよう修正した。これにより境界ぎりぎりの一部レコードを取りこぼす可能性は残るが、これは履歴読み取り権限が無い場合の既存の30日近似（lessons.md 6.1）が元々許容している誤差の範囲に収まる。
    **コードレビュー指摘（2026-10-02、3回目）**: 上記2回目の修正が、`historyPermissionGranted`という「呼び出し元のBodyFatDetailScreenがホーム画面遷移時点でスナップショットした値」に頼っているため、詳細画面を開いた後に履歴読み取り権限が取り消された場合（lessons.md 3.1・6.1が想定するケース）に直らないという指摘を受けた。この場合`historyPermissionGranted`はtrueのまま最初の読み取りが`SecurityException`になり、`readWithHistoryFallback()`が`recentRangeFilterLocal()`で読み直すが、`historyPermissionGranted`の値だけで判定するクランプはtrueのflagに引きずられてfallback側には適用されず、fallbackの開始時刻（境界ぎりぎりまで寄せた値）が26時間広げられたままになって、境界を超えて2回目も`SecurityException`になり`Failure`（Chartエラー表示）になっていた。Weightは同じ状況でも（広げる処理自体が無いため）直近30日分へのフォールバック表示に成功するのに対し、Body Fatだけがエラーになるという非対称が生じていた。判定を`historyPermissionGranted`というflagではなく、「広げる前の開始時刻が実際にHISTORY_FALLBACK_DAYS（30日）の内側にあるかどうか」という値そのものに変更した（内側にある場合のみ、広げた結果を`recentFloor`でクランプする）。この判定方法なら、historyPermissionGrantedが不正確（取得中に取り消された等、呼び出し元のスナップショットが古い）であっても、最初の読み取り・fallbackの読み取りのどちらでも正しくクランプされる（D-046、本プロジェクトで自前集計を実装する際の再利用可能な注意点として記録。公式`aggregateGroupByPeriod()`自体がタイムゾーンをどう扱うかは別途requirements.md §10で未検証のまま残っている）
-   **根拠**: 実機非依存の逆コンパイル確認（`connect-client-1.1.0-api.jar`の`records/BodyFatRecord.class`・`records/BodyFatRecord$Companion.class`・`units/Percentage.class`を`javap -p -c`、jar全体を`grep -a`でバイナリ内の文字列・シグネチャを直接検索。2026-10-02）
-   **確認日**: 2026-10-02

## 7. グラフ描画（Vico、WBS 2.4）

### 7.1 Vico 3.xはMaterial3のカラースキームに自動追従できる

-   **知見**: `com.patrykandpatrick.vico:compose-m3`の`rememberM3VicoTheme()`が返す`VicoTheme`を`ProvideVicoTheme(theme) { ... }`でラップするだけで、軸・線などグラフの既定色がアプリのMaterial3カラースキーム（ライト/ダーク）に追従する。線の色などを個別に指定しなくても、要件§12（テーマ System/Light/Dark）に自然に対応できる
-   **Viewerへの適用**: `WeightGraphScreen`の`WeightAggregateChart`で採用済み。グラフに独自の配色を持たせる場合を除き、今後追加するグラフもこの`ProvideVicoTheme`でラップする方針を基本とする
-   **根拠**: 実機確認（Pixel 11、ダークモードでの表示を確認。エミュレータ（Pixel API 36）では`cmd uimode night yes/no`でライト/ダーク両方の配色追従をスクリーンショットで確認、2026-09-30）
-   **確認日**: 2026-09-30

### 7.2 CartesianChartのXは自由な数値でよく、bucket配列の並び・欠落に依存させずに済む

-   **知見**: `LineCartesianLayerModel`の`series(x, y)`のxは、bucket配列のインデックスのような連番である必要はなく、`LocalDate`のepoch day（`toEpochDay()`）や年×12+月のような絶対値をそのまま渡せる。`CartesianValueFormatter`側もx値（Double）から同じ計算を逆算して日付を再構成できるため、bucket配列そのものを参照しなくてよい
-   **Viewerへの適用**: 当初はbucket配列のインデックスをxに使い、値がないbucketを配列から除外する実装にしていたが、これはHealth Connectの`aggregateGroupByPeriod()`が「値のないbucketも1件として返す」（欠落なく1期間1件）ことに暗黙に依存しており、実際にそうなるかは未検証だった（レビュー指摘）。bucketが欠落して返っても日付がずれないよう、各bucketの`periodStart`から計算した絶対値をxにする設計に変更した（`GraphPeriod.xValue()`/`dateFromXValue()`）。値がないbucket（§10「月・年bucketで値がない期間の表示」）は3系列（平均・最小・最大）とも対象から除外しており、値がない期間は「点を打たない」＝前後の点が線でつながる形になる。この見え方をそのままMVPで採用するかはWBS 6.2で改めて検討する
-   **根拠**: 公式（`series(x, y)`のシグネチャ自体はxの意味に制約を課しておらず、絶対値を渡すこと自体は問題ない）／実機確認（Pixel 11。実データには記録のない日が複数あったが、1M/1Y/ALLいずれもクラッシュせず、値のない日を飛ばして前後の点が線でつながる形で描画されることを確認、2026-09-30）／要検証（`aggregateGroupByPeriod()`が値のないbucketを実際に1件として返すか、何も返さないか自体はどちらの場合でもViewer側の実装が正しく動くため未確認のまま。実データでの見え方の妥当性はWBS 6.2で改めて評価する）
-   **確認日**: 2026-09-30

### 7.3 横スクロールは追加実装なしで機能する。365bucket規模までは描画・スクロールとも確認済み。10年規模・ピンチズームは引き続き未確認

-   **知見**: `CartesianChartHost`は`VicoScrollState`/`VicoZoomState`を持ち、コンテンツが画面幅を超える場合の横スクロールは追加実装なしで有効になる
-   **Viewerへの適用**: PoC 1（Weight）では1M/1Yの日bucket表示（数十bucket程度）でのスワイプ操作・横スクロールをPixel 11実機で確認した。PoC 3（Heart Rate）ではさらに大きい規模（1Y＝日bucket365点、ALL＝月bucket数十点）で実データを描画し、いずれもクラッシュ・描画崩れなく表示・横スクロールできることを確認した（bucket数自体が多くてもVicoの描画性能は問題にならなかった）。所要時間が長いのは6.8の通り`aggregateGroupByPeriod()`側の集計コストであり、Vicoの描画コストではないという点も、`readHeartRateAggregates()`呼び出し単体の実測時間（6.8）がタップからUI更新までの体感時間とほぼ一致したことから、実測で裏付けられた（Vicoの描画自体にかかる時間は無視できるほど短いとみられる）。ただし要件が想定する10年規模（数千bucket）はまだ確認できておらず、ピンチズームも複数指ジェスチャーが必要でadb経由では引き続き未検証（要検証）
-   **根拠**: 実機確認（Pixel 11。Weight実データでの横スクロール動作、およびHeart Rate実データでの365bucket描画・スクロールを確認、2026-09-30。`readHeartRateAggregates()`単体の実測時間とタップからUI更新までの体感時間がほぼ一致することも確認、2026-09-30）／要検証（10年規模・数千bucketでの操作感、ピンチズーム）
-   **確認日**: 2026-09-30

### 7.4 `aggregateGroupByPeriod()`の開始日をレコードの有無が確定する前に決めようとすると、別の形でクラッシュする

-   **知見**: WBS 2.3で取得した最古レコード時刻をALLグラフの開始日に使う実装にする際、次の2つの落とし穴があった。(1) 最古レコードの取得（`findOldestWeightRecordTime()`）がまだ完了していない状態でALLのAggregate問い合わせを開始すると、開始日が決まらないまま`TimeRangeFilter.before(now)`（開始無制限）を使うことになり、実データがない期間も含めて古い時刻からbucketを要求してしまう（レビュー指摘、性能・正しさともに要検証のまま）。(2) 読み取れる範囲にレコードが1件もないと分かった場合に、開始日をやむを得ず`now`にして`TimeRangeFilter.between(now, now)`を渡すと、`IllegalArgumentException: end time needs be after start time`でクラッシュする（`between()`は開始・終了が同一時刻だと例外になる）
-   **Viewerへの適用**: `WeightGraphScreen`で、ALLの問い合わせは最古レコードの取得結果（`oldestResult`）が確定するまで`LaunchedEffect`内で待つようにした。確定した結果が「レコードが1件もない」の場合はAggregate API自体を呼ばず、空の結果を直接組み立てて返す。確定した結果が「取得失敗」の場合のみ、従来通り開始無制限にフォールバックする。`TimeRangeFilter.between(a, b)`を使う箇所では、常に`a`が`b`より厳密に前であることを呼び出し前に保証すること
-   **根拠**: エミュレータ確認（Pixel API 36。ALLタブを選択した際に実際に`IllegalArgumentException: end time needs be after start time`でクラッシュすることを確認し、上記の対応後は同じ操作でクラッシュしなくなったことを確認、2026-09-30）／実機確認（Pixel 11。実データがあるため(2)の「レコード0件」分岐そのものは再現できないが、(1)の「oldestResult確定を待ってからALLを問い合わせる」経路は実データで正しく動作し、ALLの最初のbucketが実際の最古レコードの月から始まることを確認、2026-09-30）
-   **確認日**: 2026-09-30
-   **既知の制約（PoC 3レビュー指摘、未対応）**: 「取得失敗」の場合の開始無制限フォールバックは、`findOldestWeightRecordTime()`/`findOldestHeartRateRecordTime()`自体が失敗した（＝レアケース）場合にのみ通る経路だが、通った場合は実データがない期間も含めた大量bucketをAggregate APIに要求することになる。Heart Rateは集計負荷が大きい（6.8参照）ため、Weightより影響が重くなり得る。`HeartRateGraphScreen`もWeightと同じ実装のまま維持しており、この経路自体の対策（例: 開始無制限ではなく妥当な上限で打ち切る）は今回のセッションでは行っていない
-   **既知の制約（PoC 3レビュー指摘、未対応・極めて低確率）**: `HeartRatePeriod.TODAY`/`StepsPeriod.TODAY`が使う`TimeRangeFilter.between(startOfDay, now)`は、`now`を取得した瞬間がちょうど0時0分0秒0ミリ秒と完全に一致した場合、開始と終了が同一時刻になり`IllegalArgumentException`（本項の(2)と同じ制約）でクラッシュし得る。発生確率は極めて低く、Stepsから引き継いだ既存の設計のため対策は行っていない
-   **WBS 6.2で発見した同じ制約の別パターン（クラッシュ、修正済み）**: Custom期間で開始日に今日より後（未来）の日付を選ぶと、終了日は`minOf(選択日+1日, now)`で`now`に頭打ちになる一方、開始日は選んだ未来の日付のままになり、開始が終了より後になる。この状態で`TimeRangeFilter.between()`を組み立てると本項(2)と同じ`IllegalArgumentException`でクラッシュする。`DatePickerDialog`に`selectableDates`を指定せず、通常の操作（未来日を選ぶだけ）で誰でも踏める点が(2)と異なる（コードレビュー指摘）。`DatePickerDialog`に今日より後を選べない`SelectableDates`制約を追加し、あわせて`resolveDetailGraphRange()`側でも開始 >= 終了を検出したら`TimeRangeFilter`を組み立てず空の結果を返す（`DetailGraphRange.Empty`）防御を二重に入れた

### 7.5 `aggregateGroupByPeriod()`のbucket境界は、渡した開始時刻からの機械的な等間隔区切りで、暦日・暦月に自動整列しない

-   **知見**: `aggregateGroupByPeriod()`は、`timeRangeFilter`の開始時刻を起点に`Period`単位で区切ってbucketを作る。開始時刻に時刻（時・分・秒）が含まれていても、暦日・暦月の境界に自動的に丸めてはくれない。例えば開始時刻が「14:23」なら、日bucketは「14:23〜翌14:23」になる。当初`WeightGraphScreen`は1M/1Yの開始時刻を`LocalDateTime.now().minus(30日)`（時刻を含んだまま）で渡していたため、同じ暦日の朝と夜の記録が別のbucketに分かれてしまい、D-027（同日複数レコードをbucket集計でまとめる）の前提が崩れていた（レビュー指摘。実データがないエミュレータでは表面化しなかった）
-   **Viewerへの適用**: bucket境界を暦日・暦月に合わせるには、`timeRangeFilter`に渡す開始時刻を呼び出し側で日初（`toLocalDate().atStartOfDay()`）／月初（`withDayOfMonth(1).atStartOfDay()`）に切り捨ててから渡す（`WeightGraphScreen`の`startOfDay()`/`startOfMonth()`）。ALLの開始時刻（WBS 2.3で取得した最古レコード時刻）は端末のタイムゾーンで`LocalDateTime`に変換しているが、Health Connectはレコードごとのタイムゾーンで範囲を判定するため、別のタイムゾーンで記録された最古レコードが範囲の外に出る可能性がある（要検証。今回実機確認したデータは単一タイムゾーンでの記録のみで、この事象は再現していない）。月初への切り捨てはこのずれを吸収する副次効果があるが、根本的な解決ではない。なお`HealthConnectManager.recentRangeFilterLocal()`（履歴権限なしでの`SecurityException`フォールバック）は同じ理由で日初へ切り捨てる（floor）と許可される範囲より古くなり再度例外になり得るため、逆に翌日の0時へ切り上げる（ceiling）ようにしている（レビュー指摘）
-   **根拠**: 実機確認（Pixel 11、実データ、複数年分。(a) 同一暦日に朝夜2件の記録（近い値の2件）がある日を、Vicoのマーカー（長押し）で1M表示の該当bucketの値を表示させて確認したところ、平均・最小・最大が2件の記録を正しくまとめた値になっており、暦日区切りで正しく1つのbucketにまとまっていることを確認（表示された値は、Raw画面の2件から手計算した値とごくわずかに異なっていたが、2件が両方とも最小・最大に反映されていたことから、1つのbucketにまとまっている証拠としては十分。このわずかな差はHealth Connect内部の値変換またはVicoマーカーの丸め方によるものと推測され、集計ロジック自体の誤りではないとみられる。原因は未特定）。(b) ALL表示の最初のbucketが実際の最古レコードと同じ月から始まることを確認。(c) `pm revoke`で履歴読み取り権限を外した状態で**1M**を開き、`recentRangeFilterLocal()`のフォールバックが発生した際も、bucketが暦日境界（`now`の30日前の翌日0時に一致する日）で始まり、再度の`SecurityException`が起きないことを確認、2026-09-30）
-   **確認日**: 2026-09-30
-   **既知の制約（PoC 3レビュー指摘、未対応）**: 上記(c)で確認したのは**1M（日bucket）**での`recentRangeFilterLocal()`フォールバックのみ。`recentRangeFilterLocal()`は日初（正確には「30日前の翌日0時」）に切り捨てる関数で、月初には切り捨てない。ALL（月bucket）で履歴読み取り権限なしのままこのフォールバックが発生した場合、bucket境界が月初にならず、本項の知見（暦日・暦月に自動整列しない）通り最初のbucketが不正確な区切りになる可能性がある（未検証）。Weight・Heart Rate・Sleepの`readWeightAggregates()`/`readHeartRateAggregates()`/`readSleepAggregates()`はいずれも同じ`recentRangeFilterLocal()`を共有しており、月bucket専用のフォールバックfilterは用意していない。**Sleepでは特に顕在化しやすい**: 毎晩記録されるデータ型のため、履歴読み取り権限なしで読める直近30日の範囲内にほぼ必ず最古のレコードがあり、ALLを選ぶと高い確率でこのフォールバック経路（月初に揃わない月bucket）を通る（Weightのように記録頻度が低いデータ型では、そもそも直近30日にレコードがなく問題が顕在化しない場合もある）。PoC 4（WBS 5.1）で実際に`pm revoke`→Sleepグラフの「全期間」を確認したが、検証日（2026-09-30）は「30日前の翌日」がちょうど9/1（月初）と偶然一致しており、この既知の制約を実際には踏んでいない（レビュー指摘）。WBS 6.2でSleepの月bucket表示を検証する際は、日付が偶然月初に揃わない状態（別の検証日、または意図的に境界がずれる期間）で改めて確認する必要がある

### 7.6 表示中のperiodと、非同期で取得した結果のperiodが一致するとは限らない

-   **知見**: `LaunchedEffect(period, ...)`でperiodごとの集計結果を非同期に取得し、結果を`WeightAggregatesResult?`のような単純な状態に保持していると、periodを切り替えた直後（`LaunchedEffect`が結果を更新するまでの間、少なくとも1フレーム）は、新しいperiodの状態で古いperiodの結果を描画してしまう。日bucket（1M/1Y）から月bucket（ALL）に切り替えた場合、古い日bucketのデータが月bucket向けのx軸計算・ラベルで描画されることになり、複数の日bucketが同じx値（同じ月）に潰れてVicoの`series()`に渡る（クラッシュするか描画が崩れるだけかは未確認）。ALLが最古レコード時刻の確定を待つ設計（7.4）と組み合わさると、待っている間は結果が更新されないため、この不一致の窓（=前のperiodの結果が表示され続ける時間）がさらに広がる（レビュー指摘。実データがないエミュレータでは表面化しなかった）
-   **Viewerへの適用**: 非同期の結果には、それを取得したperiod（リクエスト時点の設定）をタグ付けして保持し（`WeightGraphScreen`の`AggregatesLoad(period, result)`）、表示側で「現在選択中のperiodとタグが一致する結果だけを描画する」チェックを必ず入れる。一致しない場合は読み込み中として扱う。WBS 6.2で本実装のDetail画面（期間選択）を作る際も同じパターンで実装すること
-   **根拠**: 公式（Jetpack Composeの一般的な非同期状態管理の注意点。`LaunchedEffect`のキー変更から状態更新までの間に古い状態でUIが再コンポーズされ得ることは、Health Connect固有ではなくCompose全般の性質）／実機確認（Pixel 11、実データ。1M/1Y/ALLを連続して何度も切り替えてもクラッシュ・描画崩れが起きないことを確認、2026-09-30。修正前の挙動（x値重複時にVicoがクラッシュするか描画が崩れるだけか）はタグ付けの導入により再現条件自体がなくなったため未確認のまま）
-   **確認日**: 2026-09-30

### 7.7 横画面判定は`LocalConfiguration.current.orientation`で足りるが、「全画面」にするグラフの高さは`Modifier.weight(1f)`ではなく`BoxWithConstraints`から動的に決め、スクロールは残しておく

-   **知見**: (1) MainActivityは`configChanges`を宣言していないため、画面回転のたびにActivityごと再生成される（既存の前例、D-035(4)等と同じ前提）。画面の向きの判定は`LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE`を再コンポーズのたびに読むだけで足り、`OrientationEventListener`等の追加の仕組みは不要。(2) `Modifier.verticalScroll()`を付けたColumnは子に無限大の高さ制約を与えるため、子に`Modifier.weight(1f)`を指定しても「残り領域いっぱいに広げる」効果が成立しない（6.17のLazyColumnとverticalScrollが同居できない制約と同じ理由の、別の形での再現）。**この組み合わせ（verticalScrollを外し、グラフにweight(1f)を渡す）は採用しない**: 横向きのスマホで使える高さはシステムバーを除くと300dp台程度しかなく、期間タブ・Custom選択時の`CustomRangePicker`・集計方法の注記・`historyLimited`の注記・（HeartRateのみ）計測数`Text`が増えると、`weight(1f)`に回る高さが0近くまで潰れ得る。しかもスクロールで確認する手段もないため、潰れた分は完全に見えなくなる
-   **Viewerへの適用**: `ChartTabColumn`（DetailCommon.kt）は全画面時も含め常に`verticalScroll`を付けたままにする（高さの見積もりが外れた場合の安全弁）。`BoxWithConstraints`を`ChartTabColumn`の内側に置き、そのスコープで得られる実際の利用可能高さ（`maxHeight`）を`val`に一度代入してから（Composeの`@LayoutScopeMarker`により、`ColumnScope`等ネストしたスコープの中から外側の`BoxWithConstraintsScope.maxHeight`を暗黙レシーバで直接参照できないため、明示的に変数へ取り出す必要がある）contentへ渡す。グラフ本体の高さは、その利用可能高さから実際に表示される要素（historyLimitedの注記・Customピッカー・HeartRateの計測数テキストは条件付き）が使うおおよその高さを`detailChartHeight()`で差し引いて決め、下限（160dp）も設ける。見積もりが多少外れても、スクロールが効くため内容が完全に見えなくなることはない。`BoxWithConstraints`（`SubcomposeLayout`を使う、`Box`より重い）は`ChartTabColumn`の内側に閉じ、Records（Paging LazyColumn）・Sourcesタブまでは巻き込まない
-   **根拠**: 公式（Jetpack Composeの一般的なレイアウト制約・Activity再生成の仕組み。いずれもHealth Connect固有ではない）／実機確認（Pixel 11、実データ。Weight/Steps/HeartRate/Sleepの4データ型×横画面で、通常表示・Customピッカー表示（実際に日付を選択してグラフを読み込ませた状態）・HeartRateの計測数テキスト付き表示のいずれでもグラフが十分な高さで表示されレイアウト崩れがないこと、期間タブ切替・システムの戻る操作・Records/Sourcesタブでの非全画面維持を確認、2026-10-01）
-   **確認日**: 2026-10-01

------------------------------------------------------------------------

## 8. テスト観点チェックリスト

実装・リリース前に確認する。

-   [ ] 権限未付与／一部のデータ型だけ許可／履歴読み取り権限なし
-   [ ] アプリ使用中にHealth Connectの設定から権限を取り消し、アプリに戻る
-   [ ] Health Connect未インストール・バージョン古い（Android 13以前、UPDATE_REQUIRED状態）。WBS 6.9でPlayストア誘導ボタンを実装済みだが、`availability`を一時的に固定する診断コードでのアプリ内ボタンの動作確認（6.21、6.22）にとどまり、この状態自体（API 28〜33の実機・エミュレータ）での確認はできていない
-   [ ] Health Connect UNAVAILABLE状態（Android 14以降のwork profile、またはsystem service自体が無い端末）で、Playストア誘導ボタンを出さずテキストのみ表示されるか（WBS 6.9、D-041。診断コードでの確認にとどまり、実際にこの状態になる端末では未確認）
-   [ ] 権限確認（`getGrantedPermissions()`）のIPC呼び出しが失敗した場合に、「読み込み中」と区別した案内・再試行ボタンが実際に出て、再試行で復帰するか（WBS 6.9、D-040。診断コードで強制的にFailureを発生させての確認にとどまり、実際のIPC失敗での確認はできていない）
-   [ ] Android 9〜13 と 14以降の両方
-   [ ] 権限説明画面を両方の経路（Android 13以前・14以降）から開く
-   [ ] データが0件のデータ型／読み込み失敗（データなしと区別できるか）
-   [ ] 端末のタイムゾーン変更・夏時間の境界・日付境界での日／月／年bucket
-   [x] テーマ（System / Light / Dark）と言語（日本語 / English）の切り替え（10.1、10.2。Pixel 11で確認、2026-10-01）
-   [ ] リリースビルドのlogcatに健康データの値が出ていないか
-   [ ] 広告リクエストに健康データ由来の情報が含まれていないか
-   [ ] 同一日・同一ソースなど複数の重複レコードが、実装変更（`ReadRecordsRequest`の呼び方の変更など）でRaw一覧から消えていないか（6.3）
-   [x] 実データでのALLグラフの見た目・横スクロール操作感（7.3。Pixel 11、数年分・千件規模で確認。10年規模・大量bucketでの滑らかさとピンチズームは未確認のまま）
-   [x] エミュレータでのみ確認した挙動（6.5、7.1、7.4）をPixel 11実機でも再確認する（2026-09-30）
-   [x] 同じ暦日に複数の記録（朝・夜など）がある実データで、グラフのbucketが暦日・暦月の境界で正しく区切られているか（7.5。Pixel 11で確認、2026-09-30）
-   [x] 1M/1YからALLへ（またはその逆へ）period切り替えを素早く繰り返しても、グラフが崩れず・クラッシュしないか（7.6。Pixel 11で確認、2026-09-30）
-   [x] `pm revoke`で履歴読み取り権限を外した状態で1Mを開き、`SecurityException`から直近30日へのフォールバック（`recentRangeFilterLocal()`）が実際に発生するか、発生した場合にbucket境界が正しく日初区切りになっているか（7.5。Pixel 11で確認、2026-09-30）
-   [x] 日付境界をまたぐSleep Sessionが、グラフのbucket（公式Aggregate）とRaw一覧（Session区間そのまま）でそれぞれどう扱われるかを実データで確認する（6.9。Pixel 11で確認、2026-09-30）
-   [x] Detail画面を横画面にした際、Chartタブのみ全画面化され、期間タブ切替・システムの戻る操作が機能し、Records/Sourcesタブでは全画面化されないか（7.7。Pixel 11、4データ型で確認、2026-10-01）
-   [x] 横画面の全画面グラフで、Custom選択時の日付ピッカー表示や（HeartRateのみ）計測数テキストなど付随要素が増えても、グラフの高さが潰れず・レイアウトが崩れないか（7.7。Pixel 11で確認、2026-10-01）

------------------------------------------------------------------------

## 9. Viewerには適用しない知見

既存アプリで得たが、技術スタックやドメインの違いからViewerには関係しないもの。Viewerの方針と混同しないよう明記しておく。

-   Expo / React Native固有: config plugin、Gradleデーモンのキャッシュによるネイティブモジュールの未リンク、`EXPO_PUBLIC_*` 環境変数の反映、LogBoxの警告バナーがadbのタップを奪う件、ダーク/ライトの色定数を3か所で手動同期する必要
-   書き込み系: `clientRecordId` による冪等化、削除・再作成、同期ジョブと再試行キュー
-   アプリ内DB（暗号化DB、スキーマ変更時の再インストール）
-   Health Connectの有無によるビルドの出し分け（Viewerは常にHealth Connectを使う）
-   iOS固有の回避策

------------------------------------------------------------------------

## 10. 設定・テーマ・言語（WBS 6.6）

### 10.1 `AppCompatDelegate.setDefaultNightMode()`はプロセス再起動をまたいで永続化されない。`setApplicationLocales()`（言語）は永続化される

-   **知見**: `androidx.appcompat:appcompat` 1.8.0のclasses.jarを逆コンパイル（`javap -p -constants`）して確認したところ、夜間モードの保持は`AppCompatDelegate`内の`private static int sDefaultNightMode`というプロセス内メモリのみのstatic変数で行われており、`SharedPreferences`等への書き込みコードは存在しない。一方、言語（`setApplicationLocales()`）は`sAppLocalesStorageSyncLock`・`AppLocalesMetadataHolderService`関連の静的フィールド・メソッド（`syncRequestedAndStoredLocales()`等）を持ち、マニフェストに`AppLocalesMetadataHolderService`（`autoStoreLocales`）を宣言すると、ライブラリが自分でSharedPreferences相当の永続化を行う（Android 13以降はさらにOS側のper-app language機能と同期する）
-   **Viewerへの適用**: テーマ（System/Light/Dark）はアプリ独自の永続化層（DataStore、`settings/UserSettingsRepository.kt`）が必須で、プロセス起動時（`HealthDataViewerApplication.onCreate()`）に保存値を読んで`setDefaultNightMode()`を呼び直す必要がある。言語は`AppCompatDelegate`が自前で永続化するため、アプリ独自のDataStoreに重複して保存しない（二重管理による食い違いを避ける）。要件§21の「DataStore（テーマ・言語・表示指標・購入状態のキャッシュ）」という書き方はこの違いを区別していないため、実装時に精査が必要だった
-   **根拠**: 逆コンパイル（appcompat 1.8.0のclasses.jar、2026-10-01）／実機確認（Pixel 11。テーマ・言語とも3択の切り替えと、`am force-stop`→再起動後の保持を確認。D-039）
-   **確認日**: 2026-10-01

### 10.2 `AppCompatDelegate.setDefaultNightMode()`はActivityを再生成するため、呼び出し元のComposition（`rememberCoroutineScope()`等）に依存する非同期処理を道連れにキャンセルしうる

-   **知見**: Settings画面のテーマ選択ハンドラで、`coroutineScope.launch { repository.setThemeMode(mode) }`（DataStoreへの書き込み）と`AppCompatDelegate.setDefaultNightMode(...)`（見た目の反映）を別々の文として呼んだところ、Pixel 11実機で「見た目は即座に切り替わるが、アプリを`force-stop`して再起動すると設定が保存されておらずSystemに戻る」不具合が発生した。`setDefaultNightMode()`は呼び出すと（必要な場合）Activityを再生成し、その再生成はSettings画面のCompositionとそれに紐づく`rememberCoroutineScope()`のスコープを破棄する。DataStoreへの書き込み（`dataStore.edit {}`、suspend）がその破棄より先に完了していなければ、書き込みは完了しないままキャンセルされる。`adb shell run-as <pkg> cat .../datastore/settings.preferences_pb`でファイル自体が作成されていないことを確認して原因を特定した
-   **Viewerへの適用**: 永続化（DataStoreへの書き込み）と、その後に続く「状態変更を引き起こす可能性のある処理」（`setDefaultNightMode()`に限らず、Activity再生成・プロセス終了・画面遷移などComposition破棄を伴いうる処理全般）は同じ`launch`ブロック内で、永続化のsuspend呼び出しを`await`（＝先に書いて完了を待つ）してから後続処理を呼ぶ順序にする。見た目の反映を先に行うと、その反映自体の副作用が永続化を妨げるという順序依存の罠になる。**ただしこの対策は「1回の操作では」安全だが、連続して素早く設定を変更された場合（例: テーマを連続タップ）に書き込みがキャンセルされる余地を完全には塞がない。より根本的な対策は10.3参照**
-   **根拠**: 実機確認（Pixel 11、2026-10-01。D-039の(4)参照。選択→スクリーンショット→アプリ再起動→スクリーンショット→DataStoreファイルの直接確認、という手順で再現・特定した）
-   **確認日**: 2026-10-01

### 10.3 Activity再生成をまたぐ設定値は、Composition寿命のCoroutineScopeではなくプロセス寿命のrepositoryインスタンス＋`MutableStateFlow`で持つと、書き込みキャンセルと表示のちらつきの両方が一度に解消する

-   **知見**: 10.2の対策（書き込みを待ってから適用する）を入れた後も、2回目のコードレビューで次の2つの問題が残っていることが分かった。(1) 書き込みが依然として`rememberCoroutineScope()`（Settings画面のCompositionに紐づく）上で動いているため、設定を素早く連続変更すると、1回目の変更が引き起こすActivity再生成が2回目の書き込みを道連れにキャンセルする余地が残る。(2) `userSettingsRepository.settingsFlow.collectAsState(initial = AppSettings())`のように、冷たい`Flow`（`dataStore.data`）をハードコードした既定値と組み合わせてComposeに繋ぐと、Activity再生成のたびに（`MainActivity.onCreate()`で`UserSettingsRepository`を作り直していたため）新しいインスタンスが既定値から再スタートし、DataStoreからの最初の読み取りが届くまでの一瞬、実際の保存値と異なる表示になる（テーマ変更直後に選択が一瞬「System」に戻る、起動直後は表示指標トグルがOFFでも一瞬すべてのカードが表示される、など）。根本原因はどちらも「設定の状態をActivity・Compositionより短命な場所に置いていること」
-   **Viewerへの適用**: `UserSettingsRepository`を`HealthDataViewerApplication`（プロセス生存期間中ただ1つ）が保持するシングルトンに変更し、`MainActivity`は作り直さずそれを取得するだけにする。repository内部は、読み取りを常に最新値を持つ`MutableStateFlow`（起動時に一度だけブロッキング読み取りして初期化）で持ち、Composeからは`collectAsState()`（`initial`不要）で直接つなぐ。書き込みは、呼び出し元の状態変更に先立って`_settings.value`を同期的に更新した上で、永続化はrepository自身が持つ`CoroutineScope`（`SupervisorJob` + `Dispatchers.Default`。Activity・Compositionのどちらにも属さない）に`launch`する。この設計にすると、(1)書き込みがどのActivity再生成にも道連れにされず常に完了する、(2)Activity再生成直後からComposeが見る値は常に最新の状態（ハードコードした既定値を経由しない）、の両方が同時に満たされる
-   **根拠**: 実機確認（Pixel 11、2026-10-01。テーマを「ライト」→「ダーク」のように連続タップしても最終的な見た目・選択表示・DataStoreへの永続化が一致することを`run-as`で確認。D-039の2回目のコードレビュー対応(12)参照）
-   **確認日**: 2026-10-01

### 10.4 DataStoreは既定で読み取り・書き込みの失敗に対する保護を持たない。`corruptionHandler`と`IOException`のcatchを明示的に用意する必要がある

-   **知見**: `preferencesDataStore()`はcorruptionHandlerを指定しない限り、ファイル破損時に読み取りが例外を投げる。`HealthDataViewerApplication.onCreate()`のようにアプリ起動のたびに行う初期読み取りがこれを素通しすると、ファイルが壊れた状態（バックアップ復元の失敗等）でアプリが起動不能になり、ユーザーはアプリのデータを消去するしかなくなる。書き込み（`dataStore.edit {}`）も同様にI/Oエラーを素通しする
-   **Viewerへの適用**: `preferencesDataStore(name = ..., corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() })`でファイル破損時に空の設定へフォールバックし、読み取りFlowには`.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }`を追加する（DataStore公式パターン）。書き込みは`runCatching`で包み、失敗しても次回の設定変更が引き続き行えるようにする（失敗した1回の書き込みが失われるだけで、アプリの続行は妨げない）
-   **根拠**: 公式（DataStoreのドキュメントが明記する推奨パターン）。コードレビュー指摘を受けて導入し、Pixel 11実機でビルド・通常の読み書きに影響がないことを確認した（2026-10-01）
-   **確認日**: 2026-10-01

### 10.5 `MutableStateFlow`を正としてrepository自身のCoroutineScopeで書き込む設計（10.3）でも、「呼び出し時点の引数」をそのままDataStoreへの書き込みに使うと、連続した変更で保存順が入れ替わりうる

-   **知見**: 10.3の設計変更後も、`setThemeModeAndApply(mode: ThemeMode)`が`persist { it[THEME_MODE] = mode.name }`のように、呼び出し時点の引数`mode`をそのままラムダに閉じ込めて`scope.launch {}`していた。`scope`は`Dispatchers.Default`（複数スレッド）上で動くため、設定を連続して素早く変更すると、生成順と実行順が入れ替わる余地が理論上残る（例: System→Light→Darkと連続で変更した場合、Darkの書き込みがLightの書き込みより先に実行されると、最終的にDataStoreにはLightが残ってしまう。画面の表示は常に最後の選択＝Darkを正しく反映しているため、気付きにくい食い違いになる）
-   **Viewerへの適用**: 書き込みラムダの中では、呼び出し時点の引数を使わず、実行される瞬間の`_settings.value`を読み直して書く（`persist { it[THEME_MODE] = _settings.value.themeMode.name }`）。`_settings.value`への代入は常に同期的に（呼び出し元のComposeイベントハンドラ内で）完了しているため、どの書き込みが実際に最後に実行されても、その時点の最新値（＝最後にタップされた選択）を書くことになる。DataStoreの`edit {}`自体は1件ずつ順番に適用されるため、これだけで実行順序に依存しない一貫性が保証される
-   **根拠**: コードレビュー指摘（2026-10-01）。理論的な競合であり、実機での再現は行っていない（10.3の修正後の実機確認では連続タップでも一致していたが、`Dispatchers.Default`のスレッドスケジューリングに依存する競合は再現性が低く、確認できなかったことが競合不在の証明にはならない）
-   **確認日**: 2026-10-01
