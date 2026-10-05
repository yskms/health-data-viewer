package com.yskms.healthdataviewer.screen.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.collectAsLazyPagingItems
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.yskms.healthdataviewer.R
import com.yskms.healthdataviewer.healthconnect.DataOriginNameResolver
import com.yskms.healthdataviewer.healthconnect.ExerciseAggregateBucket
import com.yskms.healthdataviewer.healthconnect.ExerciseAggregatesResult
import com.yskms.healthdataviewer.healthconnect.HealthConnectManager
import com.yskms.healthdataviewer.healthconnect.OldestRecordResult
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// WBS 6.10（Exercise追加、D-051）: SleepDetailScreenと同じ形（ExerciseSessionRecordもSleepSessionRecordと
// 同じIntervalRecordで公式AggregateMetric<Duration>のEXERCISE_DURATION_TOTALを持つ、
// HealthConnectManager.readExerciseAggregates()のコメント参照）。MetricDensityはSleepと同じLOW
// （運動セッションは1日0〜数件程度で記録頻度が低いという一般的な想定。このアプリの実データでは未確認、
// 要検証）。
private val EXERCISE_DENSITY = MetricDensity.LOW

private const val EXERCISE_RECORDS_PAGE_SIZE = 50
private const val EXERCISE_RECORDS_MAX_SIZE = 200

// denominatorFloorはbucketの日数按分クランプに使う（SleepDetailScreen.SleepLoadと同じ理由）。
private data class ExerciseLoad(val result: ExerciseAggregatesResult, val granularity: BucketGranularity, val denominatorFloor: LocalDateTime?)

@Composable
fun ExerciseDetailScreen(
    healthConnectManager: HealthConnectManager,
    historyPermissionGranted: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(DetailTab.CHART) }
    var period by rememberSaveable { mutableStateOf(GraphPeriod.MONTH) }
    var retryKey by remember { mutableIntStateOf(0) }
    var customRange by rememberSaveable { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    var aggregatesLoad by remember { mutableStateOf<PeriodTaggedResult<ExerciseLoad>?>(null) }
    var oldestResult by remember { mutableStateOf<OldestRecordResult?>(null) }
    // WBS 6.4: Sourcesタブは全件走査1回分のIPCを発行するため、タブ切替のたびに再実行しない
    // （rememberLazyTabResult()参照）。
    var sourcesRetryKey by remember { mutableIntStateOf(0) }
    val sourcesLoad =
        rememberLazyTabResult(active = tab == DetailTab.SOURCES, retryKey = sourcesRetryKey) {
            healthConnectManager.readSourceRecordCounts(ExerciseSessionRecord::class, historyPermissionGranted)
        }

    LaunchedEffect(retryKey) {
        oldestResult = null
        oldestResult = healthConnectManager.findOldestExerciseSessionRecordTime(historyPermissionGranted)
    }

    val oldestGateForAll = if (period == GraphPeriod.ALL) oldestResult else null

    LaunchedEffect(period, retryKey, oldestGateForAll, customRange) {
        if (period == GraphPeriod.ALL) {
            when (val currentOldest = oldestGateForAll) {
                null -> {
                    aggregatesLoad = PeriodTaggedResult(period = period, result = null)
                    return@LaunchedEffect
                }
                is OldestRecordResult.Success ->
                    if (currentOldest.time == null) {
                        aggregatesLoad =
                            PeriodTaggedResult(
                                period = period,
                                result =
                                    ExerciseLoad(
                                        result = ExerciseAggregatesResult.Success(buckets = emptyList(), historyLimited = !historyPermissionGranted),
                                        granularity = BucketGranularity.MONTH,
                                        denominatorFloor = null,
                                    ),
                            )
                        return@LaunchedEffect
                    }
                OldestRecordResult.Failure -> Unit
            }
        }
        val oldestStart =
            (oldestGateForAll as? OldestRecordResult.Success)?.time
                ?.let { LocalDateTime.ofInstant(it, ZoneId.systemDefault()) }
        val range =
            resolveDetailGraphRange(
                period = period,
                density = EXERCISE_DENSITY,
                now = LocalDateTime.now(),
                oldestStart = oldestStart,
                historyPermissionGranted = historyPermissionGranted,
                customRange = customRange,
            )
        when (range) {
            DetailGraphRange.Pending -> aggregatesLoad = PeriodTaggedResult(period = period, result = null)
            is DetailGraphRange.Empty ->
                aggregatesLoad =
                    PeriodTaggedResult(
                        period = period,
                        result =
                            ExerciseLoad(
                                result = ExerciseAggregatesResult.Success(buckets = emptyList(), historyLimited = range.historyLimited),
                                granularity = BucketGranularity.DAY,
                                denominatorFloor = null,
                            ),
                    )
            is DetailGraphRange.Resolved -> {
                aggregatesLoad = PeriodTaggedResult(period = period, result = null)
                val result =
                    healthConnectManager.readExerciseAggregates(
                        timeRangeFilter = range.timeRangeFilter,
                        bucket = range.granularity.bucketPeriod(),
                        historyPermissionGranted = historyPermissionGranted,
                    )
                aggregatesLoad =
                    PeriodTaggedResult(
                        period = period,
                        result = ExerciseLoad(result = result, granularity = range.granularity, denominatorFloor = range.denominatorFloor),
                    )
            }
        }
    }

    // WBS 6.5のSleepDetailScreenと同じ理由・同じ条件（isLandscapeOrientation()参照）。
    val fullScreenChart = isLandscapeOrientation() && tab == DetailTab.CHART

    Column(modifier = modifier.fillMaxSize()) {
        if (!fullScreenChart) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onBack) {
                    Text(text = stringResource(id = R.string.detail_back))
                }
                Text(text = stringResource(id = R.string.detail_exercise_title), style = MaterialTheme.typography.titleLarge)

                OldestRecordInfo(oldestResult)

                DetailTabs(tab = tab, onTabChange = { tab = it })
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                DetailTab.CHART ->
                    ChartTabColumn(fullScreenChart = fullScreenChart) { availableHeight ->
                        PeriodTabs(period = period, onPeriodChange = { period = it })
                        if (period == GraphPeriod.CUSTOM) {
                            CustomRangePicker(range = customRange, onRangeChange = { customRange = it })
                        }

                        val currentLoad = aggregatesLoad
                        val currentExerciseLoad = if (currentLoad != null && currentLoad.period == period) currentLoad.result else null
                        when (val currentResult = currentExerciseLoad?.result) {
                            null ->
                                if (period == GraphPeriod.CUSTOM && customRange == null) {
                                    Text(text = stringResource(id = R.string.detail_custom_pick_prompt))
                                } else {
                                    CircularProgressIndicator()
                                    Text(text = stringResource(id = R.string.detail_loading))
                                }
                            ExerciseAggregatesResult.Failure -> {
                                Text(text = stringResource(id = R.string.detail_error))
                                Button(onClick = { retryKey++ }) {
                                    Text(text = stringResource(id = R.string.detail_retry))
                                }
                            }
                            is ExerciseAggregatesResult.Success -> {
                                val granularity = currentExerciseLoad.granularity
                                val aggregationLabelRes =
                                    if (granularity == BucketGranularity.DAY) {
                                        R.string.detail_exercise_aggregation_total
                                    } else {
                                        R.string.detail_exercise_aggregation_avg_per_day
                                    }
                                Text(text = stringResource(id = aggregationLabelRes, stringResource(id = granularity.granularityLabelRes())))
                                if (currentResult.historyLimited) {
                                    Text(text = stringResource(id = R.string.detail_history_limited_notice))
                                }
                                ExerciseAggregateChart(
                                    buckets = currentResult.buckets,
                                    granularity = granularity,
                                    denominatorFloor = currentExerciseLoad.denominatorFloor,
                                    modifier =
                                        Modifier.fillMaxWidth().height(
                                            detailChartHeight(
                                                fullScreenChart = fullScreenChart,
                                                availableHeight = availableHeight,
                                                historyLimited = currentResult.historyLimited,
                                                isCustomPeriod = period == GraphPeriod.CUSTOM,
                                            ),
                                        ),
                                )
                            }
                        }
                    }
                DetailTab.RECORDS -> {
                    val pagingItems =
                        remember {
                            Pager(
                                PagingConfig(
                                    pageSize = EXERCISE_RECORDS_PAGE_SIZE,
                                    initialLoadSize = EXERCISE_RECORDS_PAGE_SIZE,
                                    maxSize = EXERCISE_RECORDS_MAX_SIZE,
                                    enablePlaceholders = false,
                                ),
                            ) {
                                healthConnectManager.exerciseSessionRecordsPagingSource(historyPermissionGranted)
                            }.flow
                        }.collectAsLazyPagingItems()
                    RecordsTab(
                        pagingItems = pagingItems,
                        historyLimited = !historyPermissionGranted,
                        rowContent = { record -> ExerciseRecordRow(record = record) },
                    )
                }
                DetailTab.SOURCES -> RecordCountSourcesTab(load = sourcesLoad, onRetry = { sourcesRetryKey++ })
            }
        }
    }
}

// WBS 6.10（D-051）: ExerciseSessionRecord.exerciseTypeの約60種類の定数を文言へ変換する。将来SDKが
// 種別を追加した場合に備え、未知の値はexercise_type_unknownへフォールバックする（SleepDetailScreen.
// stageTypeLabelRes()と同じ考え方）。
private fun exerciseTypeLabelRes(exerciseType: Int): Int =
    when (exerciseType) {
        ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON -> R.string.exercise_type_badminton
        ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL -> R.string.exercise_type_baseball
        ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> R.string.exercise_type_basketball
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> R.string.exercise_type_biking
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> R.string.exercise_type_biking_stationary
        ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP -> R.string.exercise_type_boot_camp
        ExerciseSessionRecord.EXERCISE_TYPE_BOXING -> R.string.exercise_type_boxing
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> R.string.exercise_type_calisthenics
        ExerciseSessionRecord.EXERCISE_TYPE_CRICKET -> R.string.exercise_type_cricket
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> R.string.exercise_type_dancing
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> R.string.exercise_type_elliptical
        ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS -> R.string.exercise_type_exercise_class
        ExerciseSessionRecord.EXERCISE_TYPE_FENCING -> R.string.exercise_type_fencing
        ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN -> R.string.exercise_type_football_american
        ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN -> R.string.exercise_type_football_australian
        ExerciseSessionRecord.EXERCISE_TYPE_FRISBEE_DISC -> R.string.exercise_type_frisbee_disc
        ExerciseSessionRecord.EXERCISE_TYPE_GOLF -> R.string.exercise_type_golf
        ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING -> R.string.exercise_type_guided_breathing
        ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS -> R.string.exercise_type_gymnastics
        ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL -> R.string.exercise_type_handball
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> R.string.exercise_type_high_intensity_interval_training
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> R.string.exercise_type_hiking
        ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY -> R.string.exercise_type_ice_hockey
        ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING -> R.string.exercise_type_ice_skating
        ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS -> R.string.exercise_type_martial_arts
        ExerciseSessionRecord.EXERCISE_TYPE_PADDLING -> R.string.exercise_type_paddling
        ExerciseSessionRecord.EXERCISE_TYPE_PARAGLIDING -> R.string.exercise_type_paragliding
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> R.string.exercise_type_pilates
        ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL -> R.string.exercise_type_racquetball
        ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING -> R.string.exercise_type_rock_climbing
        ExerciseSessionRecord.EXERCISE_TYPE_ROLLER_HOCKEY -> R.string.exercise_type_roller_hockey
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING -> R.string.exercise_type_rowing
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> R.string.exercise_type_rowing_machine
        ExerciseSessionRecord.EXERCISE_TYPE_RUGBY -> R.string.exercise_type_rugby
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> R.string.exercise_type_running
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> R.string.exercise_type_running_treadmill
        ExerciseSessionRecord.EXERCISE_TYPE_SAILING -> R.string.exercise_type_sailing
        ExerciseSessionRecord.EXERCISE_TYPE_SCUBA_DIVING -> R.string.exercise_type_scuba_diving
        ExerciseSessionRecord.EXERCISE_TYPE_SKATING -> R.string.exercise_type_skating
        ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> R.string.exercise_type_skiing
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING -> R.string.exercise_type_snowboarding
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING -> R.string.exercise_type_snowshoeing
        ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> R.string.exercise_type_soccer
        ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL -> R.string.exercise_type_softball
        ExerciseSessionRecord.EXERCISE_TYPE_SQUASH -> R.string.exercise_type_squash
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING -> R.string.exercise_type_stair_climbing
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> R.string.exercise_type_stair_climbing_machine
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> R.string.exercise_type_strength_training
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> R.string.exercise_type_stretching
        ExerciseSessionRecord.EXERCISE_TYPE_SURFING -> R.string.exercise_type_surfing
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> R.string.exercise_type_swimming_open_water
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> R.string.exercise_type_swimming_pool
        ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS -> R.string.exercise_type_table_tennis
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> R.string.exercise_type_tennis
        ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL -> R.string.exercise_type_volleyball
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> R.string.exercise_type_walking
        ExerciseSessionRecord.EXERCISE_TYPE_WATER_POLO -> R.string.exercise_type_water_polo
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> R.string.exercise_type_weightlifting
        ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR -> R.string.exercise_type_wheelchair
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> R.string.exercise_type_yoga
        ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT -> R.string.exercise_type_other_workout
        else -> R.string.exercise_type_unknown
    }

// SleepDetailScreen.SleepRecordRow()と同じ形。exerciseTypeの文言を1行追加する点のみ異なる。
//
// **durationはSession区間（startTime〜endTime）の単純な長さで、公式AggregateMetricのEXERCISE_
// DURATION_TOTALと一致する保証はない**（コードレビュー指摘、要検証）。ExerciseSegmentには
// EXERCISE_SEGMENT_TYPE_PAUSE/EXERCISE_SEGMENT_TYPE_RESTという休憩中を示す定数が存在し
// （javap逆コンパイルで確認）、SleepのSLEEP_DURATION_TOTALが覚醒区間を除いた値になっている
// （D-032、lessons.md 6.9）のと同様に、EXERCISE_DURATION_TOTALもPAUSE/REST区間を除いて計算
// されている可能性がある。ただし実際の計算はHealth Connectプラットフォーム側（非公開実装）が
// 行うため、このアプリ側の逆コンパイルでは確認できない。一時停止を含むセッションの実データで
// Recordsタブの値とChart/ホームカードの値を突き合わせるまで未確認のまま残る
// （requirements.md §27、lessons.md 6.32）。
@Composable
private fun ExerciseRecordRow(record: ExerciseSessionRecord) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val startZone = record.startZoneOffset ?: ZoneId.systemDefault()
    val endZone = record.endZoneOffset ?: ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
    val formattedStart = formatter.format(record.startTime.atZone(startZone))
    val formattedEnd = formatter.format(record.endTime.atZone(endZone))
    val duration = Duration.between(record.startTime, record.endTime)
    val sourceName =
        remember(record.metadata.dataOrigin.packageName) {
            DataOriginNameResolver.resolve(context, record.metadata.dataOrigin.packageName)
        }

    Column {
        Text(text = stringResource(id = exerciseTypeLabelRes(record.exerciseType)))
        Text(text = "$formattedStart – $formattedEnd  ${formatDuration(duration)}")
        Text(text = sourceName, style = MaterialTheme.typography.bodySmall)
    }
}

// SleepDetailScreen.formatDuration()と同じ定義（private宣言のためファイルをまたいで再利用できない、
// 既存の重複方針を踏襲）。
private fun formatDuration(duration: Duration): String {
    val totalMinutes = duration.toMinutes()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return String.format(Locale.ROOT, "%d:%02d", hours, minutes)
}

// DistanceDetailScreen.perDayDuration相当（決定事項5、WEEK/MONTH bucketを実カバー日数で割った
// 1日あたり平均に正規化する。denominatorFloorの意味はSleepDetailScreen.perDayDuration()のコメント参照）。
private fun perDayDuration(
    bucket: ExerciseAggregateBucket,
    granularity: BucketGranularity,
    duration: Duration,
    denominatorFloor: LocalDateTime?,
): Duration =
    if (granularity == BucketGranularity.DAY) {
        duration
    } else {
        val effectiveStart = denominatorFloor?.let { maxOf(bucket.periodStart, it) } ?: bucket.periodStart
        duration.dividedBy(daysCoveredBy(effectiveStart, bucket.periodEnd))
    }

private data class ExerciseChartPoint(val x: Long, val minutes: Double)

// SleepDetailScreen.SleepAggregateChart()とは2点異なる（コードレビュー指摘、2回目のレビューで
// さらに訂正）。
// (1) 値がnullのbucket（＝その期間に運動していない日）を`mapNotNull`で除外せず、Duration.ZEROとして
// 点を必ず打つ。Sleep/Distanceはほぼ毎日値があるため`mapNotNull`で除外しても実質的に影響しないが、
// ExerciseはD-051(2)の通り運動しない日が混在するのが前提のデータで、除外すると間の空白期間が
// 折れ線で補間され、実際には運動していない日にも運動があったかのような誤った印象を与える
// （例: 月曜60分・木曜30分のみの場合、除外すると火・水に運動があったかのような斜線になる）。
// **初版では「bucket自体はaggregateGroupByPeriod()が隙間なく返すため安全」と断定していたが、
// これはlessons.md 7.2が明示的に「未検証」としている前提を無根拠に確定扱いしてしまっていた
// という指摘を受けた**。実際にbucketが隙間なく返るかどうかに関わらずこの実装が正しく動くよう、
// 代わりに「range全体に運動データが1件も無い（＝全bucketのtotalExerciseDurationがnull）」場合を
// 下記で明示的に判定し、その場合は通常の0分埋めグラフではなくdetail_empty（レコードが無い旨の
// 案内）を表示するようにした。これにより、bucketが密に返る場合（0分埋めの折れ線が増える）・
// 疎にしか返らない場合（元々mapNotNullでも同じ結果）のどちらでも、「運動データが全く無い」利用者には
// 以前と同じ空状態の案内が出る。
// (2) 単位をhours（小数）ではなくminutes（小数）にする。Exerciseの1日あたりの値は数分〜数十分程度で、
// 特に月bucketの平均は時間単位だと0.1台の読みにくい小数になるため。
@Composable
private fun ExerciseAggregateChart(
    buckets: List<ExerciseAggregateBucket>,
    granularity: BucketGranularity,
    denominatorFloor: LocalDateTime?,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val modelProducer = remember { CartesianChartModelProducer() }

    if (buckets.all { it.totalExerciseDuration == null }) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(text = stringResource(id = R.string.detail_empty))
        }
        return
    }

    val points =
        remember(buckets, granularity, denominatorFloor) {
            buckets.map { bucket ->
                val duration = bucket.totalExerciseDuration ?: Duration.ZERO
                val normalized = perDayDuration(bucket, granularity, duration, denominatorFloor)
                // toMinutes()（整数切り捨て）ではなくtoMillis()から計算し、小数の端数を残す
                // （コードレビュー指摘: 月bucketの1日あたり平均は60分未満になることが多く、
                // 切り捨てると実際には運動した月でも0分に潰れ、ZERO埋め（上記(1)）と区別できなくなる）。
                ExerciseChartPoint(x = granularity.xValue(bucket.periodStart), minutes = normalized.toMillis() / 60_000.0)
            }
        }

    LaunchedEffect(points) {
        modelProducer.runTransaction {
            lineModel {
                series(x = points.map { it.x }, y = points.map { it.minutes })
            }
        }
    }

    val axisFormatter = remember(granularity, locale) { granularity.axisLabelFormatter(locale) }
    val bottomAxisValueFormatter =
        remember(granularity, axisFormatter) {
            CartesianValueFormatter { _, value, _ -> axisFormatter.format(granularity.dateFromXValue(value.toLong())) }
        }

    ProvideVicoTheme(rememberM3VicoTheme()) {
        CartesianChartHost(
            chart =
                rememberCartesianChart(
                    rememberLineCartesianLayer(),
                    startAxis = VerticalAxis.rememberStart(),
                    bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = bottomAxisValueFormatter),
                    marker = rememberDefaultCartesianMarker(label = rememberTextComponent()),
                ),
            modelProducer = modelProducer,
            modifier = modifier.fillMaxWidth(),
        )
    }
}
