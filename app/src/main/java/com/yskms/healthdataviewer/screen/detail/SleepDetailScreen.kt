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
import com.yskms.healthdataviewer.healthconnect.SleepAggregateBucket
import com.yskms.healthdataviewer.healthconnect.SleepAggregatesResult
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

// WBS 6.2: poc/SleepGraphScreen（PoC 4）を置き換える正式なDetail画面。SleepはMetricDensity.LOW
// （Sleep Sessionは1日1〜数件程度で記録頻度が低く、HeartRate/Stepsのような高頻度対策は不要という
// PoCでの判断を踏襲）。WEEK/MONTH bucket（3M/6M/1Y/ALL）では決定事項5により、SLEEP_DURATION_TOTAL
// （AWAKE区間除外・日付境界按分済み、D-032）の合計を実カバー日数で割った「1日あたり平均睡眠時間」に
// 正規化して表示する。これが要件§27「Sleepの月bucket（ALL）の集計方法が未定」の決着＝月合計ではなく
// 1日あたり平均にする（記録開始月・当月のような日数不足月が不自然に低く見える問題を解消する）。
private val SLEEP_DENSITY = MetricDensity.LOW

// oldestStartは、ALLの最初のbucketをdaysCoveredBy()で日数按分する際の下限クランプに使う
// （perDayDuration()参照）。ALL以外のperiodではnull（クランプ不要）。
private data class SleepLoad(val result: SleepAggregatesResult, val granularity: BucketGranularity, val oldestStart: LocalDateTime?)

@Composable
fun SleepDetailScreen(
    healthConnectManager: HealthConnectManager,
    historyPermissionGranted: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                density = SLEEP_DENSITY,
                now = LocalDateTime.now(),
                oldestStart = oldestStart,
                customRange = customRange,
            )
        when (range) {
            DetailGraphRange.Pending -> aggregatesLoad = PeriodTaggedResult(period = period, result = null)
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
                        result = SleepLoad(result = result, granularity = range.granularity, oldestStart = oldestStart),
                    )
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
        Text(text = stringResource(id = R.string.detail_sleep_title), style = MaterialTheme.typography.titleLarge)

        OldestRecordInfo(oldestResult)

        PeriodTabs(period = period, onPeriodChange = { period = it })
        if (period == GraphPeriod.CUSTOM) {
            CustomRangePicker(range = customRange, onRangeChange = { customRange = it })
        }

        val currentLoad = aggregatesLoad
        val currentSleepLoad = if (currentLoad != null && currentLoad.period == period) currentLoad.result else null
        when (val currentResult = currentSleepLoad?.result) {
            null -> {
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
                    if (granularity == BucketGranularity.DAY) R.string.detail_sleep_aggregation_total else R.string.detail_sleep_aggregation_avg_per_day
                Text(text = stringResource(id = aggregationLabelRes, stringResource(id = granularity.granularityLabelRes())))
                if (currentResult.historyLimited) {
                    Text(text = stringResource(id = R.string.detail_history_limited_notice))
                }
                SleepAggregateChart(buckets = currentResult.buckets, granularity = granularity, oldestStart = currentSleepLoad.oldestStart)
                // WBS 5.1: 日付境界をまたぐSessionがbucketにどう配分されるかを、グラフの折れ線だけでなく
                // 数値でも確認できるようにする（既存PoCから引き続き。Vicoのマーカーは長押し操作が必要）。
                SleepBucketList(buckets = currentResult.buckets, granularity = granularity, oldestStart = currentSleepLoad.oldestStart)
            }
        }
    }
}

// SleepRawRecordsScreen.formatDuration()と同じ定義（private宣言のためファイルをまたいで再利用でき
// ない。既存PoCから踏襲）。
private fun formatDuration(duration: Duration): String {
    val totalMinutes = duration.toMinutes()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return String.format(Locale.ROOT, "%d:%02d", hours, minutes)
}

// WEEK/MONTH bucketでは決定事項5により実カバー日数で割った1日あたり平均に正規化する
// （daysCoveredBy()、SumMetricNormalization.kt）。分未満は切り捨てる（表示はh:mm単位のため）。
//
// oldestStart: ALLの最初のbucketは、暦月境界に揃えるためのstartOfMonth()切り捨て（lessons.md 7.5）
// により、実際の最古レコードより前の日数まで含めた「その月の全日数」がperiodStart〜periodEndになる
// （例: 最古レコードが5/27でもbucketは5/1〜6/1の31日間になる）。この31日をそのまま分母にすると、
// 実際にはレコードが存在し得ない5/1〜5/26分も「記録なし」として平均に薄めて含めてしまい、
// 「日数不足月が不自然に低く見える」という決定事項5がそもそも解消したかった問題を、月初への
// 切り捨てが別の形で再現してしまう（レビューで発見）。oldestStartが分かっている場合は、
// bucket開始時刻をmaxOf(bucket.periodStart, oldestStart)にクランプしてから日数を数え、
// 実際にレコードが存在し得た範囲だけを分母にする。ALL以外のperiod・oldestStart不明時はnullのまま
// 従来通りbucket.periodStartを使う（このクランプは最初のbucketのみに影響し、以降のbucketは
// 通常periodStart >= oldestStartのため実質的に無効化される）。
private fun perDayDuration(
    bucket: SleepAggregateBucket,
    granularity: BucketGranularity,
    duration: Duration,
    oldestStart: LocalDateTime?,
): Duration =
    if (granularity == BucketGranularity.DAY) {
        duration
    } else {
        val effectiveStart = oldestStart?.let { maxOf(bucket.periodStart, it) } ?: bucket.periodStart
        duration.dividedBy(daysCoveredBy(effectiveStart, bucket.periodEnd))
    }

private data class SleepChartPoint(val x: Long, val hours: Double)

// SLEEP_DURATION_TOTALは合計1系列のみ（Weight/HeartRateの平均・最小・最大3系列と異なる、
// requirements.md §22.2）。値がないbucketはプロットしない（前後の点が線でつながる）。
@Composable
private fun SleepAggregateChart(
    buckets: List<SleepAggregateBucket>,
    granularity: BucketGranularity,
    oldestStart: LocalDateTime?,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val modelProducer = remember { CartesianChartModelProducer() }
    val points =
        remember(buckets, granularity, oldestStart) {
            buckets.mapNotNull { bucket ->
                bucket.totalSleepDuration?.let { duration ->
                    val normalized = perDayDuration(bucket, granularity, duration, oldestStart)
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
    oldestStart: LocalDateTime?,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val rows =
        remember(buckets, granularity, oldestStart) {
            buckets
                .mapNotNull { bucket ->
                    bucket.totalSleepDuration?.let { duration ->
                        granularity.xValue(bucket.periodStart) to perDayDuration(bucket, granularity, duration, oldestStart)
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
