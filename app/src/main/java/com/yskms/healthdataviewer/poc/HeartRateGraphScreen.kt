package com.yskms.healthdataviewer.poc

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.health.connect.client.time.TimeRangeFilter
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
import com.yskms.healthdataviewer.healthconnect.OldestHeartRateRecordResult
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

// WBS 4.1（PoC 3）専用のグラフ画面。WeightGraphScreenの構成（bucket境界・periodタグ付け・Vico描画）を
// そのまま複製する。bucket集約・描画性能が主眼のため、Raw画面（HeartRateRawRecordsScreen）とは別に
// WeightRawRecordsScreen/WeightGraphScreenと同様、WBS 6.2の正式なDetail画面に置き換えられる前提の
// 簡略実装（画面回転時は再取得する）。
// HeartRateGraphPeriod・HeartRateBucketGranularity・日初/月初への切り捨てはWeightGraphScreenと同じ定義を複製している
// （private宣言のためファイルをまたいで再利用できない。共通化はWBS 6.2で検討する、lessons.md 7.6）。
private enum class HeartRateBucketGranularity { DAY, MONTH }

private enum class HeartRateGraphPeriod(val bucket: Period, val granularity: HeartRateBucketGranularity) {
    ONE_MONTH(bucket = Period.ofDays(1), granularity = HeartRateBucketGranularity.DAY),
    ONE_YEAR(bucket = Period.ofDays(1), granularity = HeartRateBucketGranularity.DAY),
    ALL(bucket = Period.ofMonths(1), granularity = HeartRateBucketGranularity.MONTH),
}

// WeightGraphScreenのGraphPeriod.timeRangeFilter()と同じロジック（同コメント参照、lessons.md 6.5/7.4/7.5）。
private fun HeartRateGraphPeriod.timeRangeFilter(now: LocalDateTime, oldestStart: LocalDateTime?): TimeRangeFilter =
    when (this) {
        HeartRateGraphPeriod.ONE_MONTH -> TimeRangeFilter.between(startOfDay(now.minus(30, ChronoUnit.DAYS)), now)
        HeartRateGraphPeriod.ONE_YEAR -> TimeRangeFilter.between(startOfDay(now.minus(365, ChronoUnit.DAYS)), now)
        HeartRateGraphPeriod.ALL ->
            if (oldestStart != null) {
                TimeRangeFilter.between(startOfMonth(oldestStart), now)
            } else {
                TimeRangeFilter.before(now)
            }
    }

private fun startOfDay(dateTime: LocalDateTime): LocalDateTime = dateTime.toLocalDate().atStartOfDay()

private fun startOfMonth(dateTime: LocalDateTime): LocalDateTime = dateTime.toLocalDate().withDayOfMonth(1).atStartOfDay()

private fun HeartRateGraphPeriod.xValue(dateTime: LocalDateTime): Long =
    when (granularity) {
        HeartRateBucketGranularity.DAY -> dateTime.toLocalDate().toEpochDay()
        HeartRateBucketGranularity.MONTH -> YearMonth.from(dateTime).let { it.year * 12L + it.monthValue - 1 }
    }

private fun HeartRateGraphPeriod.dateFromXValue(x: Long): LocalDate =
    when (granularity) {
        HeartRateBucketGranularity.DAY -> LocalDate.ofEpochDay(x)
        HeartRateBucketGranularity.MONTH -> YearMonth.of((x / 12).toInt(), (x % 12).toInt() + 1).atDay(1)
    }

// WeightGraphScreenのAggregatesLoadと同じ理由（lessons.md 7.6）。elapsedMillisは
// readHeartRateAggregates()の所要時間の実測値（レビュー指摘。1Yとの比較対象がbucket粒度・期間長の
// 両方で異なり「体感で遅い」としか言えなかったため、実測して残す）。resultがnullの分岐
// （oldestResult確定待ち・findOldestHeartRateRecordTimeの失敗）ではAggregate呼び出し自体が
// 発生しないためnullのまま。
private data class HeartRateAggregatesLoad(val period: HeartRateGraphPeriod, val result: HeartRateAggregatesResult?, val elapsedMillis: Long?)

@Composable
fun HeartRateGraphScreen(
    healthConnectManager: HealthConnectManager,
    historyPermissionGranted: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var period by rememberSaveable { mutableStateOf(HeartRateGraphPeriod.ONE_MONTH) }
    var retryKey by remember { mutableIntStateOf(0) }
    var aggregatesLoad by remember { mutableStateOf<HeartRateAggregatesLoad?>(null) }
    var oldestResult by remember { mutableStateOf<OldestHeartRateRecordResult?>(null) }

    LaunchedEffect(retryKey) {
        oldestResult = null
        oldestResult = healthConnectManager.findOldestHeartRateRecordTime(historyPermissionGranted)
    }

    val oldestGateForAll = if (period == HeartRateGraphPeriod.ALL) oldestResult else null

    LaunchedEffect(period, retryKey, oldestGateForAll) {
        if (period == HeartRateGraphPeriod.ALL) {
            when (val currentOldest = oldestGateForAll) {
                null -> {
                    aggregatesLoad = HeartRateAggregatesLoad(period = period, result = null, elapsedMillis = null)
                    return@LaunchedEffect
                }
                is OldestHeartRateRecordResult.Success ->
                    if (currentOldest.time == null) {
                        aggregatesLoad =
                            HeartRateAggregatesLoad(
                                period = period,
                                result = HeartRateAggregatesResult.Success(buckets = emptyList(), historyLimited = !historyPermissionGranted),
                                elapsedMillis = null,
                            )
                        return@LaunchedEffect
                    }
                OldestHeartRateRecordResult.Failure -> Unit
            }
        }
        aggregatesLoad = HeartRateAggregatesLoad(period = period, result = null, elapsedMillis = null)
        val oldestStart =
            (oldestGateForAll as? OldestHeartRateRecordResult.Success)?.time
                ?.let { LocalDateTime.ofInstant(it, ZoneId.systemDefault()) }
        // WBS 4.1: aggregateGroupByPeriod()の所要時間の実測値を残す（HeartRateAggregatesLoadのコメント参照）。
        // readHeartRateAggregates()内部はreadWithHistoryFallback()を経由するため、履歴読み取り権限が
        // 取り消されていてSecurityExceptionから直近30日へフォールバックした場合、この計測値には
        // 失敗した1回目の呼び出しと再試行分の両方が含まれる（レビュー指摘）。lessons.md 6.8の実測値は
        // 履歴読み取り権限が許可された状態（フォールバックなし、1回の呼び出しのみ）で計測したもの。
        val startElapsed = SystemClock.elapsedRealtime()
        val result =
            healthConnectManager.readHeartRateAggregates(
                timeRangeFilter = period.timeRangeFilter(now = LocalDateTime.now(), oldestStart = oldestStart),
                bucket = period.bucket,
                historyPermissionGranted = historyPermissionGranted,
            )
        val elapsedMillis = SystemClock.elapsedRealtime() - startElapsed
        aggregatesLoad = HeartRateAggregatesLoad(period = period, result = result, elapsedMillis = elapsedMillis)
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) {
            Text(text = stringResource(id = R.string.poc_heart_rate_back))
        }
        Text(text = stringResource(id = R.string.poc_heart_rate_graph_title), style = MaterialTheme.typography.titleLarge)

        OldestRecordInfo(oldestResult)

        SingleChoiceSegmentedButtonRow {
            HeartRateGraphPeriod.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = period == entry,
                    onClick = { period = entry },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = HeartRateGraphPeriod.entries.size),
                ) {
                    Text(text = stringResource(id = entry.labelRes()))
                }
            }
        }

        val currentLoad = aggregatesLoad
        val currentResult = if (currentLoad != null && currentLoad.period == period) currentLoad.result else null
        val currentElapsedMillis = if (currentLoad != null && currentLoad.period == period) currentLoad.elapsedMillis else null
        when (currentResult) {
            null -> {
                CircularProgressIndicator()
                Text(text = stringResource(id = R.string.poc_heart_rate_loading))
            }
            HeartRateAggregatesResult.Failure -> {
                Text(text = stringResource(id = R.string.poc_heart_rate_error))
                Button(onClick = { retryKey++ }) {
                    Text(text = stringResource(id = R.string.poc_heart_rate_retry))
                }
            }
            is HeartRateAggregatesResult.Success -> {
                Text(text = stringResource(id = period.aggregationLabelRes()))
                if (currentResult.historyLimited) {
                    Text(text = stringResource(id = R.string.poc_heart_rate_history_limited_notice))
                }
                if (currentElapsedMillis != null) {
                    val locale = LocalLocale.current.platformLocale
                    val numberFormat = remember(locale) { NumberFormat.getIntegerInstance(locale) }
                    Text(
                        text = stringResource(id = R.string.poc_heart_rate_graph_elapsed_time, numberFormat.format(currentElapsedMillis)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                HeartRateAggregateChart(buckets = currentResult.buckets, period = period)
            }
        }
    }
}

@Composable
private fun OldestRecordInfo(oldestResult: OldestHeartRateRecordResult?) {
    val locale = LocalLocale.current.platformLocale
    val text =
        when (oldestResult) {
            null -> stringResource(id = R.string.poc_heart_rate_loading)
            OldestHeartRateRecordResult.Failure -> stringResource(id = R.string.poc_heart_rate_error)
            is OldestHeartRateRecordResult.Success -> {
                val time = oldestResult.time
                if (time != null) {
                    val formattedDate =
                        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                            .withLocale(locale)
                            .format(time.atZone(ZoneId.systemDefault()).toLocalDate())
                    stringResource(id = R.string.poc_heart_rate_graph_oldest_record, formattedDate)
                } else {
                    stringResource(id = R.string.poc_heart_rate_graph_oldest_record_none)
                }
            }
        }
    Text(text = text, style = MaterialTheme.typography.bodySmall)
}

private fun HeartRateGraphPeriod.labelRes(): Int =
    when (this) {
        HeartRateGraphPeriod.ONE_MONTH -> R.string.poc_heart_rate_graph_period_1m
        HeartRateGraphPeriod.ONE_YEAR -> R.string.poc_heart_rate_graph_period_1y
        HeartRateGraphPeriod.ALL -> R.string.poc_heart_rate_graph_period_all
    }

private fun HeartRateGraphPeriod.aggregationLabelRes(): Int =
    when (this) {
        HeartRateGraphPeriod.ONE_MONTH, HeartRateGraphPeriod.ONE_YEAR -> R.string.poc_heart_rate_graph_aggregation_daily
        HeartRateGraphPeriod.ALL -> R.string.poc_heart_rate_graph_aggregation_monthly
    }

private fun HeartRateGraphPeriod.axisLabelFormatter(locale: Locale): DateTimeFormatter =
    when (this) {
        HeartRateGraphPeriod.ONE_MONTH, HeartRateGraphPeriod.ONE_YEAR -> DateTimeFormatter.ofPattern("M/d", locale)
        HeartRateGraphPeriod.ALL -> DateTimeFormatter.ofPattern("yyyy/M", locale)
    }

// WeightGraphScreenのChartPointと同じ理由でaverage/min/maxが揃っているbucketだけを対象にする。
private data class HeartRateChartPoint(val x: Long, val average: Double, val min: Double, val max: Double)

// WBS 4.1: 平均を折れ線、最小・最大を補助的な折れ線として重ねる構成はWeightAggregateChartと同じ。
// measurementCount（サンプル数、requirements.md §22.2）は系列の単位（bpm）と異なるため、
// チャートの系列には混ぜず、表示中bucketの合計値をテキストで補足するだけに留める（PoCとして十分）。
@Composable
private fun HeartRateAggregateChart(buckets: List<HeartRateAggregateBucket>, period: HeartRateGraphPeriod, modifier: Modifier = Modifier) {
    val locale = LocalLocale.current.platformLocale
    val numberFormat = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val modelProducer = remember { CartesianChartModelProducer() }
    val points =
        remember(buckets, period) {
            buckets.mapNotNull { bucket ->
                val average = bucket.averageBpm
                val min = bucket.minBpm
                val max = bucket.maxBpm
                if (average != null && min != null && max != null) {
                    HeartRateChartPoint(x = period.xValue(bucket.periodStart), average = average.toDouble(), min = min.toDouble(), max = max.toDouble())
                } else {
                    null
                }
            }
        }
    val totalMeasurementCount = remember(buckets) { buckets.sumOf { it.measurementCount ?: 0L } }

    if (points.isEmpty()) {
        Text(text = stringResource(id = R.string.poc_heart_rate_empty))
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

    val axisFormatter = remember(period, locale) { period.axisLabelFormatter(locale) }
    val bottomAxisValueFormatter =
        remember(period, axisFormatter) {
            CartesianValueFormatter { _, value, _ -> axisFormatter.format(period.dateFromXValue(value.toLong())) }
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
        text = stringResource(id = R.string.poc_heart_rate_graph_measurement_count, numberFormat.format(totalMeasurementCount)),
        style = MaterialTheme.typography.bodySmall,
    )
}
