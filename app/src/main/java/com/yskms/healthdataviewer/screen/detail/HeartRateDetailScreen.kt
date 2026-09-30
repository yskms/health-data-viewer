package com.yskms.healthdataviewer.screen.detail

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import com.yskms.healthdataviewer.healthconnect.HealthConnectManager
import com.yskms.healthdataviewer.healthconnect.HeartRateAggregateBucket
import com.yskms.healthdataviewer.healthconnect.HeartRateAggregatesResult
import com.yskms.healthdataviewer.healthconnect.OldestRecordResult
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

// WBS 6.2: poc/HeartRateGraphScreen（PoC 3）を置き換える正式なDetail画面。WeightDetailScreenと同じ
// 共通基盤を使う。HeartRateはMetricDensity.HIGH（1W/1Mは日bucket、3M/6M/1Yは週bucket、ALLは月bucket。
// 1Y日bucketが実機で遅かったlessons.md 6.8への対策、要検証）。経過時間の実測表示（PoCにあった
// 「集計にかかった時間」テキスト）は正式画面には持ち込まない（決定事項6。粒度チューニングの計測は
// 実装中にLogcatで確認する）。
private val HEART_RATE_DENSITY = MetricDensity.HIGH

private data class HeartRateLoad(val result: HeartRateAggregatesResult, val granularity: BucketGranularity)

@Composable
fun HeartRateDetailScreen(
    healthConnectManager: HealthConnectManager,
    historyPermissionGranted: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var period by rememberSaveable { mutableStateOf(GraphPeriod.MONTH) }
    var retryKey by remember { mutableIntStateOf(0) }
    var customRange by rememberSaveable { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    var aggregatesLoad by remember { mutableStateOf<PeriodTaggedResult<HeartRateLoad>?>(null) }
    var oldestResult by remember { mutableStateOf<OldestRecordResult?>(null) }

    LaunchedEffect(retryKey) {
        oldestResult = null
        oldestResult = healthConnectManager.findOldestHeartRateRecordTime(historyPermissionGranted)
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
                                    HeartRateLoad(
                                        result =
                                            HeartRateAggregatesResult.Success(
                                                buckets = emptyList(),
                                                historyLimited = !historyPermissionGranted,
                                            ),
                                        granularity = BucketGranularity.MONTH,
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
                density = HEART_RATE_DENSITY,
                now = LocalDateTime.now(),
                oldestStart = oldestStart,
                customRange = customRange,
            )
        when (range) {
            DetailGraphRange.Pending -> aggregatesLoad = PeriodTaggedResult(period = period, result = null)
            is DetailGraphRange.Resolved -> {
                aggregatesLoad = PeriodTaggedResult(period = period, result = null)
                val result =
                    healthConnectManager.readHeartRateAggregates(
                        timeRangeFilter = range.timeRangeFilter,
                        bucket = range.granularity.bucketPeriod(),
                        historyPermissionGranted = historyPermissionGranted,
                    )
                aggregatesLoad = PeriodTaggedResult(period = period, result = HeartRateLoad(result = result, granularity = range.granularity))
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) {
            Text(text = stringResource(id = R.string.detail_back))
        }
        Text(text = stringResource(id = R.string.detail_heart_rate_title), style = MaterialTheme.typography.titleLarge)

        OldestRecordInfo(oldestResult)

        PeriodTabs(period = period, onPeriodChange = { period = it })
        if (period == GraphPeriod.CUSTOM) {
            CustomRangePicker(range = customRange, onRangeChange = { customRange = it })
        }

        val currentLoad = aggregatesLoad
        val currentHeartRateLoad = if (currentLoad != null && currentLoad.period == period) currentLoad.result else null
        when (val currentResult = currentHeartRateLoad?.result) {
            null -> {
                CircularProgressIndicator()
                Text(text = stringResource(id = R.string.detail_loading))
            }
            HeartRateAggregatesResult.Failure -> {
                Text(text = stringResource(id = R.string.detail_error))
                Button(onClick = { retryKey++ }) {
                    Text(text = stringResource(id = R.string.detail_retry))
                }
            }
            is HeartRateAggregatesResult.Success -> {
                val granularity = currentHeartRateLoad.granularity
                Text(
                    text =
                        stringResource(
                            id = R.string.detail_heart_rate_aggregation_avg,
                            stringResource(id = granularity.granularityLabelRes()),
                        ),
                )
                if (currentResult.historyLimited) {
                    Text(text = stringResource(id = R.string.detail_history_limited_notice))
                }
                HeartRateAggregateChart(buckets = currentResult.buckets, granularity = granularity)
            }
        }
    }
}

private data class HeartRateChartPoint(val x: Long, val average: Double, val min: Double, val max: Double)

@Composable
private fun HeartRateAggregateChart(
    buckets: List<HeartRateAggregateBucket>,
    granularity: BucketGranularity,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val numberFormat = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val modelProducer = remember { CartesianChartModelProducer() }
    val points =
        remember(buckets, granularity) {
            buckets.mapNotNull { bucket ->
                val average = bucket.averageBpm
                val min = bucket.minBpm
                val max = bucket.maxBpm
                if (average != null && min != null && max != null) {
                    HeartRateChartPoint(
                        x = granularity.xValue(bucket.periodStart),
                        average = average.toDouble(),
                        min = min.toDouble(),
                        max = max.toDouble(),
                    )
                } else {
                    null
                }
            }
        }
    val totalMeasurementCount = remember(buckets) { buckets.sumOf { it.measurementCount ?: 0L } }

    if (points.isEmpty()) {
        Text(text = stringResource(id = R.string.detail_empty))
        return
    }

    LaunchedEffect(points) {
        val xValues = points.map { it.x }
        modelProducer.runTransaction {
            lineModel {
                series(x = xValues, y = points.map { it.average })
                series(x = xValues, y = points.map { it.min })
                series(x = xValues, y = points.map { it.max })
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
    Text(
        text = stringResource(id = R.string.detail_heart_rate_measurement_count, numberFormat.format(totalMeasurementCount)),
        style = MaterialTheme.typography.bodySmall,
    )
}
