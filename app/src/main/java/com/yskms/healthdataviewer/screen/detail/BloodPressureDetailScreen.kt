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
import androidx.health.connect.client.records.BloodPressureRecord
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
import com.yskms.healthdataviewer.healthconnect.BloodPressureAggregateBucket
import com.yskms.healthdataviewer.healthconnect.BloodPressureAggregatesResult
import com.yskms.healthdataviewer.healthconnect.DataOriginNameResolver
import com.yskms.healthdataviewer.healthconnect.HealthConnectManager
import com.yskms.healthdataviewer.healthconnect.OldestRecordResult
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

// WBS 6.10（D-045）: WeightDetailScreen/RestingHeartRateDetailScreenと同じ共通基盤をそのまま踏襲する。
// BloodPressureRecordはWeightRecordと同じ単一時刻・単一値のレコード（サンプル配列を持たない、公式の
// 重複処理もない）で、血圧測定も体重と同様に1日数回程度の低頻度が一般的なため、MetricDensity.HIGH
// （HeartRateDetailScreen）ではなくWeightと同じLOWを使う。
private val BLOOD_PRESSURE_DENSITY = MetricDensity.LOW

// WBS 6.10: RecordsタブのPagingConfig。WeightDetailScreenと同じ理由・同じ値。
private const val BLOOD_PRESSURE_RECORDS_PAGE_SIZE = 50
private const val BLOOD_PRESSURE_RECORDS_MAX_SIZE = 200

private data class BloodPressureLoad(val result: BloodPressureAggregatesResult, val granularity: BucketGranularity)

@Composable
fun BloodPressureDetailScreen(
    healthConnectManager: HealthConnectManager,
    historyPermissionGranted: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(DetailTab.CHART) }
    var period by rememberSaveable { mutableStateOf(GraphPeriod.MONTH) }
    var retryKey by remember { mutableIntStateOf(0) }
    var customRange by rememberSaveable { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    var aggregatesLoad by remember { mutableStateOf<PeriodTaggedResult<BloodPressureLoad>?>(null) }
    var oldestResult by remember { mutableStateOf<OldestRecordResult?>(null) }
    // WeightDetailScreenと同じ理由（rememberLazyTabResult()参照、コードレビュー指摘）。
    var sourcesRetryKey by remember { mutableIntStateOf(0) }
    val sourcesLoad =
        rememberLazyTabResult(active = tab == DetailTab.SOURCES, retryKey = sourcesRetryKey) {
            healthConnectManager.readSourceRecordCounts(BloodPressureRecord::class, historyPermissionGranted)
        }

    LaunchedEffect(retryKey) {
        oldestResult = null
        oldestResult = healthConnectManager.findOldestBloodPressureRecordTime(historyPermissionGranted)
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
                                    BloodPressureLoad(
                                        result =
                                            BloodPressureAggregatesResult.Success(
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
                density = BLOOD_PRESSURE_DENSITY,
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
                            BloodPressureLoad(
                                result = BloodPressureAggregatesResult.Success(buckets = emptyList(), historyLimited = range.historyLimited),
                                granularity = BucketGranularity.DAY,
                            ),
                    )
            is DetailGraphRange.Resolved -> {
                aggregatesLoad = PeriodTaggedResult(period = period, result = null)
                val result =
                    healthConnectManager.readBloodPressureAggregates(
                        timeRangeFilter = range.timeRangeFilter,
                        bucket = range.granularity.bucketPeriod(),
                        historyPermissionGranted = historyPermissionGranted,
                    )
                aggregatesLoad =
                    PeriodTaggedResult(period = period, result = BloodPressureLoad(result = result, granularity = range.granularity))
            }
        }
    }

    // WeightDetailScreenと同じ理由・同じ条件（isLandscapeOrientation()参照）。
    val fullScreenChart = isLandscapeOrientation() && tab == DetailTab.CHART

    Column(modifier = modifier.fillMaxSize()) {
        if (!fullScreenChart) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onBack) {
                    Text(text = stringResource(id = R.string.detail_back))
                }
                Text(text = stringResource(id = R.string.detail_blood_pressure_title), style = MaterialTheme.typography.titleLarge)

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
                        val currentBloodPressureLoad = if (currentLoad != null && currentLoad.period == period) currentLoad.result else null
                        when (val currentResult = currentBloodPressureLoad?.result) {
                            null ->
                                if (period == GraphPeriod.CUSTOM && customRange == null) {
                                    Text(text = stringResource(id = R.string.detail_custom_pick_prompt))
                                } else {
                                    CircularProgressIndicator()
                                    Text(text = stringResource(id = R.string.detail_loading))
                                }
                            BloodPressureAggregatesResult.Failure -> {
                                Text(text = stringResource(id = R.string.detail_error))
                                Button(onClick = { retryKey++ }) {
                                    Text(text = stringResource(id = R.string.detail_retry))
                                }
                            }
                            is BloodPressureAggregatesResult.Success -> {
                                val granularity = currentBloodPressureLoad.granularity
                                Text(
                                    text =
                                        stringResource(
                                            id = R.string.detail_blood_pressure_aggregation_avg,
                                            stringResource(id = granularity.granularityLabelRes()),
                                        ),
                                )
                                if (currentResult.historyLimited) {
                                    Text(text = stringResource(id = R.string.detail_history_limited_notice))
                                }
                                BloodPressureAggregateChart(
                                    buckets = currentResult.buckets,
                                    granularity = granularity,
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
                                    pageSize = BLOOD_PRESSURE_RECORDS_PAGE_SIZE,
                                    // WeightDetailScreenのPagingConfigコメント参照（initialLoadSizeを
                                    // pageSizeに揃える理由、レビュー指摘）。
                                    initialLoadSize = BLOOD_PRESSURE_RECORDS_PAGE_SIZE,
                                    maxSize = BLOOD_PRESSURE_RECORDS_MAX_SIZE,
                                    enablePlaceholders = false,
                                ),
                            ) {
                                healthConnectManager.bloodPressureRecordsPagingSource(historyPermissionGranted)
                            }.flow
                        }.collectAsLazyPagingItems()
                    RecordsTab(
                        pagingItems = pagingItems,
                        historyLimited = !historyPermissionGranted,
                        rowContent = { record -> BloodPressureRecordRow(record = record) },
                    )
                }
                DetailTab.SOURCES -> RecordCountSourcesTab(load = sourcesLoad, onRetry = { sourcesRetryKey++ })
            }
        }
    }
}

// WeightDetailScreen.WeightRecordRow()と同じ形。systolic/diastolicはPressure型（inMillimetersOfMercury、
// Double）のため、臨床的な慣習（整数mmHg表記、「120/80」）に合わせて四捨五入して表示する
// （beatsPerMinuteのような整数型ではないが、実務上小数は意味を持たないため。D-045）。
@Composable
private fun BloodPressureRecordRow(record: BloodPressureRecord) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val zone = record.zoneOffset ?: ZoneId.systemDefault()
    val formattedDateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).format(record.time.atZone(zone))
    val sourceName =
        remember(record.metadata.dataOrigin.packageName) {
            DataOriginNameResolver.resolve(context, record.metadata.dataOrigin.packageName)
        }
    val systolic = record.systolic.inMillimetersOfMercury.roundToInt()
    val diastolic = record.diastolic.inMillimetersOfMercury.roundToInt()

    Column {
        Text(text = stringResource(id = R.string.detail_blood_pressure_record_summary, formattedDateTime, systolic, diastolic))
        Text(text = sourceName, style = MaterialTheme.typography.bodySmall)
    }
}

// WeightDetailScreen.ChartPointと同じ役割。systolic/diastolicの2系列（収縮期/拡張期の平均）を
// 1つのグラフに重ねる（BloodPressureAggregateBucketのコメント参照、D-045）。
private data class BloodPressureChartPoint(val x: Long, val systolic: Double, val diastolic: Double)

@Composable
private fun BloodPressureAggregateChart(
    buckets: List<BloodPressureAggregateBucket>,
    granularity: BucketGranularity,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val modelProducer = remember { CartesianChartModelProducer() }
    val points =
        remember(buckets, granularity) {
            buckets.mapNotNull { bucket ->
                val systolic = bucket.systolicAverage
                val diastolic = bucket.diastolicAverage
                if (systolic != null && diastolic != null) {
                    BloodPressureChartPoint(x = granularity.xValue(bucket.periodStart), systolic = systolic, diastolic = diastolic)
                } else {
                    null
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
        val xValues = points.map { it.x }
        modelProducer.runTransaction {
            lineModel {
                series(x = xValues, y = points.map { it.systolic })
                series(x = xValues, y = points.map { it.diastolic })
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
