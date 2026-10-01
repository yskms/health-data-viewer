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
import androidx.health.connect.client.records.StepsRecord
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
import com.yskms.healthdataviewer.healthconnect.StepsAggregateBucket
import com.yskms.healthdataviewer.healthconnect.StepsAggregatesResult
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// WBS 6.2: Stepsの詳細画面グラフを新規実装する（要件§27「Stepsのグラフ集計ルール自体が未定」の決着）。
// 既存のpoc/StepsScreen（PoC 2、Raw/Aggregate比較専用）とは別物で、そちらはどこからも遷移しない
// ままにする（D-033と同じ扱い）。StepsはMetricDensity.HIGH（HeartRateと同じ理由・同じ閾値）。
// COUNT_TOTALは合計1系列のみ（SleepAggregateChartと同じ形）で、WEEK/MONTH bucketでは決定事項5により
// 「1日あたり平均」に正規化して表示する。実機ではHeartRateと異なりどの期間も高速（3ヶ月221ms・
// 6ヶ月335ms・1年685ms・全期間5.0秒）なことを確認済み（lessons.md 6.12）。
private val STEPS_DENSITY = MetricDensity.HIGH

// WBS 6.3: RecordsタブのPagingConfig。WeightDetailScreen.WEIGHT_RECORDS_PAGE_SIZE等と同じ理由
// （具体的な値は実機で検証して調整する、docs/wbs.md 6.3の未決事項）。
private const val STEPS_RECORDS_PAGE_SIZE = 50
private const val STEPS_RECORDS_MAX_SIZE = 200

// denominatorFloorはbucketの日数按分クランプに使う（resolveDetailGraphRange()が解決する。
// SleepLoad.denominatorFloorと同じ理由、SleepDetailScreen.perDayDuration()参照）。クランプ不要な
// 場合はnull。
private data class StepsLoad(val result: StepsAggregatesResult, val granularity: BucketGranularity, val denominatorFloor: LocalDateTime?)

@Composable
fun StepsDetailScreen(
    healthConnectManager: HealthConnectManager,
    historyPermissionGranted: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(DetailTab.CHART) }
    var period by rememberSaveable { mutableStateOf(GraphPeriod.MONTH) }
    var retryKey by remember { mutableIntStateOf(0) }
    var customRange by rememberSaveable { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    var aggregatesLoad by remember { mutableStateOf<PeriodTaggedResult<StepsLoad>?>(null) }
    var oldestResult by remember { mutableStateOf<OldestRecordResult?>(null) }
    // WBS 6.4: Sourcesタブは全件走査1回分のIPCを発行するため、タブ切替のたびに再実行しない
    // （rememberLazyTabResult()参照、コードレビュー指摘）。
    var sourcesRetryKey by remember { mutableIntStateOf(0) }
    val sourcesLoad =
        rememberLazyTabResult(active = tab == DetailTab.SOURCES, retryKey = sourcesRetryKey) {
            healthConnectManager.readSourceRecordCounts(StepsRecord::class, historyPermissionGranted)
        }

    LaunchedEffect(retryKey) {
        oldestResult = null
        oldestResult = healthConnectManager.findOldestStepsRecordTime(historyPermissionGranted)
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
                                    StepsLoad(
                                        result = StepsAggregatesResult.Success(buckets = emptyList(), historyLimited = !historyPermissionGranted),
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
                density = STEPS_DENSITY,
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
                            StepsLoad(
                                result = StepsAggregatesResult.Success(buckets = emptyList(), historyLimited = range.historyLimited),
                                granularity = BucketGranularity.DAY,
                                denominatorFloor = null,
                            ),
                    )
            is DetailGraphRange.Resolved -> {
                aggregatesLoad = PeriodTaggedResult(period = period, result = null)
                val result =
                    healthConnectManager.readStepsAggregates(
                        timeRangeFilter = range.timeRangeFilter,
                        bucket = range.granularity.bucketPeriod(),
                        historyPermissionGranted = historyPermissionGranted,
                    )
                aggregatesLoad =
                    PeriodTaggedResult(
                        period = period,
                        result = StepsLoad(result = result, granularity = range.granularity, denominatorFloor = range.denominatorFloor),
                    )
            }
        }
    }

    // WBS 6.5: WeightDetailScreenと同じ理由・同じ条件でChartタブのみ全画面化する（isLandscapeOrientation()
    // 参照）。
    val fullScreenChart = isLandscapeOrientation() && tab == DetailTab.CHART

    // WBS 6.3: WeightDetailScreenと同じ理由でルートをタブ＋Box(weight)構造にする（ChartとRecordsの
    // スクロール戦略が異なるため、同じColumnに両方を入れられない）。
    Column(modifier = modifier.fillMaxSize()) {
        if (!fullScreenChart) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onBack) {
                    Text(text = stringResource(id = R.string.detail_back))
                }
                Text(text = stringResource(id = R.string.detail_steps_title), style = MaterialTheme.typography.titleLarge)

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
                        val currentStepsLoad = if (currentLoad != null && currentLoad.period == period) currentLoad.result else null
                        when (val currentResult = currentStepsLoad?.result) {
                            null ->
                                if (period == GraphPeriod.CUSTOM && customRange == null) {
                                    Text(text = stringResource(id = R.string.detail_custom_pick_prompt))
                                } else {
                                    CircularProgressIndicator()
                                    Text(text = stringResource(id = R.string.detail_loading))
                                }
                            StepsAggregatesResult.Failure -> {
                                Text(text = stringResource(id = R.string.detail_error))
                                Button(onClick = { retryKey++ }) {
                                    Text(text = stringResource(id = R.string.detail_retry))
                                }
                            }
                            is StepsAggregatesResult.Success -> {
                                val granularity = currentStepsLoad.granularity
                                val aggregationLabelRes =
                                    if (granularity == BucketGranularity.DAY) {
                                        R.string.detail_steps_aggregation_total
                                    } else {
                                        R.string.detail_steps_aggregation_avg_per_day
                                    }
                                Text(text = stringResource(id = aggregationLabelRes, stringResource(id = granularity.granularityLabelRes())))
                                if (currentResult.historyLimited) {
                                    Text(text = stringResource(id = R.string.detail_history_limited_notice))
                                }
                                StepsAggregateChart(
                                    buckets = currentResult.buckets,
                                    granularity = granularity,
                                    denominatorFloor = currentStepsLoad.denominatorFloor,
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
                                    pageSize = STEPS_RECORDS_PAGE_SIZE,
                                    // WeightDetailScreenのPagingConfigコメント参照（初回loadの既定値
                                    // pageSize * 3とページ境界をpageSizeに揃える理由、レビュー指摘）。
                                    initialLoadSize = STEPS_RECORDS_PAGE_SIZE,
                                    maxSize = STEPS_RECORDS_MAX_SIZE,
                                    enablePlaceholders = false,
                                ),
                            ) {
                                healthConnectManager.stepsRecordsPagingSource(historyPermissionGranted)
                            }.flow
                        }.collectAsLazyPagingItems()
                    RecordsTab(
                        pagingItems = pagingItems,
                        historyLimited = !historyPermissionGranted,
                        rowContent = { record -> StepsRecordRow(record = record) },
                    )
                }
                DetailTab.SOURCES -> RecordCountSourcesTab(load = sourcesLoad, onRetry = { sourcesRetryKey++ })
            }
        }
    }
}

// poc/StepsScreen.StepsRecordRow()と同じフォーマット（既存PoCから移植）。
@Composable
private fun StepsRecordRow(record: StepsRecord) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val numberFormat = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val startZone = record.startZoneOffset ?: ZoneId.systemDefault()
    val endZone = record.endZoneOffset ?: ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
    val formattedStart = formatter.format(record.startTime.atZone(startZone))
    val formattedEnd = formatter.format(record.endTime.atZone(endZone))
    val sourceName =
        remember(record.metadata.dataOrigin.packageName) {
            DataOriginNameResolver.resolve(context, record.metadata.dataOrigin.packageName)
        }

    Column {
        Text(text = "$formattedStart – $formattedEnd  ${numberFormat.format(record.count)}")
        Text(text = sourceName, style = MaterialTheme.typography.bodySmall)
    }
}

private data class StepsChartPoint(val x: Long, val steps: Double)

// COUNT_TOTALは合計1系列のみ（SleepAggregateChartと同じ形）。WEEK/MONTH bucketでは決定事項5により
// 実カバー日数で割った「1日あたり平均歩数」に正規化する（daysCoveredBy()、SumMetricNormalization.kt）。
// denominatorFloorによる最初のbucketのクランプはSleepDetailScreen.perDayDuration()と同じ理由
// （ALL・Customが暦月境界に揃えるための切り捨てで、実際にデータがあり得る開始日より前の日数まで
// 分母に含めてしまう問題への対処。レビューで発見）。
@Composable
private fun StepsAggregateChart(
    buckets: List<StepsAggregateBucket>,
    granularity: BucketGranularity,
    denominatorFloor: LocalDateTime?,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val modelProducer = remember { CartesianChartModelProducer() }
    val points =
        remember(buckets, granularity, denominatorFloor) {
            buckets.mapNotNull { bucket ->
                bucket.total?.let { total ->
                    val value =
                        if (granularity == BucketGranularity.DAY) {
                            total.toDouble()
                        } else {
                            val effectiveStart = denominatorFloor?.let { maxOf(bucket.periodStart, it) } ?: bucket.periodStart
                            total.toDouble() / daysCoveredBy(effectiveStart, bucket.periodEnd)
                        }
                    StepsChartPoint(x = granularity.xValue(bucket.periodStart), steps = value)
                }
            }
        }

    if (points.isEmpty()) {
        // WeightDetailScreen.WeightAggregateChart()と同じ理由（レビュー指摘）。
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(text = stringResource(id = R.string.detail_empty))
        }
        return
    }

    LaunchedEffect(points) {
        modelProducer.runTransaction {
            lineModel {
                series(x = points.map { it.x }, y = points.map { it.steps })
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
