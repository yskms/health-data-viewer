package com.yskms.healthdataviewer.healthconnect

import android.content.Context
import android.os.RemoteException
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.paging.PagingSource
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.Period
import java.time.temporal.ChronoUnit

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
    }

    private val appContext = context.applicationContext

    val availability: HealthConnectAvailability
        get() =
            when (HealthConnectClient.getSdkStatus(appContext)) {
                HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.INSTALLED
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                    HealthConnectAvailability.UPDATE_REQUIRED
                else -> HealthConnectAvailability.NOT_INSTALLED
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

    // findOldestWeightRecordTime() / readWeightAggregates() / findOldestHeartRateRecordTime() /
    // readHeartRateAggregates() / findOldestSleepSessionRecordTime() / readSleepAggregates()に共通する構造
    // （PoC 3でHeart Rate用の2関数を追加した際、Weight用の既存2関数と合わせて同じtry/catchの入れ子が
    // 4箇所に重複したため、レビューを受けて共通化した。PoC 4のSleep用2関数も同じ形にそのまま乗せている）。
    // 主範囲（primaryFilter）をまず試し、SecurityExceptionなら直近30日（fallbackFilter）で1回だけ
    // 再試行する。再試行後もRemoteException/IOException/SecurityExceptionのいずれかで失敗した場合、
    // また主範囲がSecurityException以外（RemoteException/IOException）で失敗した場合はFailureにする。
    // フォールバック先のfilterの決め方（Instant.now()基準かLocalDateTime基準か）・結果の詰め替え方
    // （どのsealed interfaceに包むか）は関数ごとに異なるため、そこは各呼び出し元に残している。
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

    // 履歴読み取り権限がない状態で読める範囲の近似（6.1参照）。weightRecordsPagingSource() /
    // findOldestWeightRecordTime()で使うInstantベースのTimeRangeFilter。Steps側（StepsScreen）は
    // Raw読み取りとAggregate呼び出しの範囲を完全に一致させる必要があるため、この関数は使わず
    // 同じHISTORY_FALLBACK_DAYS定数を使って呼び出し元で独自にfilterを組み立てる（D-029）。
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
