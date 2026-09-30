package com.yskms.healthdataviewer.screen.detail

import androidx.compose.foundation.layout.Arrangement
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
import com.yskms.healthdataviewer.healthconnect.WeightAggregateBucket
import com.yskms.healthdataviewer.healthconnect.WeightAggregatesResult
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

// WBS 6.2: poc/WeightGraphScreen（PoC 1〜2）を置き換える正式なDetail画面。bucket境界・periodタグ付け
// パターンはscreen/detail配下の共通ファイル（GraphPeriod/BucketGranularity/MetricDensity/
// DetailGraphRange/PeriodTaggedResult）に一本化した。WeightはMetricDensity.LOW
// （1W/1M/3M/6M/1Yは日bucket、ALLは月bucket。既存PoCの閾値と同じ）。
private val WEIGHT_DENSITY = MetricDensity.LOW

// resolveDetailGraphRange()で決めたgranularityを、呼び出し結果と一緒に保持する（表示側が
// bucketの間隔から粒度を逆算するような不安定な推定をしなくて済むようにするため）。
private data class WeightLoad(val result: WeightAggregatesResult, val granularity: BucketGranularity)

@Composable
fun WeightDetailScreen(
    healthConnectManager: HealthConnectManager,
    historyPermissionGranted: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var period by rememberSaveable { mutableStateOf(GraphPeriod.MONTH) }
    var retryKey by remember { mutableIntStateOf(0) }
    var customRange by rememberSaveable { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    var aggregatesLoad by remember { mutableStateOf<PeriodTaggedResult<WeightLoad>?>(null) }
    // WBS 2.3: ALLの開始日を決めるための最古のレコード時刻。選択中の期間に関わらず一度だけ取得する。
    var oldestResult by remember { mutableStateOf<OldestRecordResult?>(null) }

    LaunchedEffect(retryKey) {
        oldestResult = null
        oldestResult = healthConnectManager.findOldestWeightRecordTime(historyPermissionGranted)
    }

    // ALLだけがoldestResultの確定を待つ必要がある（既存PoCと同じ理由、lessons.md 7.6）。
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
                        // 読み取れる範囲にレコードが1件もないと確定している。Aggregate API自体を呼ばず、
                        // 空の結果を直接返す（lessons.md 7.4）。
                        aggregatesLoad =
                            PeriodTaggedResult(
                                period = period,
                                result =
                                    WeightLoad(
                                        result = WeightAggregatesResult.Success(buckets = emptyList(), historyLimited = !historyPermissionGranted),
                                        granularity = BucketGranularity.MONTH,
                                    ),
                            )
                        return@LaunchedEffect
                    }
                OldestRecordResult.Failure -> Unit // 開始日不明のまま、開始無制限にフォールバックする
            }
        }
        val oldestStart =
            (oldestGateForAll as? OldestRecordResult.Success)?.time
                ?.let { LocalDateTime.ofInstant(it, ZoneId.systemDefault()) }
        val range =
            resolveDetailGraphRange(
                period = period,
                density = WEIGHT_DENSITY,
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
                            WeightLoad(
                                result = WeightAggregatesResult.Success(buckets = emptyList(), historyLimited = range.historyLimited),
                                granularity = BucketGranularity.DAY,
                            ),
                    )
            is DetailGraphRange.Resolved -> {
                aggregatesLoad = PeriodTaggedResult(period = period, result = null)
                val result =
                    healthConnectManager.readWeightAggregates(
                        timeRangeFilter = range.timeRangeFilter,
                        bucket = range.granularity.bucketPeriod(),
                        historyPermissionGranted = historyPermissionGranted,
                    )
                aggregatesLoad = PeriodTaggedResult(period = period, result = WeightLoad(result = result, granularity = range.granularity))
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) {
            Text(text = stringResource(id = R.string.detail_back))
        }
        Text(text = stringResource(id = R.string.detail_weight_title), style = MaterialTheme.typography.titleLarge)

        OldestRecordInfo(oldestResult)

        PeriodTabs(period = period, onPeriodChange = { period = it })
        if (period == GraphPeriod.CUSTOM) {
            CustomRangePicker(range = customRange, onRangeChange = { customRange = it })
        }

        val currentLoad = aggregatesLoad
        val currentWeightLoad = if (currentLoad != null && currentLoad.period == period) currentLoad.result else null
        when (val currentResult = currentWeightLoad?.result) {
            null ->
                if (period == GraphPeriod.CUSTOM && customRange == null) {
                    Text(text = stringResource(id = R.string.detail_custom_pick_prompt))
                } else {
                    CircularProgressIndicator()
                    Text(text = stringResource(id = R.string.detail_loading))
                }
            WeightAggregatesResult.Failure -> {
                Text(text = stringResource(id = R.string.detail_error))
                Button(onClick = { retryKey++ }) {
                    Text(text = stringResource(id = R.string.detail_retry))
                }
            }
            is WeightAggregatesResult.Success -> {
                val granularity = currentWeightLoad.granularity
                Text(
                    text =
                        stringResource(
                            id = R.string.detail_weight_aggregation_avg,
                            stringResource(id = granularity.granularityLabelRes()),
                        ),
                )
                if (currentResult.historyLimited) {
                    Text(text = stringResource(id = R.string.detail_history_limited_notice))
                }
                WeightAggregateChart(buckets = currentResult.buckets, granularity = granularity)
            }
        }
    }
}

// 1点分のプロット対象。average/min/maxのいずれかが欠けているbucketは3系列とも対象から外す
// （既存PoCと同じ理由）。
private data class ChartPoint(val x: Long, val average: Double, val min: Double, val max: Double)

// WBS 2.2: 同日複数レコードのグラフ上の扱いはD-027の通り、平均を主系列、最小・最大を補助的な折れ線
// として重ねて描画し、値がないbucketはプロットしない（前後の点が線でつながる）。
@Composable
private fun WeightAggregateChart(buckets: List<WeightAggregateBucket>, granularity: BucketGranularity, modifier: Modifier = Modifier) {
    val locale = LocalLocale.current.platformLocale
    val modelProducer = remember { CartesianChartModelProducer() }
    val points =
        remember(buckets, granularity) {
            buckets.mapNotNull { bucket ->
                val average = bucket.average
                val min = bucket.min
                val max = bucket.max
                if (average != null && min != null && max != null) {
                    ChartPoint(x = granularity.xValue(bucket.periodStart), average = average, min = min, max = max)
                } else {
                    null
                }
            }
        }

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
}
