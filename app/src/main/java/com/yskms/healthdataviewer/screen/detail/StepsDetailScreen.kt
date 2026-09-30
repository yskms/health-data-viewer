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
import com.yskms.healthdataviewer.healthconnect.OldestRecordResult
import com.yskms.healthdataviewer.healthconnect.StepsAggregateBucket
import com.yskms.healthdataviewer.healthconnect.StepsAggregatesResult
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

// WBS 6.2: Stepsの詳細画面グラフを新規実装する（要件§27「Stepsのグラフ集計ルール自体が未定」の決着）。
// 既存のpoc/StepsScreen（PoC 2、Raw/Aggregate比較専用）とは別物で、そちらはどこからも遷移しない
// ままにする（D-033と同じ扱い）。StepsはMetricDensity.HIGH（HeartRateと同じ理由・同じ閾値。
// Stepsのbucket集計は今回が初実装で所要時間が未実測のため保守的な閾値を暫定適用する、要検証）。
// COUNT_TOTALは合計1系列のみ（SleepAggregateChartと同じ形）で、WEEK/MONTH bucketでは決定事項5により
// 「1日あたり平均」に正規化して表示する。
private val STEPS_DENSITY = MetricDensity.HIGH

// oldestStartはALLの最初のbucketの日数按分クランプに使う（SleepLoad.oldestStartと同じ理由、
// SleepDetailScreen.perDayDuration()参照）。ALL以外のperiodではnull。
private data class StepsLoad(val result: StepsAggregatesResult, val granularity: BucketGranularity, val oldestStart: LocalDateTime?)

@Composable
fun StepsDetailScreen(
    healthConnectManager: HealthConnectManager,
    historyPermissionGranted: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var period by rememberSaveable { mutableStateOf(GraphPeriod.MONTH) }
    var retryKey by remember { mutableIntStateOf(0) }
    var customRange by rememberSaveable { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    var aggregatesLoad by remember { mutableStateOf<PeriodTaggedResult<StepsLoad>?>(null) }
    var oldestResult by remember { mutableStateOf<OldestRecordResult?>(null) }

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
                                        oldestStart = null,
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
                customRange = customRange,
            )
        when (range) {
            DetailGraphRange.Pending -> aggregatesLoad = PeriodTaggedResult(period = period, result = null)
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
                        result = StepsLoad(result = result, granularity = range.granularity, oldestStart = oldestStart),
                    )
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
        Text(text = stringResource(id = R.string.detail_steps_title), style = MaterialTheme.typography.titleLarge)

        OldestRecordInfo(oldestResult)

        PeriodTabs(period = period, onPeriodChange = { period = it })
        if (period == GraphPeriod.CUSTOM) {
            CustomRangePicker(range = customRange, onRangeChange = { customRange = it })
        }

        val currentLoad = aggregatesLoad
        val currentStepsLoad = if (currentLoad != null && currentLoad.period == period) currentLoad.result else null
        when (val currentResult = currentStepsLoad?.result) {
            null -> {
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
                    if (granularity == BucketGranularity.DAY) R.string.detail_steps_aggregation_total else R.string.detail_steps_aggregation_avg_per_day
                Text(text = stringResource(id = aggregationLabelRes, stringResource(id = granularity.granularityLabelRes())))
                if (currentResult.historyLimited) {
                    Text(text = stringResource(id = R.string.detail_history_limited_notice))
                }
                StepsAggregateChart(buckets = currentResult.buckets, granularity = granularity, oldestStart = currentStepsLoad.oldestStart)
            }
        }
    }
}

private data class StepsChartPoint(val x: Long, val steps: Double)

// COUNT_TOTALは合計1系列のみ（SleepAggregateChartと同じ形）。WEEK/MONTH bucketでは決定事項5により
// 実カバー日数で割った「1日あたり平均歩数」に正規化する（daysCoveredBy()、SumMetricNormalization.kt）。
// oldestStartによる最初のbucketのクランプはSleepDetailScreen.perDayDuration()と同じ理由
// （ALLが暦月境界に揃えるためのstartOfMonth()切り捨てで、実際の最古レコードより前の日数まで
// 分母に含めてしまう問題への対処。レビューで発見）。
@Composable
private fun StepsAggregateChart(
    buckets: List<StepsAggregateBucket>,
    granularity: BucketGranularity,
    oldestStart: LocalDateTime?,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val modelProducer = remember { CartesianChartModelProducer() }
    val points =
        remember(buckets, granularity, oldestStart) {
            buckets.mapNotNull { bucket ->
                bucket.total?.let { total ->
                    val value =
                        if (granularity == BucketGranularity.DAY) {
                            total.toDouble()
                        } else {
                            val effectiveStart = oldestStart?.let { maxOf(bucket.periodStart, it) } ?: bucket.periodStart
                            total.toDouble() / daysCoveredBy(effectiveStart, bucket.periodEnd)
                        }
                    StepsChartPoint(x = granularity.xValue(bucket.periodStart), steps = value)
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
            modifier = modifier.fillMaxWidth().height(240.dp),
        )
    }
}
