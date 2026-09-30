package com.yskms.healthdataviewer.healthconnect

import android.content.Context
import android.os.RemoteException
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.Period
import java.time.temporal.ChronoUnit

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

    // readRecords()のKDocが明記する例外はRemoteException / SecurityException / IOExceptionの3つ
    // （getGrantedPermissions()のIllegalStateExceptionとは異なるので混同しないこと）。
    // 履歴読み取り権限がない状態で30日より古いレコードを読もうとするとSecurityExceptionになる
    // （0件が返るのではなくエラーになる。lessons.md 6.1）。呼び出し元から渡されたhistoryPermissionGrantedを
    // 信頼して初回の範囲を決めるが、取得中に権限が取り消された場合に備えてSecurityExceptionも
    // フォールバックとして処理する。
    //
    // 公式ドキュメント上、履歴権限なしで読める範囲は「権限を最初に許可した時点から30日前」までで、
    // 「現在から30日前」ではない。アプリからは最初の許可日時を直接取得する手段がないため、
    // 安全側（常に許可される範囲のみを要求し、SecurityExceptionを起こさない）に倒して
    // Instant.now().minus(30日)を使う。時間が経つほど実際に読める範囲より狭く見せる可能性があるが、
    // エラーにはならない（lessons.md 6.1）。
    //
    // recordType = WeightRecord::classの形式でReadRecordsRequestを構築すると、deduplicateStrategyは
    // 指定しなくてもDISABLED（重複排除なし）になる（D-007と整合。CLAUDE.md・lessons.md 6.3参照）。
    suspend fun readAllWeightRecords(historyPermissionGranted: Boolean): WeightRecordsResult {
        val initialFilter = if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter()

        suspend fun readPaged(filter: TimeRangeFilter): List<WeightRecord> {
            val records = mutableListOf<WeightRecord>()
            var pageToken: String? = null
            do {
                val response =
                    client.readRecords(
                        ReadRecordsRequest(
                            recordType = WeightRecord::class,
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

        suspend fun readSafely(filter: TimeRangeFilter, historyLimited: Boolean): WeightRecordsResult =
            try {
                WeightRecordsResult.Success(records = readPaged(filter), historyLimited = historyLimited)
            } catch (e: RemoteException) {
                WeightRecordsResult.Failure
            } catch (e: IOException) {
                WeightRecordsResult.Failure
            } catch (e: SecurityException) {
                WeightRecordsResult.Failure
            }

        return try {
            WeightRecordsResult.Success(
                records = readPaged(initialFilter),
                historyLimited = !historyPermissionGranted,
            )
        } catch (e: SecurityException) {
            readSafely(recentRangeFilter(), historyLimited = true)
        } catch (e: RemoteException) {
            WeightRecordsResult.Failure
        } catch (e: IOException) {
            WeightRecordsResult.Failure
        }
    }

    // WBS 2.3（PoC 1）: ALLの開始日（最古のレコード時刻）を、全件ページングせずに特定する。
    // requirements.md §22.4の見込み通り、昇順（ascendingOrder = true）・pageSize = 1で呼べば
    // 最古のレコード1件だけを1回のIPCで取得できる（readAllWeightRecords()のような全件ページングは不要）。
    // 例外・フォールバックの方針はreadAllWeightRecords()と揃える（6.1参照）。
    suspend fun findOldestWeightRecordTime(historyPermissionGranted: Boolean): OldestWeightRecordResult {
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

        return try {
            OldestWeightRecordResult.Success(
                time = readOldest(if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter()),
                historyLimited = !historyPermissionGranted,
            )
        } catch (e: SecurityException) {
            // フォールバック先（直近30日）も、取得中に体重の読み取り権限自体が取り消されていた場合などに
            // 再びSecurityExceptionになり得る（レビュー指摘）。readAllWeightRecords()のreadSafely()と
            // 同様にここでも捕捉し、クラッシュではなくFailureとして扱う。
            try {
                OldestWeightRecordResult.Success(time = readOldest(recentRangeFilter()), historyLimited = true)
            } catch (e: RemoteException) {
                OldestWeightRecordResult.Failure
            } catch (e: IOException) {
                OldestWeightRecordResult.Failure
            } catch (e: SecurityException) {
                OldestWeightRecordResult.Failure
            }
        } catch (e: RemoteException) {
            OldestWeightRecordResult.Failure
        } catch (e: IOException) {
            OldestWeightRecordResult.Failure
        }
    }

    // WBS 2.2（PoC 1）: 同日複数レコードのグラフ上の扱い。
    // D-027の通り、アプリ独自に平均／最新値を選ぶのではなく、Health Connect公式のAggregate Metric
    // （WEIGHT_AVG / WEIGHT_MIN / WEIGHT_MAX）をaggregateGroupByPeriod()でbucket集計して使う。
    // aggregateGroupByPeriod()はLocalDateTimeでbucket境界を扱う（Instant/Durationベースの
    // aggregateGroupByDuration()との違い）が、bucketの区切りは渡したtimeRangeFilterの開始時刻を
    // 起点にPeriod単位で機械的に等間隔に区切るだけで、暦日・暦月の境界に自動的には合わせてくれない。
    // 暦日・暦月区切りにするには、呼び出し元が開始時刻を日初／月初に切り捨てて渡す必要がある
    // （呼び出し元のWeightGraphScreen.GraphPeriod.timeRangeFilter()で対応。レビュー指摘、要検証）。
    // これが正しく機能すれば「タイムゾーン変更・夏時間の扱い」（requirements.md §10）にも
    // 対応しやすくなる見込みだが、実データでの確認はまだできていない。
    //
    // timeRangeFilterはLocalDateTimeベース（TimeRangeFilter.before/after(LocalDateTime)）で渡すこと。
    // Instantベースのfilterを渡すとIllegalArgumentException（"Either use TimeRangeFilter with
    // LocalDateTime or AggregateGroupByDurationRequest"）になる（エミュレータで確認、lessons.md 6.5。
    // Pixel 11実機では未確認）。readAllWeightRecords() / findOldestWeightRecordTime()が使う
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

        return try {
            WeightAggregatesResult.Success(
                buckets = toBuckets(timeRangeFilter),
                historyLimited = !historyPermissionGranted,
            )
        } catch (e: SecurityException) {
            // フォールバック先（直近30日）でも再びSecurityExceptionになり得る点はfindOldestWeightRecordTime()
            // と同じ（レビュー指摘）。加えてここは`recentRangeFilterLocal()`（LocalDateTimeベース）を使うため、
            // Health ConnectがLocalDateTimeの範囲をレコードごとのタイムゾーンで解釈する場合、実際の範囲が
            // `Instant.now()`基準の30日よりわずかに古い側にずれ、フォールバックでも例外になる可能性が
            // 理論上ある（未検証）。どちらのケースも例外を捕捉してFailureにすることで、クラッシュは避ける。
            try {
                WeightAggregatesResult.Success(buckets = toBuckets(recentRangeFilterLocal()), historyLimited = true)
            } catch (e: RemoteException) {
                WeightAggregatesResult.Failure
            } catch (e: IOException) {
                WeightAggregatesResult.Failure
            } catch (e: SecurityException) {
                WeightAggregatesResult.Failure
            }
        } catch (e: RemoteException) {
            WeightAggregatesResult.Failure
        } catch (e: IOException) {
            WeightAggregatesResult.Failure
        }
    }

    // WBS 3.1（PoC 2）: readStepsRecords()とreadStepsAggregateTotal()は、呼び出し元（StepsScreen）が
    // 期間（Today/直近7日/全期間）ごとに決めたTimeRangeFilterをそのまま受け取るだけで、Weightの
    // readAllWeightRecords()のようなSecurityException時の直近30日への自動フォールバックは
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

    // 履歴読み取り権限がない状態で読める範囲の近似（6.1参照）。readAllWeightRecords() /
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
