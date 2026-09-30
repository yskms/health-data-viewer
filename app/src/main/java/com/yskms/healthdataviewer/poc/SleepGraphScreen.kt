package com.yskms.healthdataviewer.poc

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
import com.yskms.healthdataviewer.healthconnect.OldestSleepSessionRecordResult
import com.yskms.healthdataviewer.healthconnect.SleepAggregateBucket
import com.yskms.healthdataviewer.healthconnect.SleepAggregatesResult
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

// WBS 5.1（PoC 4）専用のグラフ画面。WeightGraphScreen/HeartRateGraphScreenの構成（bucket境界・
// periodタグ付け・Vico描画）をそのまま複製する。この画面の主目的は要件§10・§27の
// 「日付境界をまたぐSleep Sessionの扱い」「タイムゾーン変更・夏時間の扱い」の確認で、
// SleepRawRecordsScreen（Session/Stageの生データ、Source priority）とは別画面にしている。
// SleepGraphPeriod・SleepBucketGranularity・日初/月初への切り捨て・xValue/dateFromXValueは
// WeightGraphScreenと同じ定義を複製している（private宣言のためファイルをまたいで再利用できない。
// 共通化はWBS 6.2で検討する、lessons.md 7.6）。
private enum class SleepBucketGranularity { DAY, MONTH }

private enum class SleepGraphPeriod(val bucket: Period, val granularity: SleepBucketGranularity) {
    ONE_MONTH(bucket = Period.ofDays(1), granularity = SleepBucketGranularity.DAY),
    ONE_YEAR(bucket = Period.ofDays(1), granularity = SleepBucketGranularity.DAY),
    ALL(bucket = Period.ofMonths(1), granularity = SleepBucketGranularity.MONTH),
}

// WeightGraphScreenのGraphPeriod.timeRangeFilter()と同じロジック（同コメント参照、lessons.md 6.5/7.4/7.5）。
private fun SleepGraphPeriod.timeRangeFilter(now: LocalDateTime, oldestStart: LocalDateTime?): TimeRangeFilter =
    when (this) {
        SleepGraphPeriod.ONE_MONTH -> TimeRangeFilter.between(startOfDay(now.minus(30, ChronoUnit.DAYS)), now)
        SleepGraphPeriod.ONE_YEAR -> TimeRangeFilter.between(startOfDay(now.minus(365, ChronoUnit.DAYS)), now)
        SleepGraphPeriod.ALL ->
            if (oldestStart != null) {
                TimeRangeFilter.between(startOfMonth(oldestStart), now)
            } else {
                TimeRangeFilter.before(now)
            }
    }

private fun startOfDay(dateTime: LocalDateTime): LocalDateTime = dateTime.toLocalDate().atStartOfDay()

private fun startOfMonth(dateTime: LocalDateTime): LocalDateTime = dateTime.toLocalDate().withDayOfMonth(1).atStartOfDay()

private fun SleepGraphPeriod.xValue(dateTime: LocalDateTime): Long =
    when (granularity) {
        SleepBucketGranularity.DAY -> dateTime.toLocalDate().toEpochDay()
        SleepBucketGranularity.MONTH -> YearMonth.from(dateTime).let { it.year * 12L + it.monthValue - 1 }
    }

private fun SleepGraphPeriod.dateFromXValue(x: Long): LocalDate =
    when (granularity) {
        SleepBucketGranularity.DAY -> LocalDate.ofEpochDay(x)
        SleepBucketGranularity.MONTH -> YearMonth.of((x / 12).toInt(), (x % 12).toInt() + 1).atDay(1)
    }

// WeightGraphScreenのAggregatesLoadと同じ理由（lessons.md 7.6）。
private data class SleepAggregatesLoad(val period: SleepGraphPeriod, val result: SleepAggregatesResult?)

@Composable
fun SleepGraphScreen(
    healthConnectManager: HealthConnectManager,
    historyPermissionGranted: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var period by rememberSaveable { mutableStateOf(SleepGraphPeriod.ONE_MONTH) }
    var retryKey by remember { mutableIntStateOf(0) }
    var aggregatesLoad by remember { mutableStateOf<SleepAggregatesLoad?>(null) }
    var oldestResult by remember { mutableStateOf<OldestSleepSessionRecordResult?>(null) }

    LaunchedEffect(retryKey) {
        oldestResult = null
        oldestResult = healthConnectManager.findOldestSleepSessionRecordTime(historyPermissionGranted)
    }

    val oldestGateForAll = if (period == SleepGraphPeriod.ALL) oldestResult else null

    LaunchedEffect(period, retryKey, oldestGateForAll) {
        if (period == SleepGraphPeriod.ALL) {
            when (val currentOldest = oldestGateForAll) {
                null -> {
                    aggregatesLoad = SleepAggregatesLoad(period = period, result = null)
                    return@LaunchedEffect
                }
                is OldestSleepSessionRecordResult.Success ->
                    if (currentOldest.time == null) {
                        aggregatesLoad =
                            SleepAggregatesLoad(
                                period = period,
                                result = SleepAggregatesResult.Success(buckets = emptyList(), historyLimited = !historyPermissionGranted),
                            )
                        return@LaunchedEffect
                    }
                OldestSleepSessionRecordResult.Failure -> Unit
            }
        }
        aggregatesLoad = SleepAggregatesLoad(period = period, result = null)
        val oldestStart =
            (oldestGateForAll as? OldestSleepSessionRecordResult.Success)?.time
                ?.let { LocalDateTime.ofInstant(it, ZoneId.systemDefault()) }
        val result =
            healthConnectManager.readSleepAggregates(
                timeRangeFilter = period.timeRangeFilter(now = LocalDateTime.now(), oldestStart = oldestStart),
                bucket = period.bucket,
                historyPermissionGranted = historyPermissionGranted,
            )
        aggregatesLoad = SleepAggregatesLoad(period = period, result = result)
    }

    // WeightGraphScreen/HeartRateGraphScreenにはなかったverticalScroll()をここで追加している。
    // SleepGraphScreen独自のSleepBucketList（bucket別の数値一覧、日付境界の按分確認用に追加）は、
    // 1Y（最大365行）やALLでは画面に収まらない件数になり得るが、通常のColumnはLazyColumnと異なり
    // はみ出した子要素を自動でスクロール可能にはしない。実機でスクロールを試みても反応せず、
    // 画面に収まる範囲（chart等を含めて約20行程度）より下の一覧が操作上到達不能になっていた
    // （レビュー指摘の裏付け調査中に発見。件数が少ないSleepではクラッシュや例外は起きないが、
    // 一覧の大部分が見えないまま静かに機能しなくなっていた）。
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) {
            Text(text = stringResource(id = R.string.poc_sleep_back))
        }
        Text(text = stringResource(id = R.string.poc_sleep_graph_title), style = MaterialTheme.typography.titleLarge)

        OldestRecordInfo(oldestResult)

        SingleChoiceSegmentedButtonRow {
            SleepGraphPeriod.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = period == entry,
                    onClick = { period = entry },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = SleepGraphPeriod.entries.size),
                ) {
                    Text(text = stringResource(id = entry.labelRes()))
                }
            }
        }

        val currentLoad = aggregatesLoad
        val currentResult = if (currentLoad != null && currentLoad.period == period) currentLoad.result else null
        when (currentResult) {
            null -> {
                CircularProgressIndicator()
                Text(text = stringResource(id = R.string.poc_sleep_loading))
            }
            SleepAggregatesResult.Failure -> {
                Text(text = stringResource(id = R.string.poc_sleep_error))
                Button(onClick = { retryKey++ }) {
                    Text(text = stringResource(id = R.string.poc_sleep_retry))
                }
            }
            is SleepAggregatesResult.Success -> {
                Text(text = stringResource(id = period.aggregationLabelRes()))
                if (currentResult.historyLimited) {
                    Text(text = stringResource(id = R.string.poc_sleep_history_limited_notice))
                }
                SleepAggregateChart(buckets = currentResult.buckets, period = period)
                // WBS 5.1: 日付境界をまたぐSessionがbucketにどう配分されるかを、グラフの折れ線だけでなく
                // 数値でも確認できるようにする（Vicoのマーカーは長押し操作が必要で、adb/idb経由での
                // 確認・スクリーンショット読み取りがしづらいため。日々の合計時間を一覧できる方が
                // このPoCの検証目的に直接役立つ）。
                SleepBucketList(buckets = currentResult.buckets, period = period)
            }
        }
    }
}

@Composable
private fun OldestRecordInfo(oldestResult: OldestSleepSessionRecordResult?) {
    val locale = LocalLocale.current.platformLocale
    val text =
        when (oldestResult) {
            null -> stringResource(id = R.string.poc_sleep_loading)
            OldestSleepSessionRecordResult.Failure -> stringResource(id = R.string.poc_sleep_error)
            is OldestSleepSessionRecordResult.Success -> {
                val time = oldestResult.time
                if (time != null) {
                    val formattedDate =
                        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                            .withLocale(locale)
                            .format(time.atZone(ZoneId.systemDefault()).toLocalDate())
                    stringResource(id = R.string.poc_sleep_graph_oldest_record, formattedDate)
                } else {
                    stringResource(id = R.string.poc_sleep_graph_oldest_record_none)
                }
            }
        }
    Text(text = text, style = MaterialTheme.typography.bodySmall)
}

private fun SleepGraphPeriod.labelRes(): Int =
    when (this) {
        SleepGraphPeriod.ONE_MONTH -> R.string.poc_sleep_graph_period_1m
        SleepGraphPeriod.ONE_YEAR -> R.string.poc_sleep_graph_period_1y
        SleepGraphPeriod.ALL -> R.string.poc_sleep_graph_period_all
    }

private fun SleepGraphPeriod.aggregationLabelRes(): Int =
    when (this) {
        SleepGraphPeriod.ONE_MONTH, SleepGraphPeriod.ONE_YEAR -> R.string.poc_sleep_graph_aggregation_daily
        SleepGraphPeriod.ALL -> R.string.poc_sleep_graph_aggregation_monthly
    }

private fun SleepGraphPeriod.axisLabelFormatter(locale: Locale): DateTimeFormatter =
    when (this) {
        SleepGraphPeriod.ONE_MONTH, SleepGraphPeriod.ONE_YEAR -> DateTimeFormatter.ofPattern("M/d", locale)
        SleepGraphPeriod.ALL -> DateTimeFormatter.ofPattern("yyyy/M", locale)
    }

// SleepRawRecordsScreen.formatDuration()と同じ定義を複製している（private宣言のためファイルをまたいで
// 再利用できない。同ファイルの他の複製箇所と同じ理由、共通化はWBS 6.2で検討する）。
private fun formatDuration(duration: Duration): String {
    val totalMinutes = duration.toMinutes()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return String.format(Locale.ROOT, "%d:%02d", hours, minutes)
}

private data class SleepChartPoint(val x: Long, val hours: Double)

// Weight/Heart Rateの平均・最小・最大3系列と異なり、SLEEP_DURATION_TOTALは合計1系列のみ
// （requirements.md §22.2、公式の重複処理を経た値）。値がないbucketはプロットしない
// （前後の点が線でつながる。WeightAggregateChartと同じ扱い、lessons.md 7.2）。
@Composable
private fun SleepAggregateChart(buckets: List<SleepAggregateBucket>, period: SleepGraphPeriod, modifier: Modifier = Modifier) {
    val locale = LocalLocale.current.platformLocale
    val modelProducer = remember { CartesianChartModelProducer() }
    val points =
        remember(buckets, period) {
            buckets.mapNotNull { bucket ->
                bucket.totalSleepDuration?.let { duration ->
                    SleepChartPoint(x = period.xValue(bucket.periodStart), hours = duration.toMinutes() / 60.0)
                }
            }
        }

    if (points.isEmpty()) {
        Text(text = stringResource(id = R.string.poc_sleep_empty))
        return
    }

    LaunchedEffect(points) {
        modelProducer.runTransaction {
            lineModel {
                series(x = points.map { it.x }, y = points.map { it.hours })
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
}

// SleepAggregateChartと同じ対象（値のあるbucketのみ）を、日付+時間のテキスト一覧として表示する。
// ALL（月bucket）ではdateFromXValueが各月1日を返すため、月の合計として読む。
@Composable
private fun SleepBucketList(buckets: List<SleepAggregateBucket>, period: SleepGraphPeriod, modifier: Modifier = Modifier) {
    val locale = LocalLocale.current.platformLocale
    val rows =
        remember(buckets, period) {
            buckets.mapNotNull { bucket -> bucket.totalSleepDuration?.let { period.xValue(bucket.periodStart) to it } }
                .sortedByDescending { it.first }
        }
    if (rows.isEmpty()) return

    val formatter = remember(period, locale) { period.axisLabelFormatter(locale) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier) {
        Text(text = stringResource(id = R.string.poc_sleep_graph_bucket_list_title), style = MaterialTheme.typography.titleMedium)
        for ((x, duration) in rows) {
            Text(
                text = "${formatter.format(period.dateFromXValue(x))}  ${formatDuration(duration)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
