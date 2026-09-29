# Health Data Viewer

Health Connectに保存済みのデータを読み取り専用で可視化・検証するAndroidアプリ。
現在はPoCフェーズ（WBS 2〜5、[docs/wbs.md](docs/wbs.md)参照）。

-   要件・技術方針: [docs/requirements.md](docs/requirements.md)（第II部は「確定」と「PoCで確定する候補」を区別している）
-   WBS・決定ログ: [docs/wbs.md](docs/wbs.md)。タスクの完了時に状態を更新し、方針を変えたら決定ログに追記する
-   実装知見・テスト観点: [docs/lessons.md](docs/lessons.md)。項目ごとに根拠の区分（公式／実機確認／要検証）を付け、既存アプリでの実機確認をHealth Connect全体の仕様として扱わない
-   既存の自作アプリ（Expo製、Health Connectへの書き込みあり）を知見の参照元にしている（D-017）。場所と扱いは `docs/private/reference-projects.md`。**その実装コードの直接移植を前提にせず、名前・内容を公開ドキュメントやコメントに書かない**
-   リポジトリは公開。`docs/private/`（競合分析・差別化・ストア戦略、モックアップ画像）は `.gitignore` で除外し、ローカルのみで管理している。公開ドキュメントに競合アプリ名や戦略を書き写さないこと
-   別マシンで作業を再開する場合、`docs/private/` は `git clone` では引き継がれない。旧マシンから手動でコピーすること（`docs/private/reference-projects.md` が指す参照先アプリ本体も、必要なら別途用意する）
-   `docs/private/` の画像はイメージ用のモックアップとアイコン案。細部（日付・文言など）は仕様ではない

## 変えてはいけない原則

-   **Health ConnectはRead-only。** 書き込み権限の宣言、Recordの追加・編集・削除は一切しない
-   **Rawレコードはアプリ独自に重複排除・重複判定しない。** 重複も含めてすべて表示することがこのアプリの目的
-   **Health Connectの健康データは広告・Analytics・ログに一切流さない。** Google Playのポリシー違反になる。広告のコードから健康データ層を参照させない
-   **1Y（年）とALL（全期間）はMVP必須。** 簡略化のために削らない
-   AdMobを入れているため、ストア文言などで「No cloud」「No network」は使わない。「健康データを外部送信しない」と表現する
-   広告は画面下部のバナーのみ。表示してはいけない画面の一覧は要件 §15
-   Android専用。iOS対応やクロスプラットフォーム化を提案しない
-   applicationIdは `com.yskms.healthdataviewer` で確定（Play公開後は変更不可）。表示名は英語「Health Data Viewer」／日本語「健康データビューア」（D-019）
-   **アプリ名・ストアのタイトル・applicationIdに「Health Connect」を含めない**（Googleの商標ガイドラインとPlayのなりすましポリシー）。旧名「Health Connect Viewer」に戻さない。表記を「HealthViewer」などに縮めない（要件 §3.3）

## Health Connectで誤解しやすい点

-   Aggregateで公式の重複処理が効くのはActivity / Sleepのみ。体重などのAggregate平均には、複数ソースの同一測定がそのまま含まれる
-   ソース別の**レコード件数**はAggregateでは取れない（全件走査が必要）
-   Health Connectが動くのはAndroid 9（API 28）以上。SDKのminSdk 26に合わせない
-   グラフの集計方法はデータ型・期間ごとに決め、画面にも明示する（例: 「月平均」）
-   `ReadRecordsRequest(recordType = X::class, timeRangeFilter = ..., ...)`という通常の呼び方（公式サンプルと同じ形。内部的には`recordType`を除いた6引数のコンストラクタが呼ばれる）でRawレコードを読む場合、`deduplicateStrategy`は指定しなくても自動的に`DISABLED`（重複排除なし、生データ全件）になる。これは上記「変えてはいけない原則」（重複も含めてすべて表示）と整合するための挙動。`deduplicateStrategy`を実際に指定できるコンストラクタは`@RestrictTo(LIBRARY)`（ライブラリ内部専用）かつ`@ExperimentalDeduplicationApi`（要opt-in）が付いており、通常の呼び方では到達しない・する必要がない。**`@OptIn(ExperimentalDeduplicationApi::class)`でこの制限付きコンストラクタを直接呼ぶような変更はしないこと**（Rawレコードの重複が意図せず消える）（[docs/lessons.md](docs/lessons.md) 6.3）

## Gradle設定で誤解しやすい点

-   `app/build.gradle.kts` に `org.jetbrains.kotlin.android` プラグインを**適用しない**。抜けているように見えるが意図的（D-020）。AGP 9.0以降はKotlinサポートがAGPに組み込まれ（built-in Kotlin）、このプラグインを追加するとビルドエラーになる。Composeコンパイラの `org.jetbrains.kotlin.plugin.compose` は引き続き必要
-   Kotlin用の `kotlinOptions { jvmTarget = ... }` DSLも同じ理由で使えない。JVMターゲットは `compileOptions` のJavaVersionで揃える
-   compileSdk・targetSdkは37（Android 17）。Compose BOM 2026.09.00がcompileSdk 37を要求するため、36には戻せない。ローカルSDKには `build-tools;37.0.0` の追加導入が必要だった

## UI基盤で誤解しやすい点

-   MainActivityは`ComponentActivity`ではなく`AppCompatActivity`を使う。テーマの親も`Theme.AppCompat.DayNight.NoActionBar`（フレームワーク標準の`android:Theme.Material...`ではない）。Composeオンリーだからと`ComponentActivity`に「簡略化」しないこと
-   理由: 要件§21で確定済みの言語切替方式`AppCompatDelegate.setApplicationLocales()`は、Android 12以前ではAppCompatActivity・AppCompat系テーマが前提（D-022）。DayNightテーマにしているのは、ダークモード端末での起動時（Compose描画前）の白画面ちらつきも同時に防ぐため
