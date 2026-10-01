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
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
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
import com.yskms.healthdataviewer.healthconnect.ActiveCaloriesAggregateBucket
import com.yskms.healthdataviewer.healthconnect.ActiveCaloriesAggregatesResult
import com.yskms.healthdataviewer.healthconnect.DataOriginNameResolver
import com.yskms.healthdataviewer.healthconnect.HealthConnectManager
import com.yskms.healthdataviewer.healthconnect.OldestRecordResult
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// WBS 6.10（D-043）: DistanceDetailScreenと同じ形（Active Caloriesも合計1系列のみのsum系メトリクス、
// requirements.md §22.2）。ActiveCaloriesBurnedRecordはStepsと同じActivity系の継続記録ソースから
// 書き込まれると見込まれるため、同じMetricDensity.HIGHを仮に当てる。Distance（WBS 6.10）とは異なり、
// Pixel 11実機でこの想定（記録頻度）自体はまだ確認していない（要検証、D-043(3)）。
private val ACTIVE_CALORIES_DENSITY = MetricDensity.HIGH

// WBS 6.10: RecordsタブのPagingConfig。DistanceDetailScreen.DISTANCE_RECORDS_PAGE_SIZE等と同じ値。
private const val ACTIVE_CALORIES_RECORDS_PAGE_SIZE = 50
private const val ACTIVE_CALORIES_RECORDS_MAX_SIZE = 200

// denominatorFloorはbucketの日数按分クランプに使う（resolveDetailGraphRange()が解決する。
// DistanceDetailScreen.DistanceLoadと同じ理由）。クランプ不要な場合はnull。
private data class ActiveCaloriesLoad(
    val result: ActiveCaloriesAggregatesResult,
    val granularity: BucketGranularity,
    val denominatorFloor: LocalDateTime?,
)

@Composable
fun ActiveCaloriesDetailScreen(
    healthConnectManager: HealthConnectManager,
    historyPermissionGranted: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(DetailTab.CHART) }
    var period by rememberSaveable { mutableStateOf(GraphPeriod.MONTH) }
    var retryKey by remember { mutableIntStateOf(0) }
    var customRange by rememberSaveable { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    var aggregatesLoad by remember { mutableStateOf<PeriodTaggedResult<ActiveCaloriesLoad>?>(null) }
    var oldestResult by remember { mutableStateOf<OldestRecordResult?>(null) }
    // WBS 6.4のDistanceDetailScreenと同じ理由（rememberLazyTabResult()参照）。
    var sourcesRetryKey by remember { mutableIntStateOf(0) }
    val sourcesLoad =
        rememberLazyTabResult(active = tab == DetailTab.SOURCES, retryKey = sourcesRetryKey) {
            healthConnectManager.readSourceRecordCounts(ActiveCaloriesBurnedRecord::class, historyPermissionGranted)
        }

    LaunchedEffect(retryKey) {
        oldestResult = null
        oldestResult = healthConnectManager.findOldestActiveCaloriesRecordTime(historyPermissionGranted)
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
                                    ActiveCaloriesLoad(
                                        result =
                                            ActiveCaloriesAggregatesResult.Success(
                                                buckets = emptyList(),
                                                historyLimited = !historyPermissionGranted,
                                            ),
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
                density = ACTIVE_CALORIES_DENSITY,
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
                            ActiveCaloriesLoad(
                                result = ActiveCaloriesAggregatesResult.Success(buckets = emptyList(), historyLimited = range.historyLimited),
                                granularity = BucketGranularity.DAY,
                                denominatorFloor = null,
                            ),
                    )
            is DetailGraphRange.Resolved -> {
                aggregatesLoad = PeriodTaggedResult(period = period, result = null)
                val result =
                    healthConnectManager.readActiveCaloriesAggregates(
                        timeRangeFilter = range.timeRangeFilter,
                        bucket = range.granularity.bucketPeriod(),
                        historyPermissionGranted = historyPermissionGranted,
                    )
                aggregatesLoad =
                    PeriodTaggedResult(
                        period = period,
                        result = ActiveCaloriesLoad(result = result, granularity = range.granularity, denominatorFloor = range.denominatorFloor),
                    )
            }
        }
    }

    // WBS 6.5のDistanceDetailScreenと同じ理由・同じ条件（isLandscapeOrientation()参照）。
    val fullScreenChart = isLandscapeOrientation() && tab == DetailTab.CHART

    Column(modifier = modifier.fillMaxSize()) {
        if (!fullScreenChart) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onBack) {
                    Text(text = stringResource(id = R.string.detail_back))
                }
                Text(text = stringResource(id = R.string.detail_active_calories_title), style = MaterialTheme.typography.titleLarge)

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
                        val currentCaloriesLoad = if (currentLoad != null && currentLoad.period == period) currentLoad.result else null
                        when (val currentResult = currentCaloriesLoad?.result) {
                            null ->
                                if (period == GraphPeriod.CUSTOM && customRange == null) {
                                    Text(text = stringResource(id = R.string.detail_custom_pick_prompt))
                                } else {
                                    CircularProgressIndicator()
                                    Text(text = stringResource(id = R.string.detail_loading))
                                }
                            ActiveCaloriesAggregatesResult.Failure -> {
                                Text(text = stringResource(id = R.string.detail_error))
                                Button(onClick = { retryKey++ }) {
                                    Text(text = stringResource(id = R.string.detail_retry))
                                }
                            }
                            is ActiveCaloriesAggregatesResult.Success -> {
                                val granularity = currentCaloriesLoad.granularity
                                val aggregationLabelRes =
                                    if (granularity == BucketGranularity.DAY) {
                                        R.string.detail_active_calories_aggregation_total
                                    } else {
                                        R.string.detail_active_calories_aggregation_avg_per_day
                                    }
                                Text(text = stringResource(id = aggregationLabelRes, stringResource(id = granularity.granularityLabelRes())))
                                if (currentResult.historyLimited) {
                                    Text(text = stringResource(id = R.string.detail_history_limited_notice))
                                }
                                ActiveCaloriesAggregateChart(
                                    buckets = currentResult.buckets,
                                    granularity = granularity,
                                    denominatorFloor = currentCaloriesLoad.denominatorFloor,
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
                                    pageSize = ACTIVE_CALORIES_RECORDS_PAGE_SIZE,
                                    // WeightDetailScreenのPagingConfigコメント参照（初回loadの既定値
                                    // pageSize * 3とページ境界をpageSizeに揃える理由、レビュー指摘）。
                                    initialLoadSize = ACTIVE_CALORIES_RECORDS_PAGE_SIZE,
                                    maxSize = ACTIVE_CALORIES_RECORDS_MAX_SIZE,
                                    enablePlaceholders = false,
                                ),
                            ) {
                                healthConnectManager.activeCaloriesRecordsPagingSource(historyPermissionGranted)
                            }.flow
                        }.collectAsLazyPagingItems()
                    RecordsTab(
                        pagingItems = pagingItems,
                        historyLimited = !historyPermissionGranted,
                        rowContent = { record -> ActiveCaloriesRecordRow(record = record) },
                    )
                }
                DetailTab.SOURCES -> RecordCountSourcesTab(load = sourcesLoad, onRetry = { sourcesRetryKey++ })
            }
        }
    }
}

// WBS 6.10（D-043(3)）: Distance（DistanceRecordRow）がkm表示で実機確認の結果「0.00 km」に潰れる
// 問題（D-042(2)）に気付いたのと異なり、Active Caloriesの個々のレコードの大きさ（30秒前後の区間ごとか
// どうか含む）はまだPixel 11実機で確認していない。暫定でkcalのまま小数1桁（NumberFormatの
// minimumFractionDigits/maximumFractionDigits = 1）にしているが、実機で記録頻度・1件あたりの値を
// 確認した結果、Distanceと同じように単位を使い分ける（例: cal表示）必要が出てくる可能性がある
// （要検証）。
@Composable
private fun ActiveCaloriesRecordRow(record: ActiveCaloriesBurnedRecord) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val numberFormat =
        remember(locale) {
            NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = 1
                maximumFractionDigits = 1
            }
        }
    val startZone = record.startZoneOffset ?: ZoneId.systemDefault()
    val endZone = record.endZoneOffset ?: ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
    val formattedStart = formatter.format(record.startTime.atZone(startZone))
    val formattedEnd = formatter.format(record.endTime.atZone(endZone))
    val energyText = remember(record.energy, numberFormat) { numberFormat.format(record.energy.inKilocalories) }
    val sourceName =
        remember(record.metadata.dataOrigin.packageName) {
            DataOriginNameResolver.resolve(context, record.metadata.dataOrigin.packageName)
        }

    Column {
        Text(text = "$formattedStart – $formattedEnd  $energyText kcal")
        Text(text = sourceName, style = MaterialTheme.typography.bodySmall)
    }
}

private data class ActiveCaloriesChartPoint(val x: Long, val kilocalories: Double)

// DistanceDetailScreen.DistanceAggregateChart()と同じ形（合計1系列のみ、WEEK/MONTH bucketは
// 決定事項5により実カバー日数で割った「1日あたり平均」に正規化する）。
@Composable
private fun ActiveCaloriesAggregateChart(
    buckets: List<ActiveCaloriesAggregateBucket>,
    granularity: BucketGranularity,
    denominatorFloor: LocalDateTime?,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val modelProducer = remember { CartesianChartModelProducer() }
    val points =
        remember(buckets, granularity, denominatorFloor) {
            buckets.mapNotNull { bucket ->
                bucket.totalKilocalories?.let { total ->
                    val value =
                        if (granularity == BucketGranularity.DAY) {
                            total
                        } else {
                            val effectiveStart = denominatorFloor?.let { maxOf(bucket.periodStart, it) } ?: bucket.periodStart
                            total / daysCoveredBy(effectiveStart, bucket.periodEnd)
                        }
                    ActiveCaloriesChartPoint(x = granularity.xValue(bucket.periodStart), kilocalories = value)
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
                series(x = points.map { it.x }, y = points.map { it.kilocalories })
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
