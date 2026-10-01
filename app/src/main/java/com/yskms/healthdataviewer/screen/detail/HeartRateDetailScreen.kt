package com.yskms.healthdataviewer.screen.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.health.connect.client.records.HeartRateRecord
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
import com.yskms.healthdataviewer.healthconnect.HeartRateAggregateBucket
import com.yskms.healthdataviewer.healthconnect.HeartRateAggregatesResult
import com.yskms.healthdataviewer.healthconnect.OldestRecordResult
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// WBS 6.2: poc/HeartRateGraphScreen（PoC 3）を置き換える正式なDetail画面。WeightDetailScreenと同じ
// 共通基盤を使う。HeartRateはMetricDensity.HIGH（1W/1Mは日bucket、3M/6M/1Yは週bucket、ALLは月bucket）。
// 経過時間の実測表示（PoCにあった「集計にかかった時間」テキスト）は正式画面には持ち込まない
// （決定事項6。粒度チューニングの計測は実装中にLogcatで確認した）。**週bucketへの粗粒度化は、
// このHeart Rateデータ（バックグラウンド継続記録）の所要時間をほとんど改善しなかった**
// （週bucketでも6ヶ月30秒・1年57秒、全期間96秒。lessons.md 6.12）。所要時間の主要因はbucket数では
// なく問い合わせ範囲の実データ量であり、bucket粒度調整だけでは解消できない既知の制約として
// 受け入れている（読み込み中はメインスレッドをブロックせず、クラッシュもしない）。
private val HEART_RATE_DENSITY = MetricDensity.HIGH

// WBS 6.3: RecordsタブのPagingConfig。Heart Rateは1レコードに複数サンプルを含み、継続記録する
// ソースではRaw全件読み込みが実機のヒープを枯渇させてOutOfMemoryErrorを起こした実例がある
// （lessons.md 6.7）。他3型より小さいpageSize/maxSizeにし、1回あたりの変換コストとメモリ上限を
// より保守的に抑える。具体的な値は実機で検証して調整する（docs/wbs.md 6.3の未決事項）。
private const val HEART_RATE_RECORDS_PAGE_SIZE = 20
private const val HEART_RATE_RECORDS_MAX_SIZE = 100

private data class HeartRateLoad(val result: HeartRateAggregatesResult, val granularity: BucketGranularity)

@Composable
fun HeartRateDetailScreen(
    healthConnectManager: HealthConnectManager,
    historyPermissionGranted: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(DetailTab.CHART) }
    var period by rememberSaveable { mutableStateOf(GraphPeriod.MONTH) }
    var retryKey by remember { mutableIntStateOf(0) }
    var customRange by rememberSaveable { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    var aggregatesLoad by remember { mutableStateOf<PeriodTaggedResult<HeartRateLoad>?>(null) }
    var oldestResult by remember { mutableStateOf<OldestRecordResult?>(null) }
    // WBS 6.4: Weight/Steps/SleepのSourcesタブと同じ理由でタブ切替のたびに再実行しない
    // （rememberLazyTabResult()参照、コードレビュー指摘）。Heart Rateは全件走査ではなくAggregate
    // 呼び出し（ソース数+1回）だが、タブを往復するたびに繰り返す必要はない。
    var sourcesRetryKey by remember { mutableIntStateOf(0) }
    val sourcesLoad =
        rememberLazyTabResult(active = tab == DetailTab.SOURCES, retryKey = sourcesRetryKey) {
            healthConnectManager.readHeartRateSourceSampleCounts(historyPermissionGranted)
        }

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
                            HeartRateLoad(
                                result = HeartRateAggregatesResult.Success(buckets = emptyList(), historyLimited = range.historyLimited),
                                granularity = BucketGranularity.DAY,
                            ),
                    )
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

    // WBS 6.5: WeightDetailScreenと同じ理由・同じ条件でChartタブのみ全画面化する（isLandscapeOrientation()
    // 参照）。
    val fullScreenChart = isLandscapeOrientation() && tab == DetailTab.CHART

    // WBS 6.3: WeightDetailScreenと同じ理由でルートをタブ＋Box(weight)構造にする。
    Column(modifier = modifier.fillMaxSize()) {
        if (!fullScreenChart) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onBack) {
                    Text(text = stringResource(id = R.string.detail_back))
                }
                Text(text = stringResource(id = R.string.detail_heart_rate_title), style = MaterialTheme.typography.titleLarge)

                OldestRecordInfo(oldestResult)

                DetailTabs(tab = tab, onTabChange = { tab = it })
            }
        }

        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val availableHeight = maxHeight
            when (tab) {
                DetailTab.CHART ->
                    ChartTabColumn(fullScreenChart = fullScreenChart) {
                        PeriodTabs(period = period, onPeriodChange = { period = it })
                        if (period == GraphPeriod.CUSTOM) {
                            CustomRangePicker(range = customRange, onRangeChange = { customRange = it })
                        }

                        val currentLoad = aggregatesLoad
                        val currentHeartRateLoad = if (currentLoad != null && currentLoad.period == period) currentLoad.result else null
                        when (val currentResult = currentHeartRateLoad?.result) {
                            null ->
                                if (period == GraphPeriod.CUSTOM && customRange == null) {
                                    Text(text = stringResource(id = R.string.detail_custom_pick_prompt))
                                } else {
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
                                HeartRateAggregateChart(
                                    buckets = currentResult.buckets,
                                    granularity = granularity,
                                    modifier = Modifier.fillMaxWidth().height(detailChartHeight(fullScreenChart, availableHeight)),
                                )
                            }
                        }
                    }
                DetailTab.RECORDS -> {
                    val pagingItems =
                        remember {
                            Pager(
                                PagingConfig(
                                    pageSize = HEART_RATE_RECORDS_PAGE_SIZE,
                                    // WeightDetailScreenのPagingConfigコメント参照（初回loadの既定値
                                    // pageSize * 3とページ境界をpageSizeに揃える理由、レビュー指摘）。
                                    initialLoadSize = HEART_RATE_RECORDS_PAGE_SIZE,
                                    maxSize = HEART_RATE_RECORDS_MAX_SIZE,
                                    enablePlaceholders = false,
                                ),
                            ) {
                                healthConnectManager.heartRateRecordsPagingSource(historyPermissionGranted)
                            }.flow
                        }.collectAsLazyPagingItems()
                    RecordsTab(
                        pagingItems = pagingItems,
                        historyLimited = !historyPermissionGranted,
                        rowContent = { record -> HeartRateRecordRow(record = record) },
                    )
                }
                DetailTab.SOURCES -> HeartRateSourcesTab(load = sourcesLoad, onRetry = { sourcesRetryKey++ })
            }
        }
    }
}

// 旧poc/HeartRateRawRecordsScreen.HeartRateRecordRow()と同じフォーマット（WBS 6.3で移植、元のPoC画面は削除）。
@Composable
private fun HeartRateRecordRow(record: HeartRateRecord) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val numberFormat = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val startZone = record.startZoneOffset ?: ZoneId.systemDefault()
    val endZone = record.endZoneOffset ?: ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
    val formattedStart = formatter.format(record.startTime.atZone(startZone))
    val formattedEnd = formatter.format(record.endTime.atZone(endZone))
    val timeRangeText = "$formattedStart – $formattedEnd"
    val sourceName =
        remember(record.metadata.dataOrigin.packageName) {
            DataOriginNameResolver.resolve(context, record.metadata.dataOrigin.packageName)
        }
    // WBS 6.3コードレビュー指摘: remember()していないと、再コンポーズ（スクロールでの再利用等）の
    // たびにsamples全件のaverage()を計算し直す。サンプル数が多いレコードではスクロールの負荷になりうる。
    val averageBpm = remember(record) { if (record.samples.isNotEmpty()) Math.round(record.samples.map { it.beatsPerMinute }.average()) else null }

    Column {
        Text(
            text =
                if (averageBpm != null) {
                    stringResource(
                        id = R.string.detail_heart_rate_record_summary,
                        timeRangeText,
                        record.samples.size,
                        numberFormat.format(averageBpm),
                    )
                } else {
                    stringResource(id = R.string.detail_heart_rate_record_summary_no_samples, timeRangeText, record.samples.size)
                },
        )
        Text(text = sourceName, style = MaterialTheme.typography.bodySmall)
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
        // WeightDetailScreen.WeightAggregateChart()と同じ理由（レビュー指摘）。
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(text = stringResource(id = R.string.detail_empty))
        }
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
            modifier = modifier.fillMaxWidth(),
        )
    }
    Text(
        text = stringResource(id = R.string.detail_heart_rate_measurement_count, numberFormat.format(totalMeasurementCount)),
        style = MaterialTheme.typography.bodySmall,
    )
}
