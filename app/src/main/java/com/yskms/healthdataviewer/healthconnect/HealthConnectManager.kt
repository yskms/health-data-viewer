package com.yskms.healthdataviewer.healthconnect

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.RemoteException
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.paging.PagingSource
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass

// HealthConnectManager.readWithHistoryFallback()の結果型（詳細は同関数のコメント参照）。
private sealed interface HistoryFallbackOutcome<out T> {
    data class Success<T>(val value: T, val historyLimited: Boolean) : HistoryFallbackOutcome<T>

    data object Failure : HistoryFallbackOutcome<Nothing>
}

// 権限の許可状態はアプリ内にキャッシュせず、呼び出しのたびにHealth Connectへ問い合わせる。
// Health Connectの設定などアプリ外から権限が取り消され得るため（lessons.md 3.1）。
class HealthConnectManager(context: Context) {
    companion object {
        // 履歴読み取り権限がない場合に読める範囲の近似日数（lessons.md 6.1）。recentRangeFilter()・
        // recentRangeFilterLocal()・StepsScreenの直近フォールバック計算で共通して使う。
        const val HISTORY_FALLBACK_DAYS = 30L

        // AndroidManifest.xmlの<queries>で宣言済みのパッケージ名と同じもの（変更時は両方揃えて直す）。
        private const val PROVIDER_PACKAGE_NAME = "com.google.android.apps.healthdata"
        private const val PLAY_STORE_PACKAGE_NAME = "com.android.vending"
    }

    private val appContext = context.applicationContext

    // WBS 6.9（コードレビュー指摘）: getSdkStatus()の戻り値とAndroidバージョンの対応をbytecode
    // レベルで確認した結果（lessons.md 6.23）、SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED(2)はAPI
    // 28〜33限定でしか返らず（34以降は内部実装がこのコードを返す分岐自体を持たない）、SDK_UNAVAILABLE(1)は
    // minSdk 28のこのアプリでは実質的にAPI 34以降のwork profile／system service不在でしか発生しない。
    // 「未インストールだからPlayストアへ誘導する」という判断はUPDATE_REQUIRED側でのみ成立し、
    // UNAVAILABLE側では成立しない（HealthConnectAvailability.kt・screen/common/
    // HealthConnectUnavailableNotice.ktのコメント参照）。
    val availability: HealthConnectAvailability
        get() =
            when (HealthConnectClient.getSdkStatus(appContext)) {
                HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.INSTALLED
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                    HealthConnectAvailability.UPDATE_REQUIRED
                else -> HealthConnectAvailability.UNAVAILABLE
            }

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(appContext) }

    // client.features.getFeatureStatus()はIPC不要の同期呼び出し（実装はキャッシュされたバージョン情報を
    // 参照するのみ）。Health Connectのアップデートで対応状況が変わり得るため、呼び出し元で画面復帰のたびに
    // 再取得する（availabilityと同じ方針）。
    val isHistoryReadFeatureAvailable: Boolean
        get() =
            client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    // getGrantedPermissions()はIPC呼び出しのため、Health Connect側の更新中などに
    // RemoteException / IOException / IllegalStateExceptionを投げ得る（KDoc記載）。
    // 呼び出し元を巻き込んで落とさず、失敗はnullとして伝える（lessons.md 4.1）。
    suspend fun getGrantedPermissions(): Set<String>? =
        try {
            client.permissionController.getGrantedPermissions()
        } catch (e: RemoteException) {
            null
        } catch (e: IOException) {
            null
        } catch (e: IllegalStateException) {
            null
        }

    fun createPermissionRequestContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    // WBS 6.9: Health Connect要アップデート時の案内（要件§5「Health Connect未対応端末への案内」）から
    // 使う。HealthConnectAvailability.UPDATE_REQUIRED（API 28〜33限定、未インストール・無効化・バージョン
    // 古いのいずれか）でのみ呼ばれる想定。公式のHealth Connect codelab/サンプルと同じ形のPlayストア
    // 導線で、インストール・更新のどちらであってもPlayストア側が端末の状態に応じて「インストール」
    // 「アップデート」ボタンを出し分けるため、呼び出し側で状態を判定する必要はない。
    // url=healthconnect://onboardingは、インストール後にHealth Connect自身のオンボーディング画面まで
    // 直接開かせるための公式パラメータ。
    fun createOpenInPlayStoreIntent(): Intent {
        val uri = Uri.parse("market://details?id=$PROVIDER_PACKAGE_NAME&url=healthconnect%3A%2F%2Fonboarding")
        return Intent(Intent.ACTION_VIEW).apply {
            setPackage(PLAY_STORE_PACKAGE_NAME)
            data = uri
            putExtra("overlay", true)
            putExtra("callerId", appContext.packageName)
        }
    }

    // コードレビュー指摘: createOpenInPlayStoreIntent()はsetPackage()でPlayストアアプリを明示的に
    // 指定しており、Playストアが無効化・未搭載の端末では解決に失敗する（lessons.md 6.22のパッケージ
    // 可視性の話とは別の問題）。setPackage()を指定しないブラウザ経由のフォールバック用。
    fun createOpenInPlayStoreWebIntent(): Intent {
        val uri = Uri.parse("https://play.google.com/store/apps/details?id=$PROVIDER_PACKAGE_NAME")
        return Intent(Intent.ACTION_VIEW, uri)
    }

    // WBS 6.3: 詳細画面のRecordsタブ用。readAllWeightRecords()（全件を1つのListに溜め込む旧実装、
    // Heart RateでOutOfMemoryErrorを起こした。lessons.md 6.7）を置き換える。historyPermissionGrantedに
    // 応じたfilterの決定は入り口で一度だけ行い（readRecords()のKDocが明記する例外はRemoteException /
    // SecurityException / IOExceptionの3つ。lessons.md 6.1）、ページング中の自動フォールバック再試行は
    // 行わない（Health Connectのtokenは特定のfilterに基づくため、途中でfilterを切り替える整合性が
    // 取れない。readWithHistoryFallback()とは異なる考え方）。
    //
    // 履歴読み取り権限がない場合もrecentRangeFilter()（終了側無制限のafter()）は使わず、ここで
    // 取得したnowを両端に固定したbetween()にする（レビュー指摘）。recentRangeFilter()のまま複数回
    // 読み直すと、ページング中にHealth Connectへ新しいレコードが書き込まれた場合、その都度クエリの
    // 実質的な終了側が広がり、破棄後のprepend再読込で「ページ0」の内容が最初と変わってtokenの境界が
    // ずれ、initialLoadSizeの問題（lessons.md 6.16）と同じ理屈でレコードが静かに欠落しうる。
    // historyPermissionGranted=trueのbefore(now)は終了側が固定のため、この問題は起きない。
    //
    // recordType = WeightRecord::classの形式でReadRecordsRequestを構築すると、deduplicateStrategyは
    // 指定しなくてもDISABLED（重複排除なし）になる（D-007と整合。CLAUDE.md・lessons.md 6.3参照）。
    fun weightRecordsPagingSource(historyPermissionGranted: Boolean): PagingSource<Int, PagedRecord<WeightRecord>> {
        val now = Instant.now()
        val filter =
            if (historyPermissionGranted) {
                TimeRangeFilter.before(now)
            } else {
                TimeRangeFilter.between(now.minus(HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS), now)
            }
        return HealthRecordsPagingSource { pageToken, pageSize ->
            val response =
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = WeightRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = false,
                        pageSize = pageSize,
                        pageToken = pageToken,
                    ),
                )
            HealthRecordsPage(records = response.records, nextPageToken = response.pageToken?.ifEmpty { null })
        }
    }

    // WBS 2.3（PoC 1）: ALLの開始日（最古のレコード時刻）を、全件ページングせずに特定する。
    // requirements.md §22.4の見込み通り、昇順（ascendingOrder = true）・pageSize = 1で呼べば
    // 最古のレコード1件だけを1回のIPCで取得できる（全件ページングは不要）。
    // 例外・フォールバックの方針は6.1参照（フォールバック自体の実装はreadWithHistoryFallback()参照）。
    suspend fun findOldestWeightRecordTime(historyPermissionGranted: Boolean): OldestRecordResult {
        suspend fun readOldest(filter: TimeRangeFilter): Instant? =
            client
                .readRecords(
                    ReadRecordsRequest(
                        recordType = WeightRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = true,
                        pageSize = 1,
                    ),
                ).records
                .firstOrNull()
                ?.time

        val primaryFilter = if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter()
        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = primaryFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilter(),
                    read = ::readOldest,
                )
        ) {
            is HistoryFallbackOutcome.Success -> OldestRecordResult.Success(time = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> OldestRecordResult.Failure
        }
    }

    // WBS 6.1: ホーム画面の体重カード（「最新値＋前回比」、期間タブに依存しない）用。
    // findOldestWeightRecordTime()と同じ形（全件ページング不要）だが、降順・pageSize = limitで
    // 直近limit件を取得する。records[0]が最新値、records.getOrNull(1)との差が前回比になる。
    // records[0]がいつの記録かは、呼び出し元（HomeScreen.WeightCard）がLatestRecordDateText()で
    // 併記する（WBS 6.10、コードレビュー指摘。findLatestRestingHeartRateRecords()のコメント参照）。
    suspend fun findLatestWeightRecords(limit: Int, historyPermissionGranted: Boolean): WeightRecordsResult {
        suspend fun readLatest(filter: TimeRangeFilter): List<WeightRecord> =
            client
                .readRecords(
                    ReadRecordsRequest(
                        recordType = WeightRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = false,
                        pageSize = limit,
                    ),
                ).records

        val primaryFilter = if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter()
        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = primaryFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilter(),
                    read = ::readLatest,
                )
        ) {
            is HistoryFallbackOutcome.Success -> WeightRecordsResult.Success(records = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> WeightRecordsResult.Failure
        }
    }

    // WBS 2.2（PoC 1）: 同日複数レコードのグラフ上の扱い。
    // D-027の通り、アプリ独自に平均／最新値を選ぶのではなく、Health Connect公式のAggregate Metric
    // （WEIGHT_AVG / WEIGHT_MIN / WEIGHT_MAX）をaggregateGroupByPeriod()でbucket集計して使う。
    // aggregateGroupByPeriod()はLocalDateTimeでbucket境界を扱う（Instant/Durationベースの
    // aggregateGroupByDuration()との違い）が、bucketの区切りは渡したtimeRangeFilterの開始時刻を
    // 起点にPeriod単位で機械的に等間隔に区切るだけで、暦日・暦月の境界に自動的には合わせてくれない。
    // 暦日・暦月区切りにするには、呼び出し元が開始時刻を日初／月初に切り捨てて渡す必要がある
    // （呼び出し元のscreen/detail/DetailGraphRange.kt resolveDetailGraphRange()で対応。レビュー指摘、要検証）。
    // これが正しく機能すれば「タイムゾーン変更・夏時間の扱い」（requirements.md §10）にも
    // 対応しやすくなる見込みだが、実データでの確認はまだできていない。
    //
    // timeRangeFilterはLocalDateTimeベース（TimeRangeFilter.before/after(LocalDateTime)）で渡すこと。
    // Instantベースのfilterを渡すとIllegalArgumentException（"Either use TimeRangeFilter with
    // LocalDateTime or AggregateGroupByDurationRequest"）になる（エミュレータで確認、lessons.md 6.5。
    // Pixel 11実機では未確認）。weightRecordsPagingSource() / findOldestWeightRecordTime()が使う
    // readRecords()はInstantベースのみ受け付けるため、両者でTimeRangeFilterを使い回せない点に注意。
    suspend fun readWeightAggregates(
        timeRangeFilter: TimeRangeFilter,
        bucket: Period,
        historyPermissionGranted: Boolean,
    ): WeightAggregatesResult {
        suspend fun readAggregates(filter: TimeRangeFilter) =
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(WeightRecord.WEIGHT_AVG, WeightRecord.WEIGHT_MIN, WeightRecord.WEIGHT_MAX),
                    timeRangeFilter = filter,
                    timeRangeSlicer = bucket,
                ),
            )

        suspend fun toBuckets(filter: TimeRangeFilter) =
            readAggregates(filter).map { grouped ->
                WeightAggregateBucket(
                    periodStart = grouped.startTime,
                    average = grouped.result[WeightRecord.WEIGHT_AVG]?.inKilograms,
                    min = grouped.result[WeightRecord.WEIGHT_MIN]?.inKilograms,
                    max = grouped.result[WeightRecord.WEIGHT_MAX]?.inKilograms,
                )
            }

        // フォールバック先（直近30日）でも再びSecurityExceptionになり得る点はfindOldestWeightRecordTime()
        // と同じ（レビュー指摘、readWithHistoryFallback()側で共通に捕捉する）。加えてここは
        // `recentRangeFilterLocal()`（LocalDateTimeベース）を使うため、Health ConnectがLocalDateTimeの範囲を
        // レコードごとのタイムゾーンで解釈する場合、実際の範囲が`Instant.now()`基準の30日よりわずかに
        // 古い側にずれ、フォールバックでも例外になる可能性が理論上ある（未検証）。
        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = timeRangeFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilterLocal(),
                    read = ::toBuckets,
                )
        ) {
            is HistoryFallbackOutcome.Success -> WeightAggregatesResult.Success(buckets = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> WeightAggregatesResult.Failure
        }
    }

    // WBS 6.10（優先度Aのデータ型を順次対応）: Resting Heart Rateを追加。RestingHeartRateRecordは
    // HeartRateRecordと異なりサンプル配列を持たない単一時刻・単一値のレコード（InstantaneousRecord、
    // beatsPerMinuteはLong）で、requirements.md §22.2の通り公式の重複処理もない。構造としては
    // WeightRecord（単一時刻・単一値、重複処理なし）と同じため、Weight用の4関数
    // （weightRecordsPagingSource/findOldestWeightRecordTime/findLatestWeightRecords/
    // readWeightAggregates）をそのまま踏襲する。weight.inKilogramsのような単位変換が無い点のみ
    // HeartRateRecordのBPM_AVG/BPM_MIN/BPM_MAX（AggregateMetric<Long>）と同じ（D-044）。
    fun restingHeartRateRecordsPagingSource(historyPermissionGranted: Boolean): PagingSource<Int, PagedRecord<RestingHeartRateRecord>> {
        val now = Instant.now()
        val filter =
            if (historyPermissionGranted) {
                TimeRangeFilter.before(now)
            } else {
                TimeRangeFilter.between(now.minus(HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS), now)
            }
        return HealthRecordsPagingSource { pageToken, pageSize ->
            val response =
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = RestingHeartRateRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = false,
                        pageSize = pageSize,
                        pageToken = pageToken,
                    ),
                )
            HealthRecordsPage(records = response.records, nextPageToken = response.pageToken?.ifEmpty { null })
        }
    }

    // findOldestWeightRecordTime()と同じ理由・同じ形。
    suspend fun findOldestRestingHeartRateRecordTime(historyPermissionGranted: Boolean): OldestRecordResult {
        suspend fun readOldest(filter: TimeRangeFilter): Instant? =
            client
                .readRecords(
                    ReadRecordsRequest(
                        recordType = RestingHeartRateRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = true,
                        pageSize = 1,
                    ),
                ).records
                .firstOrNull()
                ?.time

        val primaryFilter = if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter()
        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = primaryFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilter(),
                    read = ::readOldest,
                )
        ) {
            is HistoryFallbackOutcome.Success -> OldestRecordResult.Success(time = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> OldestRecordResult.Failure
        }
    }

    // WBS 6.10: ホーム画面のResting Heart Rateカード（「最新値＋前回比」、期間タブに依存しない）用。
    // findLatestWeightRecords()と同じ形・同じ理由（D-044）。Heart Rateカード（選択期間の平均・最小・
    // 最大、readHeartRateAggregateSummary()）とは異なり、Resting Heart Rateは安静時に日1回程度しか
    // 記録されないことが多く、継続記録されるHeart Rateとは記録頻度のプロファイルが異なる（一般的な
    // Health Connect連携アプリの傾向としての想定。実装前の設計判断の時点ではこのアプリでの実測はまだ
    // 無かった）ため、期間タブごとのAggregateではなくWeightと同じ「最新値＋前回比」を採用した。
    // Pixel 11実機のFitソースで2025/10/25〜2025/11/26の33日間に32件（1日あたりほぼ1件、ほぼ連続した
    // 日次記録。欠けていたのは2025/11/10の1日のみであることをRecordsタブで直接確認した）を確認でき、
    // この想定と一致した（D-044、単一ソース・短い期間のみの確認）。
    //
    // **この関数が返す「最新2件」は、確認時点（実機の端末日付）に近いとは限らない**（コードレビュー
    // 指摘）。上記の実機データはこのソースが2025/11/26を最後に書き込みを止めており、確認日
    // （2026-10-02）から見て最新レコードは既に約10ヶ月前だった。この関数はHealth Connectに実際に
    // 保存されている最新のレコードを返すだけで、それが「いつの記録か」自体はこの関数の戻り値だけからは
    // 分からない。呼び出し元（HomeScreen.RestingHeartRateCard/WeightCard）がLatestRecordDateText()で
    // 記録日を併記することで、カード単体でも古さに気付けるようにしている（WBS 6.10、この指摘を受けて
    // WeightCard側も合わせて対応した）。
    suspend fun findLatestRestingHeartRateRecords(limit: Int, historyPermissionGranted: Boolean): RestingHeartRateRecordsResult {
        suspend fun readLatest(filter: TimeRangeFilter): List<RestingHeartRateRecord> =
            client
                .readRecords(
                    ReadRecordsRequest(
                        recordType = RestingHeartRateRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = false,
                        pageSize = limit,
                    ),
                ).records

        val primaryFilter = if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter()
        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = primaryFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilter(),
                    read = ::readLatest,
                )
        ) {
            is HistoryFallbackOutcome.Success ->
                RestingHeartRateRecordsResult.Success(records = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> RestingHeartRateRecordsResult.Failure
        }
    }

    // readWeightAggregates()と同じ形。metricsだけRestingHeartRateRecord.BPM_AVG/BPM_MIN/BPM_MAXに
    // 差し替える（HeartRateRecordと同じAggregateMetric<Long>のため単位変換は不要）。
    suspend fun readRestingHeartRateAggregates(
        timeRangeFilter: TimeRangeFilter,
        bucket: Period,
        historyPermissionGranted: Boolean,
    ): RestingHeartRateAggregatesResult {
        suspend fun readAggregates(filter: TimeRangeFilter) =
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics =
                        setOf(RestingHeartRateRecord.BPM_AVG, RestingHeartRateRecord.BPM_MIN, RestingHeartRateRecord.BPM_MAX),
                    timeRangeFilter = filter,
                    timeRangeSlicer = bucket,
                ),
            )

        suspend fun toBuckets(filter: TimeRangeFilter) =
            readAggregates(filter).map { grouped ->
                RestingHeartRateAggregateBucket(
                    periodStart = grouped.startTime,
                    average = grouped.result[RestingHeartRateRecord.BPM_AVG],
                    min = grouped.result[RestingHeartRateRecord.BPM_MIN],
                    max = grouped.result[RestingHeartRateRecord.BPM_MAX],
                )
            }

        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = timeRangeFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilterLocal(),
                    read = ::toBuckets,
                )
        ) {
            is HistoryFallbackOutcome.Success ->
                RestingHeartRateAggregatesResult.Success(buckets = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> RestingHeartRateAggregatesResult.Failure
        }
    }

    // WBS 6.10（優先度Aのデータ型を順次対応）: Blood Pressureを追加。BloodPressureRecordもWeight/
    // RestingHeartRateRecordと同じ単一時刻・単一レコード（InstantaneousRecord、サンプル配列を持たない、
    // 公式の重複処理もない。requirements.md §22.2）のため、Weight用の4関数と同じ構造をそのまま踏襲する。
    // 値フィールドがsystolic/diastolicの2つ（いずれもPressure型）ある点のみWeight/RestingHeartRateと異なる
    // （D-045）。
    fun bloodPressureRecordsPagingSource(historyPermissionGranted: Boolean): PagingSource<Int, PagedRecord<BloodPressureRecord>> {
        val now = Instant.now()
        val filter =
            if (historyPermissionGranted) {
                TimeRangeFilter.before(now)
            } else {
                TimeRangeFilter.between(now.minus(HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS), now)
            }
        return HealthRecordsPagingSource { pageToken, pageSize ->
            val response =
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = BloodPressureRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = false,
                        pageSize = pageSize,
                        pageToken = pageToken,
                    ),
                )
            HealthRecordsPage(records = response.records, nextPageToken = response.pageToken?.ifEmpty { null })
        }
    }

    // findOldestWeightRecordTime()と同じ理由・同じ形。
    suspend fun findOldestBloodPressureRecordTime(historyPermissionGranted: Boolean): OldestRecordResult {
        suspend fun readOldest(filter: TimeRangeFilter): Instant? =
            client
                .readRecords(
                    ReadRecordsRequest(
                        recordType = BloodPressureRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = true,
                        pageSize = 1,
                    ),
                ).records
                .firstOrNull()
                ?.time

        val primaryFilter = if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter()
        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = primaryFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilter(),
                    read = ::readOldest,
                )
        ) {
            is HistoryFallbackOutcome.Success -> OldestRecordResult.Success(time = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> OldestRecordResult.Failure
        }
    }

    // WBS 6.10: ホーム画面のBlood Pressureカード（「最新値＋前回比」、期間タブに依存しない）用。
    // findLatestWeightRecords()/findLatestRestingHeartRateRecords()と同じ形・同じ理由（D-045）。
    // 「最新値」がいつの記録かは、呼び出し元（HomeScreen.BloodPressureCard）がLatestRecordDateText()で
    // 併記する（findLatestRestingHeartRateRecords()のコメント参照。Resting Heart Rateで実際に約10ヶ月前の
    // 値が「今の値」のように見えた事例があるため、血圧でも同じ対応を最初から組み込む）。
    suspend fun findLatestBloodPressureRecords(limit: Int, historyPermissionGranted: Boolean): BloodPressureRecordsResult {
        suspend fun readLatest(filter: TimeRangeFilter): List<BloodPressureRecord> =
            client
                .readRecords(
                    ReadRecordsRequest(
                        recordType = BloodPressureRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = false,
                        pageSize = limit,
                    ),
                ).records

        val primaryFilter = if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter()
        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = primaryFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilter(),
                    read = ::readLatest,
                )
        ) {
            is HistoryFallbackOutcome.Success ->
                BloodPressureRecordsResult.Success(records = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> BloodPressureRecordsResult.Failure
        }
    }

    // readWeightAggregates()と同じ形。metricsはSYSTOLIC_AVG/DIASTOLIC_AVGの2つのみ（BloodPressureAggregateBucket
    // のコメント参照: MIN/MAXも公式Aggregate Metricとして存在するが、グラフでの表示方法としてAVGのみを
    // 採用したため取得しない。Pressure型の実際のKotlinプロパティ名inMillimetersOfMercuryは、Energy型の
    // inKilocalories確認時（D-043(3)）と同じ方法（javap逆コンパイル+.classバイナリの文字列直接検索）で
    // 確認した、D-045）。
    suspend fun readBloodPressureAggregates(
        timeRangeFilter: TimeRangeFilter,
        bucket: Period,
        historyPermissionGranted: Boolean,
    ): BloodPressureAggregatesResult {
        suspend fun readAggregates(filter: TimeRangeFilter) =
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(BloodPressureRecord.SYSTOLIC_AVG, BloodPressureRecord.DIASTOLIC_AVG),
                    timeRangeFilter = filter,
                    timeRangeSlicer = bucket,
                ),
            )

        suspend fun toBuckets(filter: TimeRangeFilter) =
            readAggregates(filter).map { grouped ->
                BloodPressureAggregateBucket(
                    periodStart = grouped.startTime,
                    systolicAverage = grouped.result[BloodPressureRecord.SYSTOLIC_AVG]?.inMillimetersOfMercury,
                    diastolicAverage = grouped.result[BloodPressureRecord.DIASTOLIC_AVG]?.inMillimetersOfMercury,
                )
            }

        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = timeRangeFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilterLocal(),
                    read = ::toBuckets,
                )
        ) {
            is HistoryFallbackOutcome.Success -> BloodPressureAggregatesResult.Success(buckets = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> BloodPressureAggregatesResult.Failure
        }
    }

    // WBS 6.10（優先度Aのデータ型を順次対応）: Body Fatを追加。BodyFatRecordもWeight/RestingHeartRate/
    // BloodPressureRecordと同じ単一時刻のレコード（InstantaneousRecord、サンプル配列を持たない、
    // 公式の重複処理もない。requirements.md §22.2）のため、Records/Sources/ホームカード用の3関数は
    // Weight用の3関数をそのまま踏襲する（D-046(1)）。
    fun bodyFatRecordsPagingSource(historyPermissionGranted: Boolean): PagingSource<Int, PagedRecord<BodyFatRecord>> {
        val now = Instant.now()
        val filter =
            if (historyPermissionGranted) {
                TimeRangeFilter.before(now)
            } else {
                TimeRangeFilter.between(now.minus(HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS), now)
            }
        return HealthRecordsPagingSource { pageToken, pageSize ->
            val response =
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = BodyFatRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = false,
                        pageSize = pageSize,
                        pageToken = pageToken,
                    ),
                )
            HealthRecordsPage(records = response.records, nextPageToken = response.pageToken?.ifEmpty { null })
        }
    }

    // findOldestWeightRecordTime()と同じ理由・同じ形。
    suspend fun findOldestBodyFatRecordTime(historyPermissionGranted: Boolean): OldestRecordResult {
        suspend fun readOldest(filter: TimeRangeFilter): Instant? =
            client
                .readRecords(
                    ReadRecordsRequest(
                        recordType = BodyFatRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = true,
                        pageSize = 1,
                    ),
                ).records
                .firstOrNull()
                ?.time

        val primaryFilter = if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter()
        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = primaryFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilter(),
                    read = ::readOldest,
                )
        ) {
            is HistoryFallbackOutcome.Success -> OldestRecordResult.Success(time = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> OldestRecordResult.Failure
        }
    }

    // WBS 6.10: ホーム画面のBody Fatカード（「最新値＋前回比」、期間タブに依存しない）用。
    // findLatestWeightRecords()と同じ形・同じ理由。体組成計による体脂肪率測定もWeightと同程度の
    // 低頻度（1日1回程度）が一般的という想定だが、このアプリの実データでは未確認のまま採用する
    // （D-044(2)/D-045(2)と同じ基準、D-046(2)）。
    suspend fun findLatestBodyFatRecords(limit: Int, historyPermissionGranted: Boolean): BodyFatRecordsResult {
        suspend fun readLatest(filter: TimeRangeFilter): List<BodyFatRecord> =
            client
                .readRecords(
                    ReadRecordsRequest(
                        recordType = BodyFatRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = false,
                        pageSize = limit,
                    ),
                ).records

        val primaryFilter = if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter()
        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = primaryFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilter(),
                    read = ::readLatest,
                )
        ) {
            is HistoryFallbackOutcome.Success ->
                BodyFatRecordsResult.Success(records = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> BodyFatRecordsResult.Failure
        }
    }

    // WBS 6.10（D-046(3)）: BodyFatRecordには公式のAggregateMetricが存在しない（javap逆コンパイルで
    // 確認。WeightRecord.WEIGHT_AVG等に相当するものが無い。CLAUDE.md・lessons.md 6.27参照）。
    // そのためreadWeightAggregates()のようにaggregateGroupByPeriod()を呼ぶだけでは実装できず、
    // Rawレコードを全件読み取ってアプリ側でbucket集計する（requirements.md §22.2の
    // 「Aggregateがなければ、読み込んだRawから自前で集約する」方針の最初の適用例）。
    //
    // timeRangeFilterは呼び出し元（BodyFatDetailScreen）からWeightと同じ形（resolveDetailGraphRange()が
    // 返すLocalDateTimeベースのTimeRangeFilter）で渡ってくるが、readRecords()はInstantベースのfilterしか
    // 受け付けない（lessons.md 6.5の逆方向の制約）ため、まずInstantベースに変換してから全件走査する。
    // bucket境界（bucket: Period刻み）は、aggregateGroupByPeriod()の実際の挙動（lessons.md 7.5: 開始時刻を
    // 起点に機械的に等間隔区切り、暦日・暦月への自動整列はしない）を自前で再現する。呼び出し元
    // （resolveDetailGraphRange()）が渡すfilterの開始時刻は既に暦日・暦月に切り捨て済みのため、
    // ここでは単純に「開始時刻からPeriod刻みで区切る」だけでよい。
    suspend fun readBodyFatAggregates(
        timeRangeFilter: TimeRangeFilter,
        bucket: Period,
        historyPermissionGranted: Boolean,
    ): BodyFatAggregatesResult {
        val zone = ZoneId.systemDefault()

        // コードレビュー指摘（WBS 6.10、HRV追加時）: この関数は`readHrvAggregates()`にそのまま複製されて
        // いる。**この広げ幅・クランプ判定ロジックを変更する場合は、必ず`readHrvAggregates()`側の同じ
        // ロジックにも同じ修正を入れること**（Blood Glucose / SpO2で複製が増えた場合も同様）。
        //
        // Health Connectのタイムゾーンオフセットはレコードを記録した端末依存でUTC-12〜UTC+14の範囲を
        // 取り得る（最大スプレッド26時間）。読み取り範囲のInstant変換は端末のタイムゾーン（zone）基準だが、
        // bucket割り当て（下記toBuckets()）はレコード自身のzoneOffset基準のため、記録側のoffsetが端末と
        // 大きく異なる場合（旅行先での記録、オフセットをUTCで書き込むアプリ等）、本来含めるべきレコードが
        // 読み取り範囲の端で漏れうる（コードレビュー指摘）。読み取り範囲を前後にこの分だけ広げておき、
        // toBuckets()側でレコードごとのzoneOffsetで計算した現地時刻がrangeStart〜rangeEndに収まるものだけ
        // を対象にすることで、広げた分の余分なレコードを除外する。
        //
        // **広げる前の開始時刻が既に直近HISTORY_FALLBACK_DAYS（30日）の内側にある場合、広げた結果を
        // `Instant.now() - HISTORY_FALLBACK_DAYS`より古くしてはいけない**（lessons.md 6.1: それより古い
        // 範囲を含めるとreadRecords()はSecurityExceptionになる）。この判定はhistoryPermissionGranted
        // （呼び出し元のBodyFatDetailScreenがホーム画面遷移時点でスナップショットした値）では**行わない**。
        // 詳細画面を開いた後に履歴読み取り権限が取り消された場合（lessons.md 3.1・6.1が想定するケース）、
        // historyPermissionGrantedはtrueのまま最初の読み取りがSecurityExceptionになり、
        // readWithHistoryFallback()がfallbackFilter（recentRangeFilterLocal()、境界ぎりぎりまで寄せた値。
        // 安全マージンは最大24時間、nowの時刻によっては0時間に近い）で読み直すが、このfallback読み取りも
        // 同じreadAllRecords()を通るため、historyPermissionGrantedの値だけで判定すると、trueのままの
        // flagに引きずられてfallback側まで26時間広げられたままになり、境界を超えて2回目もSecurityException
        // になってしまう（コードレビュー指摘）。「広げる前の開始時刻が境界の内側かどうか」という実際の値で
        // 判定すれば、historyPermissionGrantedが不正確（取得中に取り消された等）でも、権限が無い場合の
        // 最初の読み取り・fallbackの読み取りのどちらでも正しくクランプされる。境界に近いレコードを
        // 取りこぼしうる点は、履歴読み取り権限が無い場合の既存の30日近似（lessons.md 6.1）が元々
        // 許容している誤差の範囲に収まる。
        val maxZoneOffsetSpread = Duration.ofHours(26)

        // localFilter（LocalDateTimeベース）をInstantベースに変換し、readRecords()で全件走査する。
        // ZoneId.systemDefault()を使うのは、呼び出し元resolveDetailGraphRange()がLocalDateTime.now()・
        // 端末の暦日/暦月境界を基準にfilterを組み立てているため。
        suspend fun readAllRecords(localFilter: TimeRangeFilter): List<BodyFatRecord> {
            val recentFloor = Instant.now().minus(HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS)
            val unwidenedStart = localFilter.localStartTime?.atZone(zone)?.toInstant()
            val start =
                unwidenedStart?.let { s ->
                    val widened = s.minus(maxZoneOffsetSpread)
                    if (!s.isBefore(recentFloor)) maxOf(widened, recentFloor) else widened
                }
            val end = (localFilter.localEndTime?.atZone(zone)?.toInstant() ?: Instant.now()).plus(maxZoneOffsetSpread)
            val instantFilter = if (start != null) TimeRangeFilter.between(start, end) else TimeRangeFilter.before(end)

            val records = mutableListOf<BodyFatRecord>()
            var pageToken: String? = null
            do {
                val response =
                    client.readRecords(
                        ReadRecordsRequest(recordType = BodyFatRecord::class, timeRangeFilter = instantFilter, pageToken = pageToken),
                    )
                records += response.records
                pageToken = response.pageToken?.ifEmpty { null }
            } while (pageToken != null)
            return records
        }

        suspend fun toBuckets(localFilter: TimeRangeFilter): List<BodyFatAggregateBucket> {
            val records = readAllRecords(localFilter)
            if (records.isEmpty()) return emptyList()

            // rangeStart: bucket列の起点。通常はlocalFilter.localStartTime（呼び出し元
            // resolveDetailGraphRange()が暦日/暦月に切り捨て済み）をそのまま使う。ALLで
            // findOldestBodyFatRecordTime()自体が失敗した場合（開始無制限のfilter、
            // WeightDetailScreen.ktのoldestGateForAllコメントと同じ状況）はlocalStartTimeがnullになる
            // ため、実際に読めたレコードの中で最も古いものの時刻を暦日/暦月に切り捨てて起点にする。
            val rangeStart =
                localFilter.localStartTime ?: run {
                    val earliestDate = records.minOf { it.time }.atZone(zone).toLocalDate()
                    if (bucket.months != 0 || bucket.years != 0) earliestDate.withDayOfMonth(1).atStartOfDay() else earliestDate.atStartOfDay()
                }
            val rangeEnd = localFilter.localEndTime ?: LocalDateTime.now()

            val bucketStarts = mutableListOf<LocalDateTime>()
            var current = rangeStart
            while (current.isBefore(rangeEnd)) {
                bucketStarts += current
                current = current.plus(bucket)
            }
            if (bucketStarts.isEmpty()) return emptyList()

            // レコードが1件もないbucketもnullのまま残す（WeightAggregateBucketと同じ「0で埋めない」方針。
            // BodyFatAggregateChart側がnullのbucketを自動的にスキップする）。
            val valuesByBucket = Array(bucketStarts.size) { mutableListOf<Double>() }
            for (record in records) {
                val localDateTime = record.time.atZone(record.zoneOffset ?: zone).toLocalDateTime()
                // readAllRecords()がmaxZoneOffsetSpread分だけ広げて読んでいるため、レコード自身の
                // zoneOffsetで計算した現地時刻がrangeStart〜rangeEndに実際に収まるものだけを対象にする
                // （コードレビュー指摘: 上限チェックが無いと、広げた分で余分に読めた範囲外のレコードが
                // 最後のbucketに混入する。下限はindexOfLast側で自然に除外されるが、ここで両端を明示する）。
                if (localDateTime.isBefore(rangeStart) || !localDateTime.isBefore(rangeEnd)) continue
                val bucketIndex = bucketStarts.indexOfLast { !it.isAfter(localDateTime) }
                if (bucketIndex >= 0) {
                    valuesByBucket[bucketIndex] += record.percentage.value
                }
            }

            return bucketStarts.mapIndexed { index, start ->
                val values = valuesByBucket[index]
                BodyFatAggregateBucket(
                    periodStart = start,
                    average = values.takeIf { it.isNotEmpty() }?.average(),
                    min = values.minOrNull(),
                    max = values.maxOrNull(),
                )
            }
        }

        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = timeRangeFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilterLocal(),
                    read = ::toBuckets,
                )
        ) {
            is HistoryFallbackOutcome.Success -> BodyFatAggregatesResult.Success(buckets = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> BodyFatAggregatesResult.Failure
        }
    }

    // WBS 6.10（優先度Aのデータ型を順次対応）: HRVを追加。HeartRateVariabilityRmssdRecordもWeight/
    // RestingHeartRate/BloodPressure/BodyFatと同じ単一時刻のレコード（InstantaneousRecord、サンプル
    // 配列を持たない、公式の重複処理もない。requirements.md §22.2）のため、Records/Sources/ホーム
    // カード用の3関数はWeight用の3関数をそのまま踏襲する（Body Fatと同じ判断、D-047(1)）。
    fun hrvRecordsPagingSource(historyPermissionGranted: Boolean): PagingSource<Int, PagedRecord<HeartRateVariabilityRmssdRecord>> {
        val now = Instant.now()
        val filter =
            if (historyPermissionGranted) {
                TimeRangeFilter.before(now)
            } else {
                TimeRangeFilter.between(now.minus(HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS), now)
            }
        return HealthRecordsPagingSource { pageToken, pageSize ->
            val response =
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = HeartRateVariabilityRmssdRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = false,
                        pageSize = pageSize,
                        pageToken = pageToken,
                    ),
                )
            HealthRecordsPage(records = response.records, nextPageToken = response.pageToken?.ifEmpty { null })
        }
    }

    // findOldestBodyFatRecordTime()と同じ理由・同じ形。
    suspend fun findOldestHrvRecordTime(historyPermissionGranted: Boolean): OldestRecordResult {
        suspend fun readOldest(filter: TimeRangeFilter): Instant? =
            client
                .readRecords(
                    ReadRecordsRequest(
                        recordType = HeartRateVariabilityRmssdRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = true,
                        pageSize = 1,
                    ),
                ).records
                .firstOrNull()
                ?.time

        val primaryFilter = if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter()
        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = primaryFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilter(),
                    read = ::readOldest,
                )
        ) {
            is HistoryFallbackOutcome.Success -> OldestRecordResult.Success(time = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> OldestRecordResult.Failure
        }
    }

    // WBS 6.10で当初ホーム画面のHRVカード（「最新値＋前回比」）用にfindLatestBodyFatRecords()と
    // 同じ形で実装したが、採用時の前提（D-047(3)「HRVは多くのウェアラブルで1日1回算出される」）は
    // Pixel 11実機で誤りと確定した。実際は1日平均約57件、5〜10分間隔のバースト的な記録で、
    // 「前回比」が隣接する2サンプルの差という意味の薄い値になることを確認済み（lessons.md 6.29、
    // requirements.md §27）。WBS 6.11（D-048）でホームカードは「最新レコードがある日の平均＋
    // 前日比」（readHrvHomeSummary()）に変更したが、この関数自体は「最新レコードが何日か」を
    // 特定する処理としてreadHrvHomeSummary()から引き続き使われている（limit = 1で呼ばれる）。
    suspend fun findLatestHrvRecords(limit: Int, historyPermissionGranted: Boolean): HrvRecordsResult {
        suspend fun readLatest(filter: TimeRangeFilter): List<HeartRateVariabilityRmssdRecord> =
            client
                .readRecords(
                    ReadRecordsRequest(
                        recordType = HeartRateVariabilityRmssdRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = false,
                        pageSize = limit,
                    ),
                ).records

        val primaryFilter = if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter()
        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = primaryFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilter(),
                    read = ::readLatest,
                )
        ) {
            is HistoryFallbackOutcome.Success ->
                HrvRecordsResult.Success(records = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> HrvRecordsResult.Failure
        }
    }

    // WBS 6.10（D-047(4)）: HeartRateVariabilityRmssdRecordにもBodyFatRecordと同じく公式の
    // AggregateMetricが存在しない（javap逆コンパイルで確認。CLAUDE.md・lessons.md 6.28参照）。
    // readBodyFatAggregates()と全く同じロジックをHeartRateVariabilityRmssdRecord・
    // heartRateVariabilityMillisに差し替えただけ。heartRateVariabilityMillisはPercentage/Mass等と
    // 異なり単位型でラップされていない生のdouble（CLAUDE.md「SDK調査で誤解しやすい点」参照）のため、
    // 単位変換は不要。
    //
    // コードレビュー指摘（レビューで3回修正されたreadBodyFatAggregates()のクランプ判定ロジックを、
    // 理由の説明なしに複製していた点）: **このロジックを変更する場合は、必ずreadBodyFatAggregates()側
    // の同じロジックにも同じ修正を入れること**。要点だけ以下に残す（完全な経緯はreadBodyFatAggregates()
    // 側のコメント、D-046(3)のコードレビュー1〜3回目参照）。
    // - タイムゾーンオフセットの取り得る範囲（UTC-12〜UTC+14、最大スプレッド26時間）分だけ読み取り
    //   範囲を前後に広げて読む（レコード自身のzoneOffsetが端末のzoneと食い違う場合に備える）。
    // - **広げる前の開始時刻が既にHISTORY_FALLBACK_DAYS（30日）の内側にある場合、広げた結果を
    //   `Instant.now() - HISTORY_FALLBACK_DAYS`より古くしてはいけない**（それより古い範囲を含めると
    //   readRecords()がSecurityExceptionになる、lessons.md 6.1）。
    // - **この判定はhistoryPermissionGrantedフラグでは行わず、広げる前の開始時刻が実際に境界の内側に
    //   あるかどうかという値そのもので行うこと**。フラグで判定すると、履歴読み取り権限が詳細画面を開いた
    //   後に取り消されたケースで、readWithHistoryFallback()の2回目（fallback）の読み取りもまた26時間
    //   広げられたままになり、2回目もSecurityExceptionになってFailure（Chartエラー表示）になる。
    suspend fun readHrvAggregates(
        timeRangeFilter: TimeRangeFilter,
        bucket: Period,
        historyPermissionGranted: Boolean,
    ): HrvAggregatesResult {
        val zone = ZoneId.systemDefault()
        val maxZoneOffsetSpread = Duration.ofHours(26)

        suspend fun readAllRecords(localFilter: TimeRangeFilter): List<HeartRateVariabilityRmssdRecord> {
            val recentFloor = Instant.now().minus(HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS)
            val unwidenedStart = localFilter.localStartTime?.atZone(zone)?.toInstant()
            val start =
                unwidenedStart?.let { s ->
                    val widened = s.minus(maxZoneOffsetSpread)
                    // ↑の指摘: ここをhistoryPermissionGrantedで分岐しないこと。
                    if (!s.isBefore(recentFloor)) maxOf(widened, recentFloor) else widened
                }
            val end = (localFilter.localEndTime?.atZone(zone)?.toInstant() ?: Instant.now()).plus(maxZoneOffsetSpread)
            val instantFilter = if (start != null) TimeRangeFilter.between(start, end) else TimeRangeFilter.before(end)

            val records = mutableListOf<HeartRateVariabilityRmssdRecord>()
            var pageToken: String? = null
            do {
                val response =
                    client.readRecords(
                        ReadRecordsRequest(
                            recordType = HeartRateVariabilityRmssdRecord::class,
                            timeRangeFilter = instantFilter,
                            pageToken = pageToken,
                        ),
                    )
                records += response.records
                pageToken = response.pageToken?.ifEmpty { null }
            } while (pageToken != null)
            return records
        }

        suspend fun toBuckets(localFilter: TimeRangeFilter): List<HrvAggregateBucket> {
            val records = readAllRecords(localFilter)
            if (records.isEmpty()) return emptyList()

            val rangeStart =
                localFilter.localStartTime ?: run {
                    val earliestDate = records.minOf { it.time }.atZone(zone).toLocalDate()
                    if (bucket.months != 0 || bucket.years != 0) earliestDate.withDayOfMonth(1).atStartOfDay() else earliestDate.atStartOfDay()
                }
            val rangeEnd = localFilter.localEndTime ?: LocalDateTime.now()

            val bucketStarts = mutableListOf<LocalDateTime>()
            var current = rangeStart
            while (current.isBefore(rangeEnd)) {
                bucketStarts += current
                current = current.plus(bucket)
            }
            if (bucketStarts.isEmpty()) return emptyList()

            val valuesByBucket = Array(bucketStarts.size) { mutableListOf<Double>() }
            for (record in records) {
                val localDateTime = record.time.atZone(record.zoneOffset ?: zone).toLocalDateTime()
                if (localDateTime.isBefore(rangeStart) || !localDateTime.isBefore(rangeEnd)) continue
                val bucketIndex = bucketStarts.indexOfLast { !it.isAfter(localDateTime) }
                if (bucketIndex >= 0) {
                    valuesByBucket[bucketIndex] += record.heartRateVariabilityMillis
                }
            }

            return bucketStarts.mapIndexed { index, start ->
                val values = valuesByBucket[index]
                HrvAggregateBucket(
                    periodStart = start,
                    average = values.takeIf { it.isNotEmpty() }?.average(),
                    min = values.minOrNull(),
                    max = values.maxOrNull(),
                    count = values.size,
                )
            }
        }

        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = timeRangeFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilterLocal(),
                    read = ::toBuckets,
                )
        ) {
            is HistoryFallbackOutcome.Success -> HrvAggregatesResult.Success(buckets = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> HrvAggregatesResult.Failure
        }
    }

    // WBS 6.11（ホームカード方式見直し、D-048）: findLatestHrvRecords()が前提にしていた「最新値＋前回比」
    // パターンは、HRVの実際の記録頻度（1日平均約57件、5〜10分間隔のバースト、D-047(3)、lessons.md 6.29）
    // では前回比が隣接する2サンプルの差という意味の薄い値になっていた。「最新レコードがある日の平均＋
    // 前日比」に置き換える（件数・最小〜最大も添える、コードレビュー提案）。
    //
    // タイムゾーン対応・30日境界のクランプといった複雑なロジックはreadHrvAggregates()が既に持っている
    // ため複製しない。「最新レコードがある日の前日0時〜翌日0時」という2日分の範囲をbucket=1日で
    // readHrvAggregates()に渡し、返ってきた2bucket（前日・当日）をそのまま使うだけで実装できる。
    suspend fun readHrvHomeSummary(historyPermissionGranted: Boolean): HrvHomeSummaryResult {
        val latestRecordsResult = findLatestHrvRecords(limit = 1, historyPermissionGranted = historyPermissionGranted)
        val latest: HeartRateVariabilityRmssdRecord
        val recordsHistoryLimited: Boolean
        when (latestRecordsResult) {
            is HrvRecordsResult.Success -> {
                recordsHistoryLimited = latestRecordsResult.historyLimited
                latest =
                    latestRecordsResult.records.firstOrNull()
                        ?: return HrvHomeSummaryResult.Success(latestDay = null, previousDay = null, historyLimited = recordsHistoryLimited)
            }
            HrvRecordsResult.Failure -> return HrvHomeSummaryResult.Failure
        }

        val latestDate = latest.time.atZone(latest.zoneOffset ?: ZoneId.systemDefault()).toLocalDate()
        val timeRangeFilter = TimeRangeFilter.between(latestDate.minusDays(1).atStartOfDay(), latestDate.plusDays(1).atStartOfDay())
        val aggregatesResult =
            readHrvAggregates(timeRangeFilter = timeRangeFilter, bucket = Period.ofDays(1), historyPermissionGranted = historyPermissionGranted)

        fun toSummary(date: LocalDate, bucket: HrvAggregateBucket?): HrvDailySummary? {
            val average = bucket?.average ?: return null
            val min = bucket.min ?: return null
            val max = bucket.max ?: return null
            return HrvDailySummary(date = date, average = average, min = min, max = max, count = bucket.count)
        }

        // コードレビュー指摘: bucketsを[前日, 当日]の2要素と決め打ちしてインデックス（0/1）で取り出すと、
        // readHrvAggregates()が内部でSecurityExceptionによりrecentRangeFilterLocal()
        // （直近30日・終了無制限、本関数が渡した2日分の範囲とは無関係）にフォールバックした場合に
        // bucketsが約30個になり、全く別の日のbucketを「最新日」「前日」として扱ってしまう
        // （最新のHRVレコードが約29〜30日前で、かつ履歴読み取り権限が無い場合に起こりうる。クラッシュ
        // せず、誤った値を静かに表示する）。periodStartの日付で一致するbucketを探す方式にすれば、
        // フォールバックでbucket数が変わっても正しいbucketを拾える。
        //
        // 2回目のコードレビュー指摘: この修正後も次の狭いケースが残る。recentRangeFilterLocal()は
        // 範囲の始点を「30日前の翌日0時」に切り上げてクランプしている（lessons.md 6.1）ため、最新日が
        // ちょうど「30日前の日」に当たると、最新日のbucket自体がこの読み直し範囲から外れてしまい、
        // latestDayがnull（＝「データなし」表示）になる。findLatestHrvRecords()自体はレコードを
        // 見つけているので、「実際にはあるのにデータなしと表示する」ケースになる（誤った日の値を
        // 表示していた修正前よりは安全側。historyLimited=trueになるためカードは非表示にならず、
        // 履歴が制限されている旨の案内と一緒に表示される）。発生条件が「履歴読み取り権限が無い」かつ
        // 「最新記録がちょうど約30日前」に限られるため、追加のコード対応はしていない。
        return when (aggregatesResult) {
            is HrvAggregatesResult.Success -> {
                val latestBucket = aggregatesResult.buckets.find { it.periodStart.toLocalDate() == latestDate }
                val previousBucket = aggregatesResult.buckets.find { it.periodStart.toLocalDate() == latestDate.minusDays(1) }
                HrvHomeSummaryResult.Success(
                    latestDay = toSummary(latestDate, latestBucket),
                    previousDay = toSummary(latestDate.minusDays(1), previousBucket),
                    historyLimited = recordsHistoryLimited || aggregatesResult.historyLimited,
                )
            }
            HrvAggregatesResult.Failure -> HrvHomeSummaryResult.Failure
        }
    }

    // WBS 6.10（優先度Aのデータ型を順次対応）: Oxygen Saturation（SpO2）を追加。OxygenSaturationRecordも
    // Weight/RestingHeartRate/BloodPressure/BodyFat/HRVと同じ単一時刻のレコード（InstantaneousRecord、
    // 公式の重複処理もない。requirements.md §22.2）のため、Records/Sources用の2関数はWeight用の関数を
    // そのまま踏襲する（Body Fat/HRVと同じ判断）。
    //
    // ホームカードはfindLatestOxygenSaturationRecords()による「最新値＋前回比」。HRVが当初この方式を
    // 採用し、実際の記録頻度（1日平均約57件のバースト）が前提と食い違ったため見直しが必要になった経緯
    // （D-047→D-048）を踏まえ、実装前にPixel 11実機でSpO2の実際の記録頻度を確認してから方式を決める
    // 方針を立てたが、この端末にはSpO2のレコードが1件も無く確認できなかった。低頻度想定（パルス
    // オキシメーターでの散発測定）に基づきこの方式のまま暫定確定し、記録頻度は未確認のまま要検証として
    // 残した（D-049、requirements.md §27）。
    fun oxygenSaturationRecordsPagingSource(historyPermissionGranted: Boolean): PagingSource<Int, PagedRecord<OxygenSaturationRecord>> {
        val now = Instant.now()
        val filter =
            if (historyPermissionGranted) {
                TimeRangeFilter.before(now)
            } else {
                TimeRangeFilter.between(now.minus(HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS), now)
            }
        return HealthRecordsPagingSource { pageToken, pageSize ->
            val response =
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = OxygenSaturationRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = false,
                        pageSize = pageSize,
                        pageToken = pageToken,
                    ),
                )
            HealthRecordsPage(records = response.records, nextPageToken = response.pageToken?.ifEmpty { null })
        }
    }

    // findOldestBodyFatRecordTime()と同じ理由・同じ形。
    suspend fun findOldestOxygenSaturationRecordTime(historyPermissionGranted: Boolean): OldestRecordResult {
        suspend fun readOldest(filter: TimeRangeFilter): Instant? =
            client
                .readRecords(
                    ReadRecordsRequest(
                        recordType = OxygenSaturationRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = true,
                        pageSize = 1,
                    ),
                ).records
                .firstOrNull()
                ?.time

        val primaryFilter = if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter()
        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = primaryFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilter(),
                    read = ::readOldest,
                )
        ) {
            is HistoryFallbackOutcome.Success -> OldestRecordResult.Success(time = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> OldestRecordResult.Failure
        }
    }

    // ホーム画面のOxygen Saturationカード用。findLatestBodyFatRecords()と同じ形・同じ理由。
    suspend fun findLatestOxygenSaturationRecords(limit: Int, historyPermissionGranted: Boolean): OxygenSaturationRecordsResult {
        suspend fun readLatest(filter: TimeRangeFilter): List<OxygenSaturationRecord> =
            client
                .readRecords(
                    ReadRecordsRequest(
                        recordType = OxygenSaturationRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = false,
                        pageSize = limit,
                    ),
                ).records

        val primaryFilter = if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter()
        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = primaryFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilter(),
                    read = ::readLatest,
                )
        ) {
            is HistoryFallbackOutcome.Success ->
                OxygenSaturationRecordsResult.Success(records = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> OxygenSaturationRecordsResult.Failure
        }
    }

    // OxygenSaturationRecordには公式のAggregateMetricが存在しない（javap逆コンパイルで確認。
    // CLAUDE.md参照）。readBodyFatAggregates()と全く同じロジックをOxygenSaturationRecord・
    // percentageに差し替えただけ（readHrvAggregates()も同様に複製されている）。
    //
    // コードレビュー指摘（Body Fat/HRV実装時）: この関数はreadBodyFatAggregates()・readHrvAggregates()
    // と同じ広げ幅・クランプ判定ロジックを複製している。**このロジックを変更する場合は、必ず他の2関数
    // 側の同じロジックにも同じ修正を入れること**（Blood Glucoseで複製が増えた場合も同様）。要点は
    // readBodyFatAggregates()側のコメント参照。
    suspend fun readOxygenSaturationAggregates(
        timeRangeFilter: TimeRangeFilter,
        bucket: Period,
        historyPermissionGranted: Boolean,
    ): OxygenSaturationAggregatesResult {
        val zone = ZoneId.systemDefault()
        val maxZoneOffsetSpread = Duration.ofHours(26)

        suspend fun readAllRecords(localFilter: TimeRangeFilter): List<OxygenSaturationRecord> {
            val recentFloor = Instant.now().minus(HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS)
            val unwidenedStart = localFilter.localStartTime?.atZone(zone)?.toInstant()
            val start =
                unwidenedStart?.let { s ->
                    val widened = s.minus(maxZoneOffsetSpread)
                    if (!s.isBefore(recentFloor)) maxOf(widened, recentFloor) else widened
                }
            val end = (localFilter.localEndTime?.atZone(zone)?.toInstant() ?: Instant.now()).plus(maxZoneOffsetSpread)
            val instantFilter = if (start != null) TimeRangeFilter.between(start, end) else TimeRangeFilter.before(end)

            val records = mutableListOf<OxygenSaturationRecord>()
            var pageToken: String? = null
            do {
                val response =
                    client.readRecords(
                        ReadRecordsRequest(
                            recordType = OxygenSaturationRecord::class,
                            timeRangeFilter = instantFilter,
                            pageToken = pageToken,
                        ),
                    )
                records += response.records
                pageToken = response.pageToken?.ifEmpty { null }
            } while (pageToken != null)
            return records
        }

        suspend fun toBuckets(localFilter: TimeRangeFilter): List<OxygenSaturationAggregateBucket> {
            val records = readAllRecords(localFilter)
            if (records.isEmpty()) return emptyList()

            val rangeStart =
                localFilter.localStartTime ?: run {
                    val earliestDate = records.minOf { it.time }.atZone(zone).toLocalDate()
                    if (bucket.months != 0 || bucket.years != 0) earliestDate.withDayOfMonth(1).atStartOfDay() else earliestDate.atStartOfDay()
                }
            val rangeEnd = localFilter.localEndTime ?: LocalDateTime.now()

            val bucketStarts = mutableListOf<LocalDateTime>()
            var current = rangeStart
            while (current.isBefore(rangeEnd)) {
                bucketStarts += current
                current = current.plus(bucket)
            }
            if (bucketStarts.isEmpty()) return emptyList()

            val valuesByBucket = Array(bucketStarts.size) { mutableListOf<Double>() }
            for (record in records) {
                val localDateTime = record.time.atZone(record.zoneOffset ?: zone).toLocalDateTime()
                if (localDateTime.isBefore(rangeStart) || !localDateTime.isBefore(rangeEnd)) continue
                val bucketIndex = bucketStarts.indexOfLast { !it.isAfter(localDateTime) }
                if (bucketIndex >= 0) {
                    valuesByBucket[bucketIndex] += record.percentage.value
                }
            }

            return bucketStarts.mapIndexed { index, start ->
                val values = valuesByBucket[index]
                OxygenSaturationAggregateBucket(
                    periodStart = start,
                    average = values.takeIf { it.isNotEmpty() }?.average(),
                    min = values.minOrNull(),
                    max = values.maxOrNull(),
                )
            }
        }

        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = timeRangeFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilterLocal(),
                    read = ::toBuckets,
                )
        ) {
            is HistoryFallbackOutcome.Success ->
                OxygenSaturationAggregatesResult.Success(buckets = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> OxygenSaturationAggregatesResult.Failure
        }
    }

    // WBS 6.10（優先度Aのデータ型を順次対応）: Blood Glucoseを追加。BloodGlucoseRecordも
    // Weight/RestingHeartRate/BloodPressure/BodyFat/HRV/OxygenSaturationと同じ単一時刻のレコード
    // （InstantaneousRecord、公式の重複処理もない。requirements.md §22.2）のため、Records/Sources用の
    // 2関数はWeight用の関数をそのまま踏襲する（Body Fat/HRV/SpO2と同じ判断）。
    //
    // BloodGlucoseRecordにはlevel（BloodGlucose型）の他、specimenSource/mealType/relationToMealという
    // 3つの分類用int値もあるが、Body Fat/HRV/SpO2が値フィールド1つのみを表示してきたのと同じ方針を
    // 踏襲し、今回はlevelのみを表示する（D-050(1)）。
    //
    // ホームカードはfindLatestBloodGlucoseRecords()による「最新値＋前回比」。SpO2と同じく実装前に
    // Pixel 11実機の記録頻度を確認できればそれに基づき方式を決める想定だったが、血圧・SpO2と同様に
    // 実データが無く確認できなかったため、低頻度想定（自己測定の散発測定）に基づきこの方式のまま
    // 暫定確定し、記録頻度は未確認のまま要検証として残した（D-050、requirements.md §27）。
    fun bloodGlucoseRecordsPagingSource(historyPermissionGranted: Boolean): PagingSource<Int, PagedRecord<BloodGlucoseRecord>> {
        val now = Instant.now()
        val filter =
            if (historyPermissionGranted) {
                TimeRangeFilter.before(now)
            } else {
                TimeRangeFilter.between(now.minus(HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS), now)
            }
        return HealthRecordsPagingSource { pageToken, pageSize ->
            val response =
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = BloodGlucoseRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = false,
                        pageSize = pageSize,
                        pageToken = pageToken,
                    ),
                )
            HealthRecordsPage(records = response.records, nextPageToken = response.pageToken?.ifEmpty { null })
        }
    }

    // findOldestBodyFatRecordTime()と同じ理由・同じ形。
    suspend fun findOldestBloodGlucoseRecordTime(historyPermissionGranted: Boolean): OldestRecordResult {
        suspend fun readOldest(filter: TimeRangeFilter): Instant? =
            client
                .readRecords(
                    ReadRecordsRequest(
                        recordType = BloodGlucoseRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = true,
                        pageSize = 1,
                    ),
                ).records
                .firstOrNull()
                ?.time

        val primaryFilter = if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter()
        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = primaryFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilter(),
                    read = ::readOldest,
                )
        ) {
            is HistoryFallbackOutcome.Success -> OldestRecordResult.Success(time = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> OldestRecordResult.Failure
        }
    }

    // ホーム画面のBlood Glucoseカード用。findLatestBodyFatRecords()と同じ形・同じ理由。
    suspend fun findLatestBloodGlucoseRecords(limit: Int, historyPermissionGranted: Boolean): BloodGlucoseRecordsResult {
        suspend fun readLatest(filter: TimeRangeFilter): List<BloodGlucoseRecord> =
            client
                .readRecords(
                    ReadRecordsRequest(
                        recordType = BloodGlucoseRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = false,
                        pageSize = limit,
                    ),
                ).records

        val primaryFilter = if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter()
        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = primaryFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilter(),
                    read = ::readLatest,
                )
        ) {
            is HistoryFallbackOutcome.Success ->
                BloodGlucoseRecordsResult.Success(records = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> BloodGlucoseRecordsResult.Failure
        }
    }

    // BloodGlucoseRecordには公式のAggregateMetricが存在しない（javap逆コンパイルで確認済み、D-049）。
    // readBodyFatAggregates()と全く同じロジックをBloodGlucoseRecord・levelに差し替えただけ
    // （readHrvAggregates()・readOxygenSaturationAggregates()も同様に複製されている）。levelは
    // BloodGlucose型（CLAUDE.md参照。Energy/Pressure型と同じin接頭辞付きのプロパティ名
    // inMillimolesPerLiter/inMilligramsPerDeciliterで、javapのメソッド名getMilligramsPerDeciliter()
    // からそのまま推測したmilligramsPerDeciliterはコンパイルエラーになった。D-050(2)）。
    // ここではinMilligramsPerDeciliterを使う（単位はmg/dL）。
    //
    // コードレビュー指摘（Body Fat/HRV/SpO2実装時）: この関数はreadBodyFatAggregates()・
    // readHrvAggregates()・readOxygenSaturationAggregates()と同じ広げ幅・クランプ判定ロジックを
    // 複製している。**このロジックを変更する場合は、必ず他の3関数側の同じロジックにも同じ修正を
    // 入れること**。要点はreadBodyFatAggregates()側のコメント参照。
    suspend fun readBloodGlucoseAggregates(
        timeRangeFilter: TimeRangeFilter,
        bucket: Period,
        historyPermissionGranted: Boolean,
    ): BloodGlucoseAggregatesResult {
        val zone = ZoneId.systemDefault()
        val maxZoneOffsetSpread = Duration.ofHours(26)

        suspend fun readAllRecords(localFilter: TimeRangeFilter): List<BloodGlucoseRecord> {
            val recentFloor = Instant.now().minus(HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS)
            val unwidenedStart = localFilter.localStartTime?.atZone(zone)?.toInstant()
            val start =
                unwidenedStart?.let { s ->
                    val widened = s.minus(maxZoneOffsetSpread)
                    if (!s.isBefore(recentFloor)) maxOf(widened, recentFloor) else widened
                }
            val end = (localFilter.localEndTime?.atZone(zone)?.toInstant() ?: Instant.now()).plus(maxZoneOffsetSpread)
            val instantFilter = if (start != null) TimeRangeFilter.between(start, end) else TimeRangeFilter.before(end)

            val records = mutableListOf<BloodGlucoseRecord>()
            var pageToken: String? = null
            do {
                val response =
                    client.readRecords(
                        ReadRecordsRequest(
                            recordType = BloodGlucoseRecord::class,
                            timeRangeFilter = instantFilter,
                            pageToken = pageToken,
                        ),
                    )
                records += response.records
                pageToken = response.pageToken?.ifEmpty { null }
            } while (pageToken != null)
            return records
        }

        suspend fun toBuckets(localFilter: TimeRangeFilter): List<BloodGlucoseAggregateBucket> {
            val records = readAllRecords(localFilter)
            if (records.isEmpty()) return emptyList()

            val rangeStart =
                localFilter.localStartTime ?: run {
                    val earliestDate = records.minOf { it.time }.atZone(zone).toLocalDate()
                    if (bucket.months != 0 || bucket.years != 0) earliestDate.withDayOfMonth(1).atStartOfDay() else earliestDate.atStartOfDay()
                }
            val rangeEnd = localFilter.localEndTime ?: LocalDateTime.now()

            val bucketStarts = mutableListOf<LocalDateTime>()
            var current = rangeStart
            while (current.isBefore(rangeEnd)) {
                bucketStarts += current
                current = current.plus(bucket)
            }
            if (bucketStarts.isEmpty()) return emptyList()

            val valuesByBucket = Array(bucketStarts.size) { mutableListOf<Double>() }
            for (record in records) {
                val localDateTime = record.time.atZone(record.zoneOffset ?: zone).toLocalDateTime()
                if (localDateTime.isBefore(rangeStart) || !localDateTime.isBefore(rangeEnd)) continue
                val bucketIndex = bucketStarts.indexOfLast { !it.isAfter(localDateTime) }
                if (bucketIndex >= 0) {
                    valuesByBucket[bucketIndex] += record.level.inMilligramsPerDeciliter
                }
            }

            return bucketStarts.mapIndexed { index, start ->
                val values = valuesByBucket[index]
                BloodGlucoseAggregateBucket(
                    periodStart = start,
                    average = values.takeIf { it.isNotEmpty() }?.average(),
                    min = values.minOrNull(),
                    max = values.maxOrNull(),
                )
            }
        }

        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = timeRangeFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilterLocal(),
                    read = ::toBuckets,
                )
        ) {
            is HistoryFallbackOutcome.Success ->
                BloodGlucoseAggregatesResult.Success(buckets = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> BloodGlucoseAggregatesResult.Failure
        }
    }

    // WBS 3.1（PoC 2）: readStepsRecords()とreadStepsAggregateTotal()は、呼び出し元（StepsScreen）が
    // 期間（Today/直近7日/全期間）ごとに決めたTimeRangeFilterをそのまま受け取るだけで、
    // readWithHistoryFallback()が行うようなSecurityException時の直近30日への自動フォールバックは
    // どちらも行わない（D-029）。RawとAggregateの合計を突き合わせて公式の重複処理の結果を見ることが
    // このPoCの目的のため、両者に渡すfilterを呼び出し元で完全に一致させることを優先する。
    // 片方だけ内部でフォールバックすると、Raw側とAggregate側で実際に問い合わせた範囲がずれ、
    // 比較自体が成立しなくなるため（Weightのように単一の呼び出し結果をそのまま表示するだけの
    // 画面とは異なり、2つの呼び出し結果を比較するこの画面ではフォールバックの非対称性が
    // そのまま誤った差分として表示されてしまう）。全期間（ALL）で履歴読み取り権限がない場合の
    // 範囲は、呼び出し元がfilterを直近30日にして渡すことで対応する（StepsScreen.StepsPeriod参照）。
    //
    // 呼び出し元は「今日」「直近7日間」でも終了側を無制限（after()）にせず、同じInstant.now()を使った
    // between(start, now)で終了側も固定する。終了側を無制限にすると、Raw読み取り→全ソースAggregate→
    // ソース別Aggregate…と複数回に分けて呼ぶ間にHealth Connect側へ新しいレコードが書き込まれた場合、
    // 後続の呼び出しだけがそれを含んでしまい、公式の重複処理とは無関係な差分が生じ得る。
    private suspend fun readStepsPaged(filter: TimeRangeFilter): List<StepsRecord> {
        val records = mutableListOf<StepsRecord>()
        var pageToken: String? = null
        do {
            val response =
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = StepsRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = false,
                        pageToken = pageToken,
                    ),
                )
            records += response.records
            pageToken = response.pageToken?.ifEmpty { null }
        } while (pageToken != null)
        return records
    }

    suspend fun readStepsRecords(timeRangeFilter: TimeRangeFilter): StepsRecordsResult =
        try {
            StepsRecordsResult.Success(records = readStepsPaged(timeRangeFilter))
        } catch (e: RemoteException) {
            StepsRecordsResult.Failure
        } catch (e: IOException) {
            StepsRecordsResult.Failure
        } catch (e: SecurityException) {
            StepsRecordsResult.Failure
        }

    // WBS 6.3: 詳細画面のRecordsタブ用。weightRecordsPagingSource()と同じ考え方（入り口で一度だけ
    // filterを決め、ページング中の自動フォールバック再試行は行わない。履歴読み取り権限がない場合も
    // nowを両端に固定したbetween()にする理由はweightRecordsPagingSource()のコメント参照）。
    // readStepsRecords()（Raw/Aggregate比較PoC専用、D-029。filterの決め方を呼び出し元に委ねる設計）
    // とは別物で、poc/StepsScreenはこの関数を使わず引き続きreadStepsRecords()を使う。
    fun stepsRecordsPagingSource(historyPermissionGranted: Boolean): PagingSource<Int, PagedRecord<StepsRecord>> {
        val now = Instant.now()
        val filter =
            if (historyPermissionGranted) {
                TimeRangeFilter.before(now)
            } else {
                TimeRangeFilter.between(now.minus(HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS), now)
            }
        return HealthRecordsPagingSource { pageToken, pageSize ->
            val response =
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = StepsRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = false,
                        pageSize = pageSize,
                        pageToken = pageToken,
                    ),
                )
            HealthRecordsPage(records = response.records, nextPageToken = response.pageToken?.ifEmpty { null })
        }
    }

    // WBS 3.1（PoC 2）: RawとAggregateの差、複数Sourceの公式重複処理の結果を確認するための歩数合計。
    // dataOriginFilterを空集合で呼ぶと全ソース合算（Steps=Activity系のため公式の重複処理が適用される、
    // requirements.md §22.2）、単一のDataOriginを指定するとそのソース単体の合計になる（呼び出し元の
    // StepsScreenで、Rawの単純合計との比較・ソース単体Aggregateとの比較に使う）。
    // ただし単一ソース単体を指定した場合でも、そのソースのRaw単純合計とAggregate合計が一致するとは
    // 限らないことを実機で確認している。原因はレビューで複数の説（表示精度による誤読、ゼロ長レコード、
    // クエリ境界を一部だけまたぐレコードの扱いの違いなど）が指摘され未確定（lessons.md 6.6、要検証）。
    // aggregate()はreadRecords()と同じInstantベースのTimeRangeFilterを受け付ける（Period単位でbucket化する
    // aggregateGroupByPeriod()だけがLocalDateTimeを要求する。lessons.md 6.5）。例外の型もreadRecords()と
    // 同じ3種（RemoteException/SecurityException/IOException）と仮定している（javapでは確認できず要検証）。
    suspend fun readStepsAggregateTotal(
        timeRangeFilter: TimeRangeFilter,
        dataOriginFilter: Set<DataOrigin> = emptySet(),
    ): StepsAggregateTotalResult =
        try {
            val result =
                client.aggregate(
                    AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = timeRangeFilter,
                        dataOriginFilter = dataOriginFilter,
                    ),
                )
            StepsAggregateTotalResult.Success(total = result[StepsRecord.COUNT_TOTAL])
        } catch (e: RemoteException) {
            StepsAggregateTotalResult.Failure
        } catch (e: IOException) {
            StepsAggregateTotalResult.Failure
        } catch (e: SecurityException) {
            StepsAggregateTotalResult.Failure
        }

    // WBS 6.3: 詳細画面のRecordsタブ用。readHeartRateRecords()（全件を1つのListに溜め込む旧実装）を
    // 置き換える。Heart Rateは1レコードに複数サンプルを含み、継続記録するソースでは「全期間」の
    // Raw全件読み込みが実機のヒープを枯渇させ、Health Connect SDK内部（readRecords()のレコード変換
    // 処理）でOutOfMemoryErrorが発生することを確認した（lessons.md 6.7）。Paging3で画面に見えている
    // 分だけ保持する設計に変えたことで、サンプル数上限による打ち切り（旧HEART_RATE_RAW_SAMPLE_LIMIT・
    // HeartRateRecordsResult.LimitReached）という粗い安全弁は不要になった。
    // weightRecordsPagingSource()と同じ考え方（入り口で一度だけfilterを決め、ページング中の
    // 自動フォールバック再試行は行わない。履歴読み取り権限がない場合もnowを両端に固定した
    // between()にする理由はweightRecordsPagingSource()のコメント参照）。
    fun heartRateRecordsPagingSource(historyPermissionGranted: Boolean): PagingSource<Int, PagedRecord<HeartRateRecord>> {
        val now = Instant.now()
        val filter =
            if (historyPermissionGranted) {
                TimeRangeFilter.before(now)
            } else {
                TimeRangeFilter.between(now.minus(HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS), now)
            }
        return HealthRecordsPagingSource { pageToken, pageSize ->
            val response =
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = HeartRateRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = false,
                        pageSize = pageSize,
                        pageToken = pageToken,
                    ),
                )
            HealthRecordsPage(records = response.records, nextPageToken = response.pageToken?.ifEmpty { null })
        }
    }

    // findOldestWeightRecordTime()と同じ理由・同じ形（昇順・pageSize = 1で全件ページング不要）。
    suspend fun findOldestHeartRateRecordTime(historyPermissionGranted: Boolean): OldestRecordResult {
        suspend fun readOldest(filter: TimeRangeFilter): Instant? =
            client
                .readRecords(
                    ReadRecordsRequest(
                        recordType = HeartRateRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = true,
                        pageSize = 1,
                    ),
                ).records
                .firstOrNull()
                ?.startTime

        val primaryFilter = if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter()
        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = primaryFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilter(),
                    read = ::readOldest,
                )
        ) {
            is HistoryFallbackOutcome.Success -> OldestRecordResult.Success(time = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> OldestRecordResult.Failure
        }
    }

    // readWeightAggregates()と同じ形。metricsだけHeartRateRecord.BPM_AVG/BPM_MIN/BPM_MAX/
    // MEASUREMENTS_COUNTに差し替える（いずれもAggregateMetric<Long>、単位変換は不要）。
    suspend fun readHeartRateAggregates(
        timeRangeFilter: TimeRangeFilter,
        bucket: Period,
        historyPermissionGranted: Boolean,
    ): HeartRateAggregatesResult {
        suspend fun readAggregates(filter: TimeRangeFilter) =
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics =
                        setOf(
                            HeartRateRecord.BPM_AVG,
                            HeartRateRecord.BPM_MIN,
                            HeartRateRecord.BPM_MAX,
                            HeartRateRecord.MEASUREMENTS_COUNT,
                        ),
                    timeRangeFilter = filter,
                    timeRangeSlicer = bucket,
                ),
            )

        suspend fun toBuckets(filter: TimeRangeFilter) =
            readAggregates(filter).map { grouped ->
                HeartRateAggregateBucket(
                    periodStart = grouped.startTime,
                    averageBpm = grouped.result[HeartRateRecord.BPM_AVG],
                    minBpm = grouped.result[HeartRateRecord.BPM_MIN],
                    maxBpm = grouped.result[HeartRateRecord.BPM_MAX],
                    measurementCount = grouped.result[HeartRateRecord.MEASUREMENTS_COUNT],
                )
            }

        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = timeRangeFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilterLocal(),
                    read = ::toBuckets,
                )
        ) {
            is HistoryFallbackOutcome.Success -> HeartRateAggregatesResult.Success(buckets = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> HeartRateAggregatesResult.Failure
        }
    }

    // WBS 6.1: ホーム画面のHeart Rateカード（選択期間の平均・最小・最大、期間タブごとに変わる）用。
    // readHeartRateAggregates()（グラフ用、aggregateGroupByPeriod()でbucket分割）とは別物で、
    // readStepsAggregateTotal()と同じ形（client.aggregate()、Instantベースのfilter、bucket分割なし）。
    // 呼び出し元（ホーム画面）が期間ごとのfilterを安全な範囲に事前クランプする設計のため、
    // Steps同様historyPermissionGrantedによる内部フォールバックは持たせない（D-029と同じ考え方）。
    suspend fun readHeartRateAggregateSummary(timeRangeFilter: TimeRangeFilter): HeartRateAggregateSummaryResult =
        try {
            val result =
                client.aggregate(
                    AggregateRequest(
                        metrics = setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX),
                        timeRangeFilter = timeRangeFilter,
                    ),
                )
            HeartRateAggregateSummaryResult.Success(
                averageBpm = result[HeartRateRecord.BPM_AVG],
                minBpm = result[HeartRateRecord.BPM_MIN],
                maxBpm = result[HeartRateRecord.BPM_MAX],
            )
        } catch (e: RemoteException) {
            HeartRateAggregateSummaryResult.Failure
        } catch (e: IOException) {
            HeartRateAggregateSummaryResult.Failure
        } catch (e: SecurityException) {
            HeartRateAggregateSummaryResult.Failure
        }

    // WBS 6.4（D-036）: Heart Rateのデータソース画面用。正確なレコード件数の全件走査は
    // OutOfMemoryErrorの実例がある（lessons.md 6.7）ため行わず、Aggregateのソース別MEASUREMENTS_COUNT
    // （サンプル数、レコード数ではない）で代替する。ソース一覧は、requirements.md §22.3の見込み通り
    // dataOriginFilterを指定しない通常のAggregate呼び出しが返す`AggregationResult.dataOrigins`から
    // 取得する（全件走査よりはるかに軽量な1回のAggregate呼び出し）。
    // ソースが1件も見つからない場合（範囲内に記録なし）はdataOriginsが空集合になり、counts=emptyListを返す
    // （Failureにはしない。Recordsタブの「記録なし」と同じ「正常に0件」の扱い）。
    // 個別ソースのAggregate呼び出しが失敗した場合は、そのソースだけfailed=trueにして他のソースの
    // 表示を妨げない（poc/StepsScreen.StepsSourceRowのofficialTotal/officialTotalFailedと同じ考え方）。
    // ただしSecurityExceptionはここで握りつぶさず、呼び出し元のread()・readWithHistoryFallback()まで
    // 伝播させる（コードレビュー指摘）。ここで捕まえてfailed=trueにしてしまうと、主範囲の問い合わせ中に
    // 履歴読み取り権限が失われた場合でも直近30日へのフォールバックが起動せず、countBySource()を使う
    // Weight/Steps/Sleep（readSourceRecordCounts()）と異なり「権限喪失時は直近30日に切り替えて再取得する」
    // という既存の方針（readWithHistoryFallback()）から外れてしまう。RemoteException/IOExceptionは
    // 従来通りここで捕まえ、1ソースの一時的な失敗が他のソースの表示を道連れにしないようにする。
    //
    // D-036の採用条件（MEASUREMENTS_COUNTがRawから数えたサンプル数と実機で一致するか）は、全件走査が
    // 安全な直近7日・直近30日の範囲（主ソースでそれぞれ9,895件/253,082サンプル、41,376件/1,065,015
    // サンプル）で、一時的な診断コード（確認後に削除済み）により検証した。いずれもMEASUREMENTS_COUNTと
    // Rawから数えたサンプル数の単純合計が完全に一致することをPixel 11実機（実データ）で確認できた
    // （2026-10-01）。多年規模の全件走査自体は引き続き行わないため、この一致がより長い範囲でも
    // 成り立つかは未検証のまま残る。
    suspend fun readHeartRateSourceSampleCounts(historyPermissionGranted: Boolean): HeartRateSourceSampleCountsResult {
        suspend fun readSingleSourceSampleCount(filter: TimeRangeFilter, origin: DataOrigin): HeartRateSourceSampleCount =
            try {
                val result =
                    client.aggregate(
                        AggregateRequest(
                            metrics = setOf(HeartRateRecord.MEASUREMENTS_COUNT),
                            timeRangeFilter = filter,
                            dataOriginFilter = setOf(origin),
                        ),
                    )
                HeartRateSourceSampleCount(dataOrigin = origin, sampleCount = result[HeartRateRecord.MEASUREMENTS_COUNT], failed = false)
            } catch (e: RemoteException) {
                HeartRateSourceSampleCount(dataOrigin = origin, sampleCount = null, failed = true)
            } catch (e: IOException) {
                HeartRateSourceSampleCount(dataOrigin = origin, sampleCount = null, failed = true)
            }

        suspend fun read(filter: TimeRangeFilter): List<HeartRateSourceSampleCount> {
            val combined =
                client.aggregate(
                    AggregateRequest(metrics = setOf(HeartRateRecord.MEASUREMENTS_COUNT), timeRangeFilter = filter),
                )
            return combined.dataOrigins
                .map { origin -> readSingleSourceSampleCount(filter, origin) }
                .sortedByDescending { it.sampleCount ?: 0L }
        }

        val primaryFilter = if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter()
        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = primaryFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilter(),
                    read = ::read,
                )
        ) {
            is HistoryFallbackOutcome.Success ->
                HeartRateSourceSampleCountsResult.Success(counts = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> HeartRateSourceSampleCountsResult.Failure
        }
    }

    // WBS 5.1（PoC 4）: Sleepの公式Aggregate（SLEEP_DURATION_TOTAL）はActivity/Sleepにのみ効く公式の
    // 重複処理を経る（requirements.md §22.2）。Weight/Heart Rateと同じくreadWeightAggregates()と同じ形
    // （metricsをSLEEP_DURATION_TOTAL 1つに差し替え、結果はDuration）にする。
    //
    // 日付境界をまたぐSleep Session（例: 23:30〜翌07:15）が、aggregateGroupByPeriod()のbucket境界を
    // またいだ場合にどう扱われるかは、このJetpackクライアントのソースやKDocには記載がなく、実際の集計は
    // Health Connect本体（プラットフォーム側）が行うためjavapによる逆コンパイルでも確認できない
    // （lessons.md 6.3のような手法が使えない）。Pixel 11実機の実データ（単一ソースのみ、0時より前の
    // 時間が4分〜86.5分と幅のある6事例）で確認した結果、丸ごと1つのbucketに計上されることはなく、
    // 両日のbucketに実時間の重なりに応じて按分されることは分かったが、Health Connectが実際にどう
    // 計算しているか（Stage単位の計算か近似的な計算か）と複数ソース時の重複処理（Source priority）は
    // 未確認のまま残っている（lessons.md 6.9、D-032、requirements.md §27）。
    suspend fun readSleepAggregates(
        timeRangeFilter: TimeRangeFilter,
        bucket: Period,
        historyPermissionGranted: Boolean,
    ): SleepAggregatesResult {
        suspend fun readAggregates(filter: TimeRangeFilter) =
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL),
                    timeRangeFilter = filter,
                    timeRangeSlicer = bucket,
                ),
            )

        suspend fun toBuckets(filter: TimeRangeFilter) =
            readAggregates(filter).map { grouped ->
                SleepAggregateBucket(
                    periodStart = grouped.startTime,
                    periodEnd = grouped.endTime,
                    totalSleepDuration = grouped.result[SleepSessionRecord.SLEEP_DURATION_TOTAL],
                )
            }

        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = timeRangeFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilterLocal(),
                    read = ::toBuckets,
                )
        ) {
            is HistoryFallbackOutcome.Success -> SleepAggregatesResult.Success(buckets = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> SleepAggregatesResult.Failure
        }
    }

    // WBS 6.1: ホーム画面のSleepカード用。readHeartRateAggregateSummary()と同じ理由・同じ形
    // （client.aggregate()、Instantベースのfilter、bucket分割なし、内部フォールバックなし）。
    suspend fun readSleepAggregateSummary(timeRangeFilter: TimeRangeFilter): SleepAggregateSummaryResult =
        try {
            val result =
                client.aggregate(
                    AggregateRequest(
                        metrics = setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL),
                        timeRangeFilter = timeRangeFilter,
                    ),
                )
            SleepAggregateSummaryResult.Success(totalSleepDuration = result[SleepSessionRecord.SLEEP_DURATION_TOTAL])
        } catch (e: RemoteException) {
            SleepAggregateSummaryResult.Failure
        } catch (e: IOException) {
            SleepAggregateSummaryResult.Failure
        } catch (e: SecurityException) {
            SleepAggregateSummaryResult.Failure
        }

    // WBS 6.3: 詳細画面のRecordsタブ用。readSleepSessionRecords()（全件を1つのListに溜め込む旧実装）を
    // 置き換える。weightRecordsPagingSource()と同じ考え方（入り口で一度だけfilterを決め、ページング中の
    // 自動フォールバック再試行は行わない。履歴読み取り権限がない場合もnowを両端に固定したbetween()に
    // する理由はweightRecordsPagingSource()のコメント参照）。Sleep Sessionは1日1〜数件程度で記録頻度が
    // 低く、Heart RateのようなOOMの実例は確認されていないが、一貫性のため他3型と同じPaging3のラップにする。
    fun sleepSessionRecordsPagingSource(historyPermissionGranted: Boolean): PagingSource<Int, PagedRecord<SleepSessionRecord>> {
        val now = Instant.now()
        val filter =
            if (historyPermissionGranted) {
                TimeRangeFilter.before(now)
            } else {
                TimeRangeFilter.between(now.minus(HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS), now)
            }
        return HealthRecordsPagingSource { pageToken, pageSize ->
            val response =
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = SleepSessionRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = false,
                        pageSize = pageSize,
                        pageToken = pageToken,
                    ),
                )
            HealthRecordsPage(records = response.records, nextPageToken = response.pageToken?.ifEmpty { null })
        }
    }

    // findOldestWeightRecordTime()/findOldestHeartRateRecordTime()と同じ形。SleepSessionRecordには
    // WeightRecordの.timeのような単一時刻フィールドがないため、IntervalRecord共通のstartTimeを使う。
    suspend fun findOldestSleepSessionRecordTime(historyPermissionGranted: Boolean): OldestRecordResult {
        suspend fun readOldest(filter: TimeRangeFilter): Instant? =
            client
                .readRecords(
                    ReadRecordsRequest(
                        recordType = SleepSessionRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = true,
                        pageSize = 1,
                    ),
                ).records
                .firstOrNull()
                ?.startTime

        val primaryFilter = if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter()
        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = primaryFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilter(),
                    read = ::readOldest,
                )
        ) {
            is HistoryFallbackOutcome.Success -> OldestRecordResult.Success(time = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> OldestRecordResult.Failure
        }
    }

    // WBS 6.2: findOldestWeightRecordTime()と同じ理由・同じ形。
    suspend fun findOldestStepsRecordTime(historyPermissionGranted: Boolean): OldestRecordResult {
        suspend fun readOldest(filter: TimeRangeFilter): Instant? =
            client
                .readRecords(
                    ReadRecordsRequest(
                        recordType = StepsRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = true,
                        pageSize = 1,
                    ),
                ).records
                .firstOrNull()
                ?.startTime

        val primaryFilter = if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter()
        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = primaryFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilter(),
                    read = ::readOldest,
                )
        ) {
            is HistoryFallbackOutcome.Success -> OldestRecordResult.Success(time = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> OldestRecordResult.Failure
        }
    }

    // WBS 6.2: 詳細画面のStepsグラフ用。readWeightAggregates()と同じ形（aggregateGroupByPeriod()で
    // bucket集計、readWithHistoryFallback経由）。既存のreadStepsAggregateTotal()（D-029、Raw/Aggregate
    // 比較PoC専用、bucket分割なし・内部フォールバックなし）とは別物。COUNT_TOTALはAggregateMetric<Long>。
    suspend fun readStepsAggregates(
        timeRangeFilter: TimeRangeFilter,
        bucket: Period,
        historyPermissionGranted: Boolean,
    ): StepsAggregatesResult {
        suspend fun readAggregates(filter: TimeRangeFilter) =
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = filter,
                    timeRangeSlicer = bucket,
                ),
            )

        suspend fun toBuckets(filter: TimeRangeFilter) =
            readAggregates(filter).map { grouped ->
                StepsAggregateBucket(
                    periodStart = grouped.startTime,
                    periodEnd = grouped.endTime,
                    total = grouped.result[StepsRecord.COUNT_TOTAL],
                )
            }

        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = timeRangeFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilterLocal(),
                    read = ::toBuckets,
                )
        ) {
            is HistoryFallbackOutcome.Success -> StepsAggregatesResult.Success(buckets = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> StepsAggregatesResult.Failure
        }
    }

    // WBS 6.10: Distance。要件§22.2ではSteps同様Activity系に分類され公式重複処理が効くとされており、
    // Steps用の関数群と同じ形で実装する（PoCは行わない。requirements.md §16の優先度Aを順に追加していく
    // 段階では、同じ分類のデータ型についてまでPoCを繰り返さない）。**ただしこの分類はrequirements.md
    // §22.2の表・公式ドキュメント上の根拠であり、Steps PoC 2で確認したのはStepsRecordのAggregateが
    // 複数ソースの重複を実際に処理することのみ。DistanceRecordで同じ重複処理が実際に効いているかは
    // 実機未検証のまま（要検証、requirements.md §22.2・§27、D-042(8)でも同様に記録）**。
    // DISTANCE_TOTALはAggregateMetric<Length>のため、WeightAggregateBucketがMassをinKilogramsへ
    // 変換するのと同じ考え方でkmに変換する（Length.inKilometers/inMeters等はjavapの出力に現れる
    // JVMメソッド名getKilometers()/getMeters()とは異なり、Kotlin側から見える実際のプロパティ名。
    // KotlinのJvmName差し替えにより、javap逆コンパイルだけでは正しいプロパティ名が分からない
    // 落とし穴がある。D-012のMassでも同じ構造）。集計値（ホーム画面カード・グラフのbucket値）は
    // km、Recordsタブの個々のレコードはmに表示単位を使い分ける（D-042(2)）。

    // findOldestWeightRecordTime()と同じ理由・同じ形。
    suspend fun findOldestDistanceRecordTime(historyPermissionGranted: Boolean): OldestRecordResult {
        suspend fun readOldest(filter: TimeRangeFilter): Instant? =
            client
                .readRecords(
                    ReadRecordsRequest(
                        recordType = DistanceRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = true,
                        pageSize = 1,
                    ),
                ).records
                .firstOrNull()
                ?.startTime

        val primaryFilter = if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter()
        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = primaryFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilter(),
                    read = ::readOldest,
                )
        ) {
            is HistoryFallbackOutcome.Success -> OldestRecordResult.Success(time = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> OldestRecordResult.Failure
        }
    }

    // weightRecordsPagingSource()と同じ考え方（入り口で一度だけfilterを決め、ページング中の自動
    // フォールバック再試行は行わない。履歴読み取り権限がない場合もnowを両端に固定したbetween()に
    // する理由はweightRecordsPagingSource()のコメント参照）。
    fun distanceRecordsPagingSource(historyPermissionGranted: Boolean): PagingSource<Int, PagedRecord<DistanceRecord>> {
        val now = Instant.now()
        val filter =
            if (historyPermissionGranted) {
                TimeRangeFilter.before(now)
            } else {
                TimeRangeFilter.between(now.minus(HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS), now)
            }
        return HealthRecordsPagingSource { pageToken, pageSize ->
            val response =
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = DistanceRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = false,
                        pageSize = pageSize,
                        pageToken = pageToken,
                    ),
                )
            HealthRecordsPage(records = response.records, nextPageToken = response.pageToken?.ifEmpty { null })
        }
    }

    // readStepsAggregates()と同じ形（詳細画面のDistanceグラフ用、aggregateGroupByPeriod()でbucket
    // 集計、readWithHistoryFallback経由）。
    suspend fun readDistanceAggregates(
        timeRangeFilter: TimeRangeFilter,
        bucket: Period,
        historyPermissionGranted: Boolean,
    ): DistanceAggregatesResult {
        suspend fun readAggregates(filter: TimeRangeFilter) =
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                    timeRangeFilter = filter,
                    timeRangeSlicer = bucket,
                ),
            )

        suspend fun toBuckets(filter: TimeRangeFilter) =
            readAggregates(filter).map { grouped ->
                DistanceAggregateBucket(
                    periodStart = grouped.startTime,
                    periodEnd = grouped.endTime,
                    totalKilometers = grouped.result[DistanceRecord.DISTANCE_TOTAL]?.inKilometers,
                )
            }

        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = timeRangeFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilterLocal(),
                    read = ::toBuckets,
                )
        ) {
            is HistoryFallbackOutcome.Success -> DistanceAggregatesResult.Success(buckets = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> DistanceAggregatesResult.Failure
        }
    }

    // WBS 6.10: ホーム画面のDistanceカード用。readHeartRateAggregateSummary()/readSleepAggregateSummary()と
    // 同じ形（client.aggregate()、Instantベースのfilter、bucket分割なし、内部フォールバックなし。
    // 呼び出し元（ホーム画面）が期間ごとのfilterを安全な範囲に事前クランプする設計のため）。
    suspend fun readDistanceAggregateTotal(timeRangeFilter: TimeRangeFilter): DistanceAggregateTotalResult =
        try {
            val result =
                client.aggregate(
                    AggregateRequest(
                        metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                        timeRangeFilter = timeRangeFilter,
                    ),
                )
            DistanceAggregateTotalResult.Success(totalKilometers = result[DistanceRecord.DISTANCE_TOTAL]?.inKilometers)
        } catch (e: RemoteException) {
            DistanceAggregateTotalResult.Failure
        } catch (e: IOException) {
            DistanceAggregateTotalResult.Failure
        } catch (e: SecurityException) {
            DistanceAggregateTotalResult.Failure
        }

    // WBS 6.10（D-043）: Calories。requirements.md §22.2の「Calories」はHealth Connect上で
    // ActiveCaloriesBurnedRecord / TotalCaloriesBurnedRecordという2つの独立したRecord型に対応し
    // （§22.2備考「Total / Activeの区別あり」）、1つのRecord型の中にTotal/Activeの2フィールドがある
    // わけではない（javap逆コンパイルで確認）。この2型を1つのカード・詳細画面に統合すると、どちらの
    // 値を優先するか、あるいは両方をどう1つの数値に合成するかというアプリ独自の判断が必要になり、
    // 「重複も含めてすべて表示する、アプリ独自に判定しない」という原則（CLAUDE.md）に反する。そのため
    // Weight/Steps/Distanceと同じ「1 Record型＝1カード＝1詳細画面」の構成を踏襲し、Active Calories・
    // Total Caloriesをそれぞれ独立したデータ型として追加する（D-043(1)）。
    //
    // 両Record型ともDistanceRecordと同じ構造（IntervalRecord、値フィールドは`energy: Energy`1つのみ）
    // で、§22.2では両方とも「合計」「あり（Activity）」に分類されているため、Steps/Distance用の
    // 関数群と同じ形でそのまま実装する（PoCは行わない。D-042(1)と同じ考え方）。
    //
    // Energy型の実際のKotlinプロパティ名はinKilocalories（javap逆コンパイルが示すgetKilocalories()とは
    // 異なる。CLAUDE.md「SDK調査（逆コンパイル）で誤解しやすい点」のLength/Massと同じ落とし穴で、
    // .classバイナリの文字列直接検索で確認、D-043(3)）。表示単位はkcalに統一する（D-043(4)）。
    // Distanceのkmのように1件あたりの値が小さく潰れて見える問題（D-042(2)）は、TotalCaloriesBurnedRecord
    // の実データ（「Health」ソース、15分間隔・1件あたり約10〜60kcal）では起きないことを実機で確認済み。
    // ActiveCaloriesBurnedRecordはこの端末にデータがなく未確認のまま（要検証）。
    //
    // 権限文字列（READ_ACTIVE_CALORIES_BURNED/READ_TOTAL_CALORIES_BURNED）は、HealthPermission.
    // getReadPermission()が「android.permission.health.READ_」+内部のRECORD_TYPE_TO_PERMISSIONマップ
    // から引いた接尾辞、という組み立てロジック自体はjavap逆コンパイルで確認できたが、肝心の接尾辞の値
    // （ACTIVE_CALORIES_BURNED/TOTAL_CALORIES_BURNED）はそのマップの初期化コードがjavapのメソッド
    // 逆アセンブル出力には現れず、.classバイナリの文字列直接検索（grep -a）で確認した
    // （HealthConnectPermissions.kt参照、D-043(5)）。
    //
    // **TotalCaloriesBurnedRecord.ENERGY_TOTALは「レコードが無ければnull」という他のAggregateMetric
    // 共通の前提（readDistanceAggregateTotal()等のコメント参照）に従わない**（レビュー指摘を受けて
    // 実機確認、2026-10-01）。実際のレコードが1件も存在しない期間（2010年の1日分でAggregateを試した）
    // でもnullではなく非null値（約1,565kcal）を返した。Android 14+のプラットフォーム側でActive
    // Calories・基礎代謝（BMR）等から推計値を補っていると見られるが、根拠資料は未確認。この挙動を
    // 放置すると「データがない項目は非表示」設定（isTotalCaloriesCardHidden()）が機能せず、
    // Records/Sourcesタブには何もないのにホームのカードには値が出る、という食い違いが起きるため、
    // ホーム画面カード用のreadTotalCaloriesAggregateTotal()では、Aggregateを信用する前にhasAnyRecord()で
    // 範囲内の実レコードの有無を確認し、無ければnullとして扱う。**詳細画面のグラフ用
    // readTotalCaloriesAggregates()には、このガードを入れていない**（理由は同関数のコメント参照）。
    // そのためグラフでは、この食い違いが未解消のまま残っている。
    //
    // 一方、実際にレコードがある日は、単一ソースに絞ったAggregateとRaw合計が完全に一致することも
    // 確認した。ただしこの確認は「24時間すべてレコードで埋まっている1日」「dataOriginFilterで1ソースに
    // 絞った」という狭い条件でのものに限る。ソースを絞らない通常の呼び出し（ホーム画面・Chartが
    // 実際に使う形）や、レコードが一部だけ欠けた日（ソースが途中で止まった日等）で、推計による水増しが
    // 混ざらず一致し続けるかは確認できていない（要検証）。詳細はlessons.md 6.26、requirements.md §27参照

    // findOldestDistanceRecordTime()と同じ理由・同じ形。
    suspend fun findOldestActiveCaloriesRecordTime(historyPermissionGranted: Boolean): OldestRecordResult {
        suspend fun readOldest(filter: TimeRangeFilter): Instant? =
            client
                .readRecords(
                    ReadRecordsRequest(
                        recordType = ActiveCaloriesBurnedRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = true,
                        pageSize = 1,
                    ),
                ).records
                .firstOrNull()
                ?.startTime

        val primaryFilter = if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter()
        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = primaryFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilter(),
                    read = ::readOldest,
                )
        ) {
            is HistoryFallbackOutcome.Success -> OldestRecordResult.Success(time = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> OldestRecordResult.Failure
        }
    }

    // distanceRecordsPagingSource()と同じ考え方。
    fun activeCaloriesRecordsPagingSource(historyPermissionGranted: Boolean): PagingSource<Int, PagedRecord<ActiveCaloriesBurnedRecord>> {
        val now = Instant.now()
        val filter =
            if (historyPermissionGranted) {
                TimeRangeFilter.before(now)
            } else {
                TimeRangeFilter.between(now.minus(HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS), now)
            }
        return HealthRecordsPagingSource { pageToken, pageSize ->
            val response =
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = ActiveCaloriesBurnedRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = false,
                        pageSize = pageSize,
                        pageToken = pageToken,
                    ),
                )
            HealthRecordsPage(records = response.records, nextPageToken = response.pageToken?.ifEmpty { null })
        }
    }

    // readDistanceAggregates()と同じ形（詳細画面のActive Caloriesグラフ用）。
    suspend fun readActiveCaloriesAggregates(
        timeRangeFilter: TimeRangeFilter,
        bucket: Period,
        historyPermissionGranted: Boolean,
    ): ActiveCaloriesAggregatesResult {
        suspend fun readAggregates(filter: TimeRangeFilter) =
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                    timeRangeFilter = filter,
                    timeRangeSlicer = bucket,
                ),
            )

        suspend fun toBuckets(filter: TimeRangeFilter) =
            readAggregates(filter).map { grouped ->
                ActiveCaloriesAggregateBucket(
                    periodStart = grouped.startTime,
                    periodEnd = grouped.endTime,
                    totalKilocalories = grouped.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
                )
            }

        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = timeRangeFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilterLocal(),
                    read = ::toBuckets,
                )
        ) {
            is HistoryFallbackOutcome.Success ->
                ActiveCaloriesAggregatesResult.Success(buckets = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> ActiveCaloriesAggregatesResult.Failure
        }
    }

    // readDistanceAggregateTotal()と同じ形（ホーム画面のActive Caloriesカード用）。
    suspend fun readActiveCaloriesAggregateTotal(timeRangeFilter: TimeRangeFilter): ActiveCaloriesAggregateTotalResult =
        try {
            val result =
                client.aggregate(
                    AggregateRequest(
                        metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                        timeRangeFilter = timeRangeFilter,
                    ),
                )
            ActiveCaloriesAggregateTotalResult.Success(
                totalKilocalories = result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
            )
        } catch (e: RemoteException) {
            ActiveCaloriesAggregateTotalResult.Failure
        } catch (e: IOException) {
            ActiveCaloriesAggregateTotalResult.Failure
        } catch (e: SecurityException) {
            ActiveCaloriesAggregateTotalResult.Failure
        }

    // findOldestDistanceRecordTime()と同じ理由・同じ形。
    suspend fun findOldestTotalCaloriesRecordTime(historyPermissionGranted: Boolean): OldestRecordResult {
        suspend fun readOldest(filter: TimeRangeFilter): Instant? =
            client
                .readRecords(
                    ReadRecordsRequest(
                        recordType = TotalCaloriesBurnedRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = true,
                        pageSize = 1,
                    ),
                ).records
                .firstOrNull()
                ?.startTime

        val primaryFilter = if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter()
        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = primaryFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilter(),
                    read = ::readOldest,
                )
        ) {
            is HistoryFallbackOutcome.Success -> OldestRecordResult.Success(time = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> OldestRecordResult.Failure
        }
    }

    // distanceRecordsPagingSource()と同じ考え方。
    fun totalCaloriesRecordsPagingSource(historyPermissionGranted: Boolean): PagingSource<Int, PagedRecord<TotalCaloriesBurnedRecord>> {
        val now = Instant.now()
        val filter =
            if (historyPermissionGranted) {
                TimeRangeFilter.before(now)
            } else {
                TimeRangeFilter.between(now.minus(HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS), now)
            }
        return HealthRecordsPagingSource { pageToken, pageSize ->
            val response =
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = TotalCaloriesBurnedRecord::class,
                        timeRangeFilter = filter,
                        ascendingOrder = false,
                        pageSize = pageSize,
                        pageToken = pageToken,
                    ),
                )
            HealthRecordsPage(records = response.records, nextPageToken = response.pageToken?.ifEmpty { null })
        }
    }

    // readDistanceAggregates()と同じ形（詳細画面のTotal Caloriesグラフ用）。readTotalCaloriesAggregateTotal()
    // と異なり、bucketごとのhasAnyRecord()チェックは**意図的に入れていない**。
    //
    // 実機で計測したところ、ALL期間（MONTH bucket、43個）のaggregateGroupByPeriod()自体が単独で約77秒
    // かかった（System.currentTimeMillis()で計測、一時的な診断コードは確認後に削除済み）。これはHeart
    // Rateの既知の遅さ（ALL期間96秒、lessons.md 6.12）に匹敵し、bucket数ではなく問い合わせ範囲の実
    // データ量が主要因と見られる点もHeart Rateと同じ（約17万件・2ソース・3.5年分）。
    //
    // **このbucketごとのガードを実際に追加して試したところ合計約45秒、外した状態（上記）が約77秒と、
    // 数字の上ではガードを入れたほうが速いという結果になった**。これはガードの効果を示すものではなく、
    // 2回の計測の間で実行環境（端末の負荷・Health Connect側の状態等）が変わったことによる誤差と見られ、
    // 「並行化しても悪化するだけ」という当初の判断根拠は誤りだった（レビュー指摘）。実際のところ、
    // ガードを追加することの所要時間への影響は、計測が安定しないため確認できていない。それでも追加を
    // 見送ったのは、素の状態で既に77秒という「致命的に遅い」操作に、bucket数分のIPC呼び出しを足す
    // ことが改善につながる見込みが薄く、かつ正確な効果を計測で確認できない以上、確証のないまま追加する
    // べきではないと判断したため（要検証、lessons.md 6.26）。
    //
    // 影響範囲: このグラフのALL期間は、すでにfindOldestTotalCaloriesRecordTime()で求めた最古レコード
    // 時刻を開始点にしている（resolveDetailGraphRange()参照）ため、記録を始める前の期間がbucketとして
    // 現れることはない。起こり得るのは、最古レコード〜現在の間で記録が途切れた日・週・月のbucketが、
    // 実際には記録がないにもかかわらず値を描画してしまうことに限られる。そのため、記録が1件もない
    // bucketでも（readTotalCaloriesAggregateTotal()と異なり）Aggregateの値をそのまま使う。
    suspend fun readTotalCaloriesAggregates(
        timeRangeFilter: TimeRangeFilter,
        bucket: Period,
        historyPermissionGranted: Boolean,
    ): TotalCaloriesAggregatesResult {
        suspend fun readAggregates(filter: TimeRangeFilter) =
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                    timeRangeFilter = filter,
                    timeRangeSlicer = bucket,
                ),
            )

        suspend fun toBuckets(filter: TimeRangeFilter) =
            readAggregates(filter).map { grouped ->
                TotalCaloriesAggregateBucket(
                    periodStart = grouped.startTime,
                    periodEnd = grouped.endTime,
                    totalKilocalories = grouped.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories,
                )
            }

        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = timeRangeFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilterLocal(),
                    read = ::toBuckets,
                )
        ) {
            is HistoryFallbackOutcome.Success ->
                TotalCaloriesAggregatesResult.Success(buckets = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> TotalCaloriesAggregatesResult.Failure
        }
    }

    // readDistanceAggregateTotal()と同じ形（ホーム画面のTotal Caloriesカード用）。hasAnyRecord()による
    // 実レコード有無の確認を挟む理由はhasAnyRecord()のコメント・lessons.md 6.26参照。
    suspend fun readTotalCaloriesAggregateTotal(timeRangeFilter: TimeRangeFilter): TotalCaloriesAggregateTotalResult =
        try {
            if (!hasAnyRecord(TotalCaloriesBurnedRecord::class, timeRangeFilter)) {
                TotalCaloriesAggregateTotalResult.Success(totalKilocalories = null)
            } else {
                val result =
                    client.aggregate(
                        AggregateRequest(
                            metrics = setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                            timeRangeFilter = timeRangeFilter,
                        ),
                    )
                TotalCaloriesAggregateTotalResult.Success(totalKilocalories = result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories)
            }
        } catch (e: RemoteException) {
            TotalCaloriesAggregateTotalResult.Failure
        } catch (e: IOException) {
            TotalCaloriesAggregateTotalResult.Failure
        } catch (e: SecurityException) {
            TotalCaloriesAggregateTotalResult.Failure
        }

    // WBS 6.4（WBS 6.10でDistance・Active Calories・Total Calories、Resting Heart Rateも追加）:
    // 詳細画面のSourcesタブ用（Weight/Steps/Sleep/Distance/Calories/Resting Heart Rate共通。
    // recordType以外の処理が完全に同一のため、
    // データ型ごとの関数に分けず1つにまとめた。OldestRecordResultを1つに統合した方針（D-034(8)）と
    // 同じ考え方）。ソース別のレコード件数はAggregateでは取れない（requirements.md §7.2/§22.3）ため、
    // 全件走査して正確に数える。
    //
    // **この基準は「レコード件数の多寡」ではない**（コードレビュー指摘。Stepsは全期間で数十万件規模に
    // なり得る＝Weight/Sleepより2桁近く多い）。実際の基準は「1レコードが大きなサンプル配列を持たず、
    // Health Connect SDK内部の変換コストが低いか」で、Heart Rateだけがこれに該当しない（1レコードに
    // 多数のサンプルを含み、継続記録ソースでは全件走査がOutOfMemoryErrorを起こす実例がある。
    // lessons.md 6.7）。DistanceRecord/ActiveCaloriesBurnedRecord/TotalCaloriesBurnedRecordもStepsと
    // 同じ単純な区間+数値1個の構造で該当しないと判断した。Pixel 11実機（実データ、Fit 212,095件・
    // Health 20,202件の計約23万件規模）で実際に全件走査してもクラッシュ・メモリ増大が起きないことを
    // 確認済み（WBS 6.10、D-042）。TotalCaloriesBurnedRecordについても、Pixel 11実機の実データ
    // （Health 90,722件・Fit 81,738件の計約17万件規模）で同様にクラッシュ・メモリ増大が起きないことを
    // 確認済み（WBS 6.10、D-043）。一方ActiveCaloriesBurnedRecordはこの端末にデータを書き込むソースが
    // なく、構造が同じという理由による判断のまま、全件走査自体は未確認（要検証）。Stepsの全期間全件
    // 走査もWBS 3.1・WBS 6.4（lessons.md 6.19）でクラッシュ・メモリ増大なしを確認済み。
    // RestingHeartRateRecordはWeightと同じ単一時刻・単一値（サンプル配列を持たない）の構造のため
    // 同じ基準に該当する。Pixel 11実機（実データ）での全件走査の確認結果はWBS 6.10参照。
    //
    // findOldestWeightRecordTime()と同じreadWithHistoryFallback()を使う一回限りの問い合わせで、
    // Recordsタブのページングのような永続的なtokenを扱わないため、SecurityException時の
    // 自動フォールバック再試行をそのまま使える（D-035(1)がRecordsタブで避けている設計とは別物）。
    suspend fun <T : Record> readSourceRecordCounts(recordType: KClass<T>, historyPermissionGranted: Boolean): SourceRecordCountsResult {
        suspend fun read(filter: TimeRangeFilter): List<SourceRecordCount> =
            countBySource(recordType, filter)
                .map { (origin, count) -> SourceRecordCount(dataOrigin = origin, count = count) }
                .sortedByDescending { it.count }

        val primaryFilter = if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter()
        return when (
            val outcome =
                readWithHistoryFallback(
                    primaryFilter = primaryFilter,
                    primaryHistoryLimited = !historyPermissionGranted,
                    fallbackFilter = recentRangeFilter(),
                    read = ::read,
                )
        ) {
            is HistoryFallbackOutcome.Success -> SourceRecordCountsResult.Success(counts = outcome.value, historyLimited = outcome.historyLimited)
            HistoryFallbackOutcome.Failure -> SourceRecordCountsResult.Failure
        }
    }

    // readSourceRecordCounts()が使う全件走査カウント。D-031(3)で候補に挙がっていた「件数だけを集計し、
    // レコード自体は保持しない走査」（Raw一覧のページング表示化後の代替案）をここで実装する。
    // Map<DataOrigin, Long>（ソース数程度の小さいサイズ）だけを保持し、ページごとのレコードリストは
    // カウントに使ったらその場で破棄する（Stepsのような数十万件規模でも安全なことをlessons.md 6.19で確認済み）。
    // レビュー指摘（2026-10-01、lessons.md 6.26）: TotalCaloriesBurnedRecord.ENERGY_TOTALは、
    // 問い合わせ範囲に実レコードが1件もなくても非null値（推計値と見られる）を返すことがある。
    // readRecords(pageSize = 1)でその範囲に実レコードが1件でも存在するかを安価に確認し、無ければ
    // Aggregateの値を信用せずnullとして扱う（readTotalCaloriesAggregateTotal()参照）。推計がどんな
    // 条件で発生するかは非公開のプラットフォーム実装のため分からないが、このチェック自体は「実レコード
    // の有無」という事実に基づくため、発生条件が分からなくても正しく働く。ActiveCaloriesBurnedRecordは
    // この端末にデータがなく、同じ推計が起きるかどうかを確かめたこと自体がない（未確認。2010年等
    // レコードのない期間でACTIVE_CALORIES_TOTALのAggregateを試せば、この端末でも確認できる）。
    // 未確認のため、根拠のないガードは足さない（ActiveCaloriesAggregateTotalResult.kt参照）。
    //
    // **bucketごとのAggregate（readTotalCaloriesAggregates()、詳細画面のChart用）には、このガードを
    // 意図的に適用していない**。詳しい経緯・判断理由はreadTotalCaloriesAggregates()のコメント参照。
    private suspend fun <T : Record> hasAnyRecord(recordType: KClass<T>, filter: TimeRangeFilter): Boolean =
        client.readRecords(ReadRecordsRequest(recordType = recordType, timeRangeFilter = filter, pageSize = 1)).records.isNotEmpty()

    private suspend fun <T : Record> countBySource(recordType: KClass<T>, filter: TimeRangeFilter): Map<DataOrigin, Long> {
        val counts = mutableMapOf<DataOrigin, Long>()
        var pageToken: String? = null
        do {
            val response =
                client.readRecords(
                    ReadRecordsRequest(recordType = recordType, timeRangeFilter = filter, pageToken = pageToken),
                )
            for (record in response.records) {
                val origin = record.metadata.dataOrigin
                counts[origin] = (counts[origin] ?: 0L) + 1L
            }
            pageToken = response.pageToken?.ifEmpty { null }
        } while (pageToken != null)
        return counts
    }

    // findOldestWeightRecordTime() / readWeightAggregates() / findOldestHeartRateRecordTime() /
    // readHeartRateAggregates() / findOldestSleepSessionRecordTime() / readSleepAggregates()に共通する構造
    // （PoC 3でHeart Rate用の2関数を追加した際、Weight用の既存2関数と合わせて同じtry/catchの入れ子が
    // 4箇所に重複したため、レビューを受けて共通化した。PoC 4のSleep用2関数も同じ形にそのまま乗せている）。
    // 主範囲（primaryFilter）をまず試し、SecurityExceptionなら直近30日（fallbackFilter）で1回だけ
    // 再試行する。再試行後もRemoteException/IOException/SecurityExceptionのいずれかで失敗した場合、
    // また主範囲がSecurityException以外（RemoteException/IOException）で失敗した場合はFailureにする。
    // フォールバック先のfilterの決め方（Instant.now()基準かLocalDateTime基準か）・結果の詰め替え方
    // （どのsealed interfaceに包むか）は関数ごとに異なるため、そこは各呼び出し元に残している。
    //
    // **ここでRemoteException/IOException/SecurityException以外を捕まえない（＝レート制限らしき例外を
    // 握りつぶさず表に出す）のは意図的**（D-035(7)と同じ方針）。connect-client 1.1.0の
    // `ExceptionConverterKt.toKtException()`をbytecodeで確認したところ、プラットフォーム側の
    // `HealthConnectException.errorCode`はSECURITY→SecurityException、IO→IOException、
    // REMOTE→RemoteException、INVALID_ARGUMENT→IllegalArgumentExceptionに変換される一方、
    // **RATE_LIMIT_EXCEEDEDを含むそれ以外のerrorCode（UNKNOWN/INTERNAL/DATA_SYNC_IN_PROGRESS/
    // UNSUPPORTED_OPERATION）はすべてIllegalStateExceptionに変換される**（コードレビュー指摘を受けて
    // 確認。非公開の実装詳細の逆コンパイルのため将来のバージョンで変わり得る）。つまりレート制限が
    // 実際に発生した場合、ここではIllegalStateExceptionとして未捕捉のまま呼び出し元（LaunchedEffect）
    // まで伝播し、アプリがクラッシュする形で表面化する。「RemoteException/IOException/SecurityException
    // 以外の例外が出なかった」という従来の確認基準は、レート制限がRemoteException化されて汎用の
    // Failure表示に隠れる可能性を考慮していなかったが、実際にはそうならず、見える形で（クラッシュとして）
    // 検知できる設計になっている。ただしIllegalStateExceptionはレート制限以外の要因（一時的な内部エラー等）
    // でも起こり得るため、「クラッシュしなかった」こと自体もレート制限が存在しないことの確定的な証明には
    // ならない（lessons.md 6.20参照）。
    private suspend fun <T> readWithHistoryFallback(
        primaryFilter: TimeRangeFilter,
        primaryHistoryLimited: Boolean,
        fallbackFilter: TimeRangeFilter,
        read: suspend (TimeRangeFilter) -> T,
    ): HistoryFallbackOutcome<T> =
        try {
            HistoryFallbackOutcome.Success(value = read(primaryFilter), historyLimited = primaryHistoryLimited)
        } catch (e: SecurityException) {
            try {
                HistoryFallbackOutcome.Success(value = read(fallbackFilter), historyLimited = true)
            } catch (e: RemoteException) {
                HistoryFallbackOutcome.Failure
            } catch (e: IOException) {
                HistoryFallbackOutcome.Failure
            } catch (e: SecurityException) {
                HistoryFallbackOutcome.Failure
            }
        } catch (e: RemoteException) {
            HistoryFallbackOutcome.Failure
        } catch (e: IOException) {
            HistoryFallbackOutcome.Failure
        }

    // 履歴読み取り権限がない状態で読める範囲の近似（6.1参照）。findOldestWeightRecordTime()等の
    // find*系関数（1回限りの問い合わせで、フォールバック先として再読み直しを気にしなくてよい）が使う
    // Instantベースの終了側無制限TimeRangeFilter。weightRecordsPagingSource()等の*RecordsPagingSource()は
    // 同じ終了側無制限のままだとページング中の新規書き込みでtoken境界がずれるため、この関数を使わず
    // 自前でnowを両端に固定したbetween()を組み立てる（レビュー指摘、weightRecordsPagingSource()の
    // コメント参照）。Steps側（StepsScreen）も同じ理由（Raw読み取りとAggregate呼び出しの範囲を完全に
    // 一致させる必要がある）でこの関数を使わず、同じHISTORY_FALLBACK_DAYS定数を使って呼び出し元で
    // 独自にfilterを組み立てる（D-029）。
    private fun recentRangeFilter(): TimeRangeFilter =
        TimeRangeFilter.after(Instant.now().minus(HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS))

    // readWeightAggregates()用のLocalDateTimeベース版（同関数のコメント参照）。開始時刻を単純に
    // 日初へ切り捨てる（floor）と、許可される範囲（Instant.now()から30日前）よりわずかに古くなり、
    // このフォールバック経路でも再びSecurityExceptionになり得る（レビュー指摘）。安全側に倒し、
    // 翌日の0時（切り上げ、ceiling）にする。bucket境界を暦日に揃えるための代償として、実際に
    // 読める範囲より最大1日分狭くなるが、lessons.md 6.1の既存の安全側の考え方と整合する。
    private fun recentRangeFilterLocal(): TimeRangeFilter =
        TimeRangeFilter.after(
            LocalDateTime.now().minus(HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS).toLocalDate().plusDays(1).atStartOfDay(),
        )
}
