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
import androidx.health.connect.client.records.DistanceRecord
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
import com.yskms.healthdataviewer.healthconnect.DistanceAggregateBucket
import com.yskms.healthdataviewer.healthconnect.DistanceAggregatesResult
import com.yskms.healthdataviewer.healthconnect.HealthConnectManager
import com.yskms.healthdataviewer.healthconnect.OldestRecordResult
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// WBS 6.10: StepsDetailScreenと同じ形（Distanceも合計1系列のみのsum系メトリクス、requirements.md
// §22.2）。StepsはMetricDensity.HIGH（記録頻度の高いActivity系データ）のため、同じ記録経路を
// 持つと見込まれるDistanceも同じ密度プロファイルを踏襲する（実機での記録頻度の実測はまだ行っていない。
// Stepsと大きく異なることが分かればWBS以降で見直す）。
private val DISTANCE_DENSITY = MetricDensity.HIGH

// WBS 6.10: RecordsタブのPagingConfig。StepsDetailScreen.STEPS_RECORDS_PAGE_SIZE等と同じ値
// （具体的な値は実機で検証して調整する、docs/wbs.md 6.3の未決事項を踏襲）。
private const val DISTANCE_RECORDS_PAGE_SIZE = 50
private const val DISTANCE_RECORDS_MAX_SIZE = 200

// denominatorFloorはbucketの日数按分クランプに使う（resolveDetailGraphRange()が解決する。
// StepsDetailScreen.StepsLoadと同じ理由）。クランプ不要な場合はnull。
private data class DistanceLoad(val result: DistanceAggregatesResult, val granularity: BucketGranularity, val denominatorFloor: LocalDateTime?)

@Composable
fun DistanceDetailScreen(
    healthConnectManager: HealthConnectManager,
    historyPermissionGranted: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(DetailTab.CHART) }
    var period by rememberSaveable { mutableStateOf(GraphPeriod.MONTH) }
    var retryKey by remember { mutableIntStateOf(0) }
    var customRange by rememberSaveable { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    var aggregatesLoad by remember { mutableStateOf<PeriodTaggedResult<DistanceLoad>?>(null) }
    var oldestResult by remember { mutableStateOf<OldestRecordResult?>(null) }
    // WBS 6.4のStepsDetailScreenと同じ理由（rememberLazyTabResult()参照）。
    var sourcesRetryKey by remember { mutableIntStateOf(0) }
    val sourcesLoad =
        rememberLazyTabResult(active = tab == DetailTab.SOURCES, retryKey = sourcesRetryKey) {
            healthConnectManager.readSourceRecordCounts(DistanceRecord::class, historyPermissionGranted)
        }

    LaunchedEffect(retryKey) {
        oldestResult = null
        oldestResult = healthConnectManager.findOldestDistanceRecordTime(historyPermissionGranted)
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
                                    DistanceLoad(
                                        result = DistanceAggregatesResult.Success(buckets = emptyList(), historyLimited = !historyPermissionGranted),
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
                density = DISTANCE_DENSITY,
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
                            DistanceLoad(
                                result = DistanceAggregatesResult.Success(buckets = emptyList(), historyLimited = range.historyLimited),
                                granularity = BucketGranularity.DAY,
                                denominatorFloor = null,
                            ),
                    )
            is DetailGraphRange.Resolved -> {
                aggregatesLoad = PeriodTaggedResult(period = period, result = null)
                val result =
                    healthConnectManager.readDistanceAggregates(
                        timeRangeFilter = range.timeRangeFilter,
                        bucket = range.granularity.bucketPeriod(),
                        historyPermissionGranted = historyPermissionGranted,
                    )
                aggregatesLoad =
                    PeriodTaggedResult(
                        period = period,
                        result = DistanceLoad(result = result, granularity = range.granularity, denominatorFloor = range.denominatorFloor),
                    )
            }
        }
    }

    // WBS 6.5のStepsDetailScreenと同じ理由・同じ条件（isLandscapeOrientation()参照）。
    val fullScreenChart = isLandscapeOrientation() && tab == DetailTab.CHART

    Column(modifier = modifier.fillMaxSize()) {
        if (!fullScreenChart) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onBack) {
                    Text(text = stringResource(id = R.string.detail_back))
                }
                Text(text = stringResource(id = R.string.detail_distance_title), style = MaterialTheme.typography.titleLarge)

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
                        val currentDistanceLoad = if (currentLoad != null && currentLoad.period == period) currentLoad.result else null
                        when (val currentResult = currentDistanceLoad?.result) {
                            null ->
                                if (period == GraphPeriod.CUSTOM && customRange == null) {
                                    Text(text = stringResource(id = R.string.detail_custom_pick_prompt))
                                } else {
                                    CircularProgressIndicator()
                                    Text(text = stringResource(id = R.string.detail_loading))
                                }
                            DistanceAggregatesResult.Failure -> {
                                Text(text = stringResource(id = R.string.detail_error))
                                Button(onClick = { retryKey++ }) {
                                    Text(text = stringResource(id = R.string.detail_retry))
                                }
                            }
                            is DistanceAggregatesResult.Success -> {
                                val granularity = currentDistanceLoad.granularity
                                val aggregationLabelRes =
                                    if (granularity == BucketGranularity.DAY) {
                                        R.string.detail_distance_aggregation_total
                                    } else {
                                        R.string.detail_distance_aggregation_avg_per_day
                                    }
                                Text(text = stringResource(id = aggregationLabelRes, stringResource(id = granularity.granularityLabelRes())))
                                if (currentResult.historyLimited) {
                                    Text(text = stringResource(id = R.string.detail_history_limited_notice))
                                }
                                DistanceAggregateChart(
                                    buckets = currentResult.buckets,
                                    granularity = granularity,
                                    denominatorFloor = currentDistanceLoad.denominatorFloor,
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
                                    pageSize = DISTANCE_RECORDS_PAGE_SIZE,
                                    // WeightDetailScreenのPagingConfigコメント参照（初回loadの既定値
                                    // pageSize * 3とページ境界をpageSizeに揃える理由、レビュー指摘）。
                                    initialLoadSize = DISTANCE_RECORDS_PAGE_SIZE,
                                    maxSize = DISTANCE_RECORDS_MAX_SIZE,
                                    enablePlaceholders = false,
                                ),
                            ) {
                                healthConnectManager.distanceRecordsPagingSource(historyPermissionGranted)
                            }.flow
                        }.collectAsLazyPagingItems()
                    RecordsTab(
                        pagingItems = pagingItems,
                        historyLimited = !historyPermissionGranted,
                        rowContent = { record -> DistanceRecordRow(record = record) },
                    )
                }
                DetailTab.SOURCES -> RecordCountSourcesTab(load = sourcesLoad, onRetry = { sourcesRetryKey++ })
            }
        }
    }
}

// WBS 6.10実機確認（Pixel 11、実データ）で判明: Distanceの個々のレコードは30秒前後の短い区間ごとに
// 記録されており（GPS/フィットネスのような継続記録ソース）、集計（ホーム画面・グラフ）がkm単位で
// 妥当な値（1日5〜8km程度）になる一方、レコード1件あたりの移動量はkmの小数2桁では「0.00 km」
// 「0.01 km」のように潰れて見えてしまい、実データで確認するまで気付けなかった。レコード一覧だけは
// Stepsの歩数表示と同じ整数＋桁区切り（NumberFormat.getIntegerInstance）でメートル単位にする
// （mはkm同様、両言語で表記が同じためstring resourceに持たない。D-042）。集計側（ホーム画面・グラフ）は
// 日・月単位の合計で数kmになるためkmのまま変更しない。
@Composable
private fun DistanceRecordRow(record: DistanceRecord) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val numberFormat = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val startZone = record.startZoneOffset ?: ZoneId.systemDefault()
    val endZone = record.endZoneOffset ?: ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
    val formattedStart = formatter.format(record.startTime.atZone(startZone))
    val formattedEnd = formatter.format(record.endTime.atZone(endZone))
    val distanceText = remember(record.distance, numberFormat) { numberFormat.format(record.distance.inMeters) }
    val sourceName =
        remember(record.metadata.dataOrigin.packageName) {
            DataOriginNameResolver.resolve(context, record.metadata.dataOrigin.packageName)
        }

    Column {
        Text(text = "$formattedStart – $formattedEnd  $distanceText m")
        Text(text = sourceName, style = MaterialTheme.typography.bodySmall)
    }
}

private data class DistanceChartPoint(val x: Long, val distanceKm: Double)

// StepsDetailScreen.StepsAggregateChart()と同じ形（合計1系列のみ、WEEK/MONTH bucketは決定事項5により
// 実カバー日数で割った「1日あたり平均距離」に正規化する）。
@Composable
private fun DistanceAggregateChart(
    buckets: List<DistanceAggregateBucket>,
    granularity: BucketGranularity,
    denominatorFloor: LocalDateTime?,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val modelProducer = remember { CartesianChartModelProducer() }
    val points =
        remember(buckets, granularity, denominatorFloor) {
            buckets.mapNotNull { bucket ->
                bucket.totalKilometers?.let { total ->
                    val value =
                        if (granularity == BucketGranularity.DAY) {
                            total
                        } else {
                            val effectiveStart = denominatorFloor?.let { maxOf(bucket.periodStart, it) } ?: bucket.periodStart
                            total / daysCoveredBy(effectiveStart, bucket.periodEnd)
                        }
                    DistanceChartPoint(x = granularity.xValue(bucket.periodStart), distanceKm = value)
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
                series(x = points.map { it.x }, y = points.map { it.distanceKm })
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
