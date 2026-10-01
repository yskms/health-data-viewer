package com.yskms.healthdataviewer.screen.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.SleepSessionRecord
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
import com.yskms.healthdataviewer.healthconnect.HealthConnectManager
import com.yskms.healthdataviewer.healthconnect.OldestRecordResult
import com.yskms.healthdataviewer.healthconnect.SleepAggregateBucket
import com.yskms.healthdataviewer.healthconnect.SleepAggregatesResult
import com.yskms.healthdataviewer.healthconnect.SourceRecordCountsResult
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// WBS 6.2: poc/SleepGraphScreen（PoC 4）を置き換える正式なDetail画面。SleepはMetricDensity.LOW
// （Sleep Sessionは1日1〜数件程度で記録頻度が低く、HeartRate/Stepsのような高頻度対策は不要という
// PoCでの判断を踏襲）。WEEK/MONTH bucket（3M/6M/1Y/ALL）では決定事項5により、SLEEP_DURATION_TOTAL
// （AWAKE区間除外・日付境界按分済み、D-032）の合計を実カバー日数で割った「1日あたり平均睡眠時間」に
// 正規化して表示する。これが要件§27「Sleepの月bucket（ALL）の集計方法が未定」の決着＝月合計ではなく
// 1日あたり平均にする（記録開始月・当月のような日数不足月が不自然に低く見える問題を解消する）。
private val SLEEP_DENSITY = MetricDensity.LOW

// WBS 6.3: RecordsタブのPagingConfig。WeightDetailScreen.WEIGHT_RECORDS_PAGE_SIZE等と同じ理由
// （具体的な値は実機で検証して調整する、docs/wbs.md 6.3の未決事項）。
private const val SLEEP_RECORDS_PAGE_SIZE = 50
private const val SLEEP_RECORDS_MAX_SIZE = 200

// denominatorFloorはbucketの日数按分クランプに使う（resolveDetailGraphRange()が解決する。
// perDayDuration()参照）。クランプ不要な場合はnull。
private data class SleepLoad(val result: SleepAggregatesResult, val granularity: BucketGranularity, val denominatorFloor: LocalDateTime?)

@Composable
fun SleepDetailScreen(
    healthConnectManager: HealthConnectManager,
    historyPermissionGranted: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(DetailTab.CHART) }
    var period by rememberSaveable { mutableStateOf(GraphPeriod.MONTH) }
    var retryKey by remember { mutableIntStateOf(0) }
    var customRange by rememberSaveable { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    var aggregatesLoad by remember { mutableStateOf<PeriodTaggedResult<SleepLoad>?>(null) }
    var oldestResult by remember { mutableStateOf<OldestRecordResult?>(null) }

    LaunchedEffect(retryKey) {
        oldestResult = null
        oldestResult = healthConnectManager.findOldestSleepSessionRecordTime(historyPermissionGranted)
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
                                    SleepLoad(
                                        result = SleepAggregatesResult.Success(buckets = emptyList(), historyLimited = !historyPermissionGranted),
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
                density = SLEEP_DENSITY,
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
                            SleepLoad(
                                result = SleepAggregatesResult.Success(buckets = emptyList(), historyLimited = range.historyLimited),
                                granularity = BucketGranularity.DAY,
                                denominatorFloor = null,
                            ),
                    )
            is DetailGraphRange.Resolved -> {
                aggregatesLoad = PeriodTaggedResult(period = period, result = null)
                val result =
                    healthConnectManager.readSleepAggregates(
                        timeRangeFilter = range.timeRangeFilter,
                        bucket = range.granularity.bucketPeriod(),
                        historyPermissionGranted = historyPermissionGranted,
                    )
                aggregatesLoad =
                    PeriodTaggedResult(
                        period = period,
                        result = SleepLoad(result = result, granularity = range.granularity, denominatorFloor = range.denominatorFloor),
                    )
            }
        }
    }

    // WBS 6.3: WeightDetailScreenと同じ理由でルートをタブ＋Box(weight)構造にする。
    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = onBack) {
                Text(text = stringResource(id = R.string.detail_back))
            }
            Text(text = stringResource(id = R.string.detail_sleep_title), style = MaterialTheme.typography.titleLarge)

            OldestRecordInfo(oldestResult)

            DetailTabs(tab = tab, onTabChange = { tab = it })
        }

        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                DetailTab.CHART ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        PeriodTabs(period = period, onPeriodChange = { period = it })
                        if (period == GraphPeriod.CUSTOM) {
                            CustomRangePicker(range = customRange, onRangeChange = { customRange = it })
                        }

                        val currentLoad = aggregatesLoad
                        val currentSleepLoad = if (currentLoad != null && currentLoad.period == period) currentLoad.result else null
                        when (val currentResult = currentSleepLoad?.result) {
                            null ->
                                if (period == GraphPeriod.CUSTOM && customRange == null) {
                                    Text(text = stringResource(id = R.string.detail_custom_pick_prompt))
                                } else {
                                    CircularProgressIndicator()
                                    Text(text = stringResource(id = R.string.detail_loading))
                                }
                            SleepAggregatesResult.Failure -> {
                                Text(text = stringResource(id = R.string.detail_error))
                                Button(onClick = { retryKey++ }) {
                                    Text(text = stringResource(id = R.string.detail_retry))
                                }
                            }
                            is SleepAggregatesResult.Success -> {
                                val granularity = currentSleepLoad.granularity
                                val aggregationLabelRes =
                                    if (granularity == BucketGranularity.DAY) {
                                        R.string.detail_sleep_aggregation_total
                                    } else {
                                        R.string.detail_sleep_aggregation_avg_per_day
                                    }
                                Text(text = stringResource(id = aggregationLabelRes, stringResource(id = granularity.granularityLabelRes())))
                                if (currentResult.historyLimited) {
                                    Text(text = stringResource(id = R.string.detail_history_limited_notice))
                                }
                                SleepAggregateChart(
                                    buckets = currentResult.buckets,
                                    granularity = granularity,
                                    denominatorFloor = currentSleepLoad.denominatorFloor,
                                )
                                // WBS 5.1: 日付境界をまたぐSessionがbucketにどう配分されるかを、グラフの折れ線
                                // だけでなく数値でも確認できるようにする（既存PoCから引き続き。Vicoのマーカーは
                                // 長押し操作が必要）。
                                SleepBucketList(
                                    buckets = currentResult.buckets,
                                    granularity = granularity,
                                    denominatorFloor = currentSleepLoad.denominatorFloor,
                                )
                            }
                        }
                    }
                DetailTab.RECORDS -> {
                    val pagingItems =
                        remember {
                            Pager(
                                PagingConfig(
                                    pageSize = SLEEP_RECORDS_PAGE_SIZE,
                                    // WeightDetailScreenのPagingConfigコメント参照（初回loadの既定値
                                    // pageSize * 3とページ境界をpageSizeに揃える理由、レビュー指摘）。
                                    initialLoadSize = SLEEP_RECORDS_PAGE_SIZE,
                                    maxSize = SLEEP_RECORDS_MAX_SIZE,
                                    enablePlaceholders = false,
                                ),
                            ) {
                                healthConnectManager.sleepSessionRecordsPagingSource(historyPermissionGranted)
                            }.flow
                        }.collectAsLazyPagingItems()
                    RecordsTab(
                        pagingItems = pagingItems,
                        historyLimited = !historyPermissionGranted,
                        rowContent = { record -> SleepRecordRow(record = record) },
                    )
                }
                DetailTab.SOURCES -> {
                    // WeightDetailScreenのSourcesタブと同じ考え方（タブに入っている間だけ生存する状態）。
                    // Sleep Sessionは件数が少なく、Weightと同じ全件走査カウント（readSleepSourceCounts()）を使う。
                    var sourcesLoad by remember { mutableStateOf<SourceRecordCountsResult?>(null) }
                    var sourcesRetryKey by remember { mutableIntStateOf(0) }
                    LaunchedEffect(sourcesRetryKey) {
                        sourcesLoad = null
                        sourcesLoad = healthConnectManager.readSleepSourceCounts(historyPermissionGranted)
                    }
                    RecordCountSourcesTab(load = sourcesLoad, onRetry = { sourcesRetryKey++ })
                }
            }
        }
    }
}

// 旧poc/SleepRawRecordsScreen.stageTypeLabelRes()と同じ対応（WBS 6.3で移植、元のPoC画面は削除）。
private fun stageTypeLabelRes(stageType: Int): Int =
    when (stageType) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> R.string.detail_sleep_stage_awake
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> R.string.detail_sleep_stage_sleeping
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> R.string.detail_sleep_stage_out_of_bed
        SleepSessionRecord.STAGE_TYPE_LIGHT -> R.string.detail_sleep_stage_light
        SleepSessionRecord.STAGE_TYPE_DEEP -> R.string.detail_sleep_stage_deep
        SleepSessionRecord.STAGE_TYPE_REM -> R.string.detail_sleep_stage_rem
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> R.string.detail_sleep_stage_awake_in_bed
        else -> R.string.detail_sleep_stage_unknown
    }

// 旧poc/SleepRawRecordsScreen.STAGE_DISPLAY_ORDERと同じ順序（WBS 6.3で移植、元のPoC画面は削除）。
private val STAGE_DISPLAY_ORDER =
    listOf(
        SleepSessionRecord.STAGE_TYPE_AWAKE,
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
        SleepSessionRecord.STAGE_TYPE_SLEEPING,
        SleepSessionRecord.STAGE_TYPE_LIGHT,
        SleepSessionRecord.STAGE_TYPE_DEEP,
        SleepSessionRecord.STAGE_TYPE_REM,
        SleepSessionRecord.STAGE_TYPE_UNKNOWN,
    )

// 旧poc/SleepRawRecordsScreen.stageTotals()と同じ（WBS 6.3で移植、元のPoC画面は削除）。
private fun stageTotals(stages: List<SleepSessionRecord.Stage>): Map<Int, Duration> =
    stages
        .groupBy { it.stage }
        .mapValues { (_, list) -> list.fold(Duration.ZERO) { acc, stage -> acc + Duration.between(stage.startTime, stage.endTime) } }

// 旧poc/SleepRawRecordsScreen.SleepRecordRow()と同じフォーマット（WBS 6.3で移植、元のPoC画面は削除）。
// formatDuration()はこのファイル内の既存定義をそのまま使う。
@Composable
private fun SleepRecordRow(record: SleepSessionRecord) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val startZone = record.startZoneOffset ?: ZoneId.systemDefault()
    val endZone = record.endZoneOffset ?: ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
    val formattedStart = formatter.format(record.startTime.atZone(startZone))
    val formattedEnd = formatter.format(record.endTime.atZone(endZone))
    val duration = Duration.between(record.startTime, record.endTime)
    val stages = remember(record) { stageTotals(record.stages) }
    val sourceName =
        remember(record.metadata.dataOrigin.packageName) {
            DataOriginNameResolver.resolve(context, record.metadata.dataOrigin.packageName)
        }

    Column {
        Text(text = "$formattedStart – $formattedEnd  ${formatDuration(duration)}")
        Text(text = sourceName, style = MaterialTheme.typography.bodySmall)
        if (stages.isEmpty()) {
            Text(text = stringResource(id = R.string.detail_sleep_no_stage_data), style = MaterialTheme.typography.bodySmall)
        } else {
            // joinToString(transform = ...)は非inline関数のため、渡したラムダの中で@Composableな
            // stringResource()を直接呼べない（コンパイルエラーになる）。mapNotNull（inline）側でラベルを
            // 文字列に解決してから、transformなしのjoinToStringで連結する（旧poc/SleepRawRecordsScreenから踏襲）。
            val stageText =
                STAGE_DISPLAY_ORDER
                    .mapNotNull { stageType ->
                        stages[stageType]?.let { stageDuration -> "${stringResource(id = stageTypeLabelRes(stageType))} ${formatDuration(stageDuration)}" }
                    }.joinToString(separator = " ・ ")
            Text(text = stageText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// 旧poc/SleepRawRecordsScreen.formatDuration()と同じ定義（private宣言のためファイルをまたいで再利用でき
// ない。元のPoC画面はWBS 6.3で削除）。
private fun formatDuration(duration: Duration): String {
    val totalMinutes = duration.toMinutes()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return String.format(Locale.ROOT, "%d:%02d", hours, minutes)
}

// WEEK/MONTH bucketでは決定事項5により実カバー日数（暦日数、両端含む）で割った1日あたり平均に
// 正規化する（daysCoveredBy()、SumMetricNormalization.kt）。
//
// denominatorFloor: ALL・Customが暦月境界に揃えるための切り捨て（lessons.md 7.5）により、実際に
// データがあり得る開始日より前の日数まで含めた「bucketの全日数」がperiodStart〜periodEndになる
// 場合がある（例: 最古レコードが5/27でもbucketは5/1〜6/1の31日間になる）。この31日をそのまま
// 分母にすると、実際にはレコードが存在し得ない5/1〜5/26分も「記録なし」として平均に薄めて
// 含めてしまい、「日数不足月が不自然に低く見える」という決定事項5がそもそも解消したかった問題を、
// 切り捨てが別の形で再現してしまう（レビューで発見）。denominatorFloorが分かっている場合は、
// bucket開始時刻をmaxOf(bucket.periodStart, denominatorFloor)にクランプしてから日数を数え、
// 実際にレコードが存在し得た範囲だけを分母にする（resolveDetailGraphRange()が解決する。
// クランプ不要な場合はnullで、このとき従来通りbucket.periodStartを使う。このクランプは最初の
// bucketのみに影響し、以降のbucketは通常periodStart >= denominatorFloorのため実質的に無効化される）。
private fun perDayDuration(
    bucket: SleepAggregateBucket,
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

private data class SleepChartPoint(val x: Long, val hours: Double)

// SLEEP_DURATION_TOTALは合計1系列のみ（Weight/HeartRateの平均・最小・最大3系列と異なる、
// requirements.md §22.2）。値がないbucketはプロットしない（前後の点が線でつながる）。
@Composable
private fun SleepAggregateChart(
    buckets: List<SleepAggregateBucket>,
    granularity: BucketGranularity,
    denominatorFloor: LocalDateTime?,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val modelProducer = remember { CartesianChartModelProducer() }
    val points =
        remember(buckets, granularity, denominatorFloor) {
            buckets.mapNotNull { bucket ->
                bucket.totalSleepDuration?.let { duration ->
                    val normalized = perDayDuration(bucket, granularity, duration, denominatorFloor)
                    SleepChartPoint(x = granularity.xValue(bucket.periodStart), hours = normalized.toMinutes() / 60.0)
                }
            }
        }

    if (points.isEmpty()) {
        Text(text = stringResource(id = R.string.detail_empty))
        return
    }

    LaunchedEffect(points) {
        modelProducer.runTransaction {
            lineModel {
                series(x = points.map { it.x }, y = points.map { it.hours })
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
            modifier = modifier.fillMaxWidth().height(240.dp),
        )
    }
}

// SleepAggregateChartと同じ対象（値のあるbucketのみ、正規化済みの値）を、日付+時間のテキスト一覧
// として表示する。ALL（月bucket）ではdateFromXValueが各月1日を返す。
@Composable
private fun SleepBucketList(
    buckets: List<SleepAggregateBucket>,
    granularity: BucketGranularity,
    denominatorFloor: LocalDateTime?,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val rows =
        remember(buckets, granularity, denominatorFloor) {
            buckets
                .mapNotNull { bucket ->
                    bucket.totalSleepDuration?.let { duration ->
                        granularity.xValue(bucket.periodStart) to perDayDuration(bucket, granularity, duration, denominatorFloor)
                    }
                }.sortedByDescending { it.first }
        }
    if (rows.isEmpty()) return

    val formatter = remember(granularity, locale) { granularity.axisLabelFormatter(locale) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier) {
        Text(text = stringResource(id = R.string.detail_sleep_bucket_list_title), style = MaterialTheme.typography.titleMedium)
        for ((x, duration) in rows) {
            Text(
                text = "${formatter.format(granularity.dateFromXValue(x))}  ${formatDuration(duration)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
