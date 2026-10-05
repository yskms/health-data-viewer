# Health Data Viewer

Health Connectに保存済みのデータを読み取り専用で可視化・検証するAndroidアプリ。
現在はMVP実装フェーズ（WBS 6、[docs/wbs.md](docs/wbs.md)参照）。

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
-   `aggregateGroupByPeriod()`（bucket幅を`Period`で指定するAggregate API）に渡す`TimeRangeFilter`は`before/after(LocalDateTime)`で構築すること。`before/after(Instant)`で構築したfilterを渡すと`IllegalArgumentException`で即クラッシュする（`readRecords()`や`aggregateGroupByDuration()`はInstantベースの`TimeRangeFilter`が前提で、同じ型でもAPIによって要求が異なる）（[docs/lessons.md](docs/lessons.md) 6.5）
-   `aggregateGroupByPeriod()`のbucket境界は、渡した`timeRangeFilter`の開始時刻を起点に`Period`単位で機械的に区切るだけで、暦日・暦月の境界に自動整列**しない**。開始時刻に時刻（時・分・秒）が残ったまま渡すと、bucketが「14:23〜翌14:23」のようにずれ、クラッシュせず静かに間違った集計になる。暦日・暦月区切りにするには、呼び出し側で開始時刻を日初／月初に切り捨ててから渡すこと（Pixel 11実機の実データで確認済み、[docs/lessons.md](docs/lessons.md) 7.5）
-   `SleepSessionRecord.SLEEP_DURATION_TOTAL`（Aggregate）は、Session区間（startTime〜endTime）の単純な長さの合計ではなく、Stageのうち覚醒（`STAGE_TYPE_AWAKE`）区間を除いた時間の合計になっている（Pixel 11実機の実データで、分単位の精度で確認済み）。日付境界をまたぐSessionは、両日のbucketに実時間の重なりに応じて按分される（丸ごと1つのbucketに計上されることはない。0時より前の時間が4分〜86.5分と幅のある6事例で確認済み。アプリ側で日付境界の按分ロジックを実装する必要はない）。ただし按分の正確な計算根拠（Health Connectが実際にどう計算しているか）と、複数ソースが同じ夜を重ねて記録した場合の重複処理（Source priority）は**単一ソースのデータでしか確認しておらず未確認**。詳細・限界は[docs/lessons.md](docs/lessons.md) 6.9参照
-   `HealthConnectClient.getSdkStatus()`の戻り値の意味はAndroidバージョンで大きく異なる（connect-client 1.1.0を逆コンパイルして確認）。API 28〜33では「未インストール」「無効化」「バインド可能なサービスが無い」「バージョン古い」のいずれかで`SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED`になり、Playストアへ誘導すれば解決する。`SDK_UNAVAILABLE`は、minSdk 28の本アプリでは実質的にAPI 34以降（work profile・system service不在）でしか起こらず、**Playストアへ誘導しても解決しない**。「未インストールだからPlayストアへ誘導すればよい」という一見自然な判断を両方の状態に同じように適用しないこと（`HealthConnectAvailability.UNAVAILABLE`/`UPDATE_REQUIRED`、[docs/lessons.md](docs/lessons.md) 6.21〜6.23）
-   requirements.md §16・§22.2の「Calories」はHealth Connect上の1つのRecord型ではなく、`ActiveCaloriesBurnedRecord`と`TotalCaloriesBurnedRecord`という2つの完全に独立したRecord型に対応する（javap逆コンパイルで確認）。どちらか一方だけ表示する、または両方を1つの数値に合成する「簡略化」はしないこと（重複も含めてすべて表示するという原則に反する）。2つの独立したデータ型（カード・詳細画面）として実装している理由はD-043(1)参照
-   `TotalCaloriesBurnedRecord.ENERGY_TOTAL`（Aggregate）は、レコードが1件も存在しない期間でもnullではなく非null値を返すことを実機で確認している（他のAggregateMetricの「レコードが無ければnull」という前提が通用しない。Android 14+のプラットフォーム側でActive Calories・基礎代謝等から推計値を補っていると見られるが未確認）。そのため`HealthConnectManager`のホーム画面カード用関数（`readTotalCaloriesAggregateTotal()`）はAggregateを呼ぶ前に`hasAnyRecord()`で範囲内に実レコードがあるかを確認し、無ければnullとして扱う。詳細画面のChart用（`readTotalCaloriesAggregates()`）には同じガードを**意図的に適用していない**。ALL期間は`aggregateGroupByPeriod()`自体が単独で約77秒かかる（Heart RateのALL期間96秒に匹敵、requirements.md §27に独立した既知の問題として記録）が、ガード追加がこれにどう影響するかは計測が安定せず確認できておらず、効果を確証できないまま追加しなかった（「致命的に遅いから悪化する」という当初の判断根拠は誤りだったと判明している。詳細はlessons.md 6.26参照）。一部だけレコードがある期間のAggregate自体に推計による水増しが含まれていないかも未確認のまま残る（詳細・要検証事項は[docs/lessons.md](docs/lessons.md) 6.26、requirements.md §27）
-   `BodyFatRecord`には`WeightRecord.WEIGHT_AVG`等に相当する公式`AggregateMetric`が**存在しない**（javap逆コンパイルで確認。`connect-client-1.1.0-api.jar`全体を`AggregateMetric<Percentage>`シグネチャで検索しても0件）。`HeartRateVariabilityRmssdRecord`（HRV）・`OxygenSaturationRecord`（SpO2）・`BloodGlucoseRecord`（Blood Glucose）も同じ検索で存在しないことを確認済み。Body Fat・HRV・SpO2・Blood Glucoseの詳細画面Chartは公式Aggregateを使わず、Rawレコードを全件走査してアプリ側でbucket集計している（`HealthConnectManager.readBodyFatAggregates()`/`readHrvAggregates()`/`readOxygenSaturationAggregates()`/`readBloodGlucoseAggregates()`、D-046(3)/D-047(4)/D-049(2)/D-050(2)、[docs/lessons.md](docs/lessons.md) 6.27・6.28・6.30）。**Pixel 11実機1台での観察（Health Connect全体の仕様として確認したものではない）だが、Health Connect本体アプリの「データとアクセス」画面は、権限が許可済み・このアプリが実装済みのデータ型でも、実際にレコードが1件も存在しない型は一覧に表示しないように見えた**（SpO2・Blood Pressureの両方で確認）。同じ観察をする場合、「項目が出ない＝このアプリの権限・実装が間違っている」と即断しないこと（[docs/lessons.md](docs/lessons.md) 6.30）
-   `ExerciseSessionRecord`は`SleepSessionRecord`と同じIntervalRecord（開始〜終了時刻のSession型）で、公式`AggregateMetric<Duration>`の`EXERCISE_DURATION_TOTAL`を持つ（javap逆コンパイルで確認。Body Fat/HRV/SpO2/Blood Glucoseのような「公式Aggregateが無い」ケースではない）。一方、`exerciseRouteResult`（GPSルート）は読み取るために通常の`HealthPermission`（`getReadPermission()`で取得する`READ_EXERCISE`等の文字列）とは別の`READ_EXERCISE_ROUTE`権限と、`ExerciseRouteRequestContract`経由のセッション単位の専用同意フローが必要（javap逆コンパイルで確認。`HealthPermission.getReadPermission()`が返す通常の権限文字列ではなく、セッションごとの個別同意に近い仕組み）。本アプリはGPSルート表示機能自体を実装しないため`READ_EXERCISE`のみ追加した（D-051）。requirements.md §22.2のExercise行の重複処理欄は「—」のまま確定させていない。Steps/Distance/Caloriesが示す「あり（Activity）」、SleepのAggregateMetric名が示唆する「あり（Sleep）」のいずれに相当する公式ドキュメント上の根拠も見当たらないため、未検証ではなく分類自体が公式に示されていないと理解すること（要検証、requirements.md §27）

## SDK調査（逆コンパイル）で誤解しやすい点

-   Health Connect SDK固有の挙動ではなくKotlin/JVM一般の注意点だが、このSDKの単位クラス（`Mass`/`Length`/`Energy`等）を調べる際に踏みやすいため記録する。`javap`でのクラスファイル逆コンパイルは、KotlinのJvmName差し替えによりKotlinから見える実際のプロパティ名（`inKilograms`/`inKilometers`/`inKilocalories`等）を表示しない（JVM上のメソッド名`getKilograms()`等しか見えない）。`Energy`型はCalories実装時（D-043(3)）に`inKilocalories`、`Pressure`型はBlood Pressure実装時（D-045(4)）に`inMillimetersOfMercury`、`BloodGlucose`型はBlood Glucose実装時（D-050(2)）に`inMilligramsPerDeciliter`/`inMillimolesPerLiter`であることをそれぞれ確認済みだが、まだ確認していない単位型（`Power`等）を調べる際も同様に、`javap`の出力だけで「このプロパティは存在しない」と判断しないこと（詳細・確認手順は[docs/lessons.md](docs/lessons.md) 6.24）。例外として`Percentage`型（Body Fat実装時、D-046(4)で確認）は`@JvmName`差し替えが**されておらず**、Kotlinプロパティ名は単純に`value`（javapで見える`getValue()`のまま）。単位型だからといって機械的にinXxxの罠があると決めつけないこと。**逆に、この`Percentage`型の例外に引きずられて、他の型も同様にin接頭辞なしだろうと判断するのも誤り**（Blood Glucose実装時、`BloodGlucose`型を`milligramsPerDeciliter`とin接頭辞なしで実装しコンパイルエラーになった。D-050(2)、lessons.md 6.31）。さらにHRV実装時（D-047(4)）、`HeartRateVariabilityRmssdRecord.heartRateVariabilityMillis`はそもそも`Mass`/`Percentage`のような単位型のラッパークラスではなく、単純な`double`であることも確認した。「値フィールドは必ず何らかの単位型でラップされている」という前提も機械的に当てはめないこと（[docs/lessons.md](docs/lessons.md) 6.28）

## Gradle設定で誤解しやすい点

-   `app/build.gradle.kts` に `org.jetbrains.kotlin.android` プラグインを**適用しない**。抜けているように見えるが意図的（D-020）。AGP 9.0以降はKotlinサポートがAGPに組み込まれ（built-in Kotlin）、このプラグインを追加するとビルドエラーになる。Composeコンパイラの `org.jetbrains.kotlin.plugin.compose` は引き続き必要
-   Kotlin用の `kotlinOptions { jvmTarget = ... }` DSLも同じ理由で使えない。JVMターゲットは `compileOptions` のJavaVersionで揃える
-   compileSdk・targetSdkは37（Android 17）。Compose BOM 2026.09.00がcompileSdk 37を要求するため、36には戻せない。ローカルSDKには `build-tools;37.0.0` の追加導入が必要だった
-   `app/build.gradle.kts`の`vico.compose`/`vico.compose.m3`依存に付けている`exclude(group = "org.jetbrains.compose.*")`は削除しないこと。vicoはKotlin Multiplatform対応アーティファクトで、Android targetでもBOM管理下の`androidx.compose.*`と同じパッケージ名・別バージョンの`org.jetbrains.compose.*`を推移的に持ち込み、`Modifier.weight()`等の解決が壊れる（除外しても実体はandroidx側の依存から得られるため安全。D-035、[docs/wbs.md](docs/wbs.md)）

## UI基盤で誤解しやすい点

-   MainActivityは`ComponentActivity`ではなく`AppCompatActivity`を使う。テーマの親も`Theme.AppCompat.DayNight.NoActionBar`（フレームワーク標準の`android:Theme.Material...`ではない）。Composeオンリーだからと`ComponentActivity`に「簡略化」しないこと
-   理由: 要件§21で確定済みの言語切替方式`AppCompatDelegate.setApplicationLocales()`は、Android 12以前ではAppCompatActivity・AppCompat系テーマが前提（D-022）。DayNightテーマにしているのは、ダークモード端末での起動時（Compose描画前）の白画面ちらつきも同時に防ぐため

## 設定・テーマ・言語で誤解しやすい点

-   `AppCompatDelegate.setDefaultNightMode()`（テーマ）はプロセス内メモリのstatic変数のみで、プロセス再起動をまたいで**永続化されない**。`setApplicationLocales()`（言語）はマニフェストの`AppLocalesMetadataHolderService`宣言経由でライブラリ自身が永続化する点と対照的（逆コンパイルで確認）。そのためテーマだけは独自のDataStore（`settings/UserSettingsRepository.kt`）が必要で、アプリ起動時（`HealthDataViewerApplication.onCreate()`）に保存値を読んで`setDefaultNightMode()`を呼び直す。言語は独自のDataStoreに重複して保存しない（[docs/lessons.md](docs/lessons.md) 10.1）
-   `AppCompatDelegate.setDefaultNightMode()`はActivityの再生成を引き起こすため、設定変更をComposition寿命のスコープ（`rememberCoroutineScope()`）で書き込むと、その再生成が書き込み自身を道連れにキャンセルしうる（見た目だけ切り替わり、再起動すると保存されていない）。**`UserSettingsRepository`は`HealthDataViewerApplication`が保持するプロセス生存期間中ただ1つのインスタンスとし、書き込みはrepository自身が持つCoroutineScope（Activity・Compositionのどちらにも属さない）で行うこと。** 読み取りも常に最新値を持つ`MutableStateFlow`を正とし、Composeの`collectAsState()`に`initial`を渡してごまかさない（ハードコードした既定値から始まり、Activity再生成のたびに一瞬ちらつく）。`MainActivity.onCreate()`のたびに`UserSettingsRepository`を作り直さないこと（[docs/lessons.md](docs/lessons.md) 10.2、10.3、D-039）
