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
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
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
import com.yskms.healthdataviewer.healthconnect.NutritionAggregateBucket
import com.yskms.healthdataviewer.healthconnect.NutritionAggregatesResult
import com.yskms.healthdataviewer.healthconnect.OldestRecordResult
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// WBS 6.13（Nutrition追加、D-054）: TotalCaloriesDetailScreenと同じ基盤（Chartはエネルギー1系列の
// 合計/1日あたり平均、RecordsタブはPaging、Sourcesタブは全件走査）をそのまま踏襲する。食事記録は
// Weight/BloodPressureと同じ「1日数回程度の手動入力」という想定でMetricDensity.LOWを選んだが、
// BloodPressureのD-045(2)と同じ理由でこの想定は実データで確認していない（要検証）。
private val NUTRITION_DENSITY = MetricDensity.LOW

// WBS 6.13: RecordsタブのPagingConfig。TotalCaloriesDetailScreenと同じ値。
private const val NUTRITION_RECORDS_PAGE_SIZE = 50
private const val NUTRITION_RECORDS_MAX_SIZE = 200

// NutritionRecordの個々の値（energy/protein/totalFat/totalCarbohydrate/dietaryFiber）は
// 書き込み元アプリが入力していなければnull（javap逆コンパイルで確認、D-054）。他の単一値データ型の
// ような「レコードが無ければ非表示」ではなく、レコード自体はあるのに一部の値だけが無い状態のため、
// その項目だけをこのプレースホルダーで示す（翻訳不要な記号のため文字列リソース化していない）。
private const val NUTRITION_VALUE_UNKNOWN = "—"

private data class NutritionLoad(
    val result: NutritionAggregatesResult,
    val granularity: BucketGranularity,
    val denominatorFloor: LocalDateTime?,
)

@Composable
fun NutritionDetailScreen(
    healthConnectManager: HealthConnectManager,
    historyPermissionGranted: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(DetailTab.CHART) }
    var period by rememberSaveable { mutableStateOf(GraphPeriod.MONTH) }
    var retryKey by remember { mutableIntStateOf(0) }
    var customRange by rememberSaveable { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    var aggregatesLoad by remember { mutableStateOf<PeriodTaggedResult<NutritionLoad>?>(null) }
    var oldestResult by remember { mutableStateOf<OldestRecordResult?>(null) }
    // TotalCaloriesDetailScreenと同じ理由（rememberLazyTabResult()参照）。
    var sourcesRetryKey by remember { mutableIntStateOf(0) }
    val sourcesLoad =
        rememberLazyTabResult(active = tab == DetailTab.SOURCES, retryKey = sourcesRetryKey) {
            healthConnectManager.readSourceRecordCounts(NutritionRecord::class, historyPermissionGranted)
        }

    LaunchedEffect(retryKey) {
        oldestResult = null
        oldestResult = healthConnectManager.findOldestNutritionRecordTime(historyPermissionGranted)
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
                                    NutritionLoad(
                                        result =
                                            NutritionAggregatesResult.Success(
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
                density = NUTRITION_DENSITY,
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
                            NutritionLoad(
                                result = NutritionAggregatesResult.Success(buckets = emptyList(), historyLimited = range.historyLimited),
                                granularity = BucketGranularity.DAY,
                                denominatorFloor = null,
                            ),
                    )
            is DetailGraphRange.Resolved -> {
                aggregatesLoad = PeriodTaggedResult(period = period, result = null)
                val result =
                    healthConnectManager.readNutritionAggregates(
                        timeRangeFilter = range.timeRangeFilter,
                        bucket = range.granularity.bucketPeriod(),
                        historyPermissionGranted = historyPermissionGranted,
                    )
                aggregatesLoad =
                    PeriodTaggedResult(
                        period = period,
                        result = NutritionLoad(result = result, granularity = range.granularity, denominatorFloor = range.denominatorFloor),
                    )
            }
        }
    }

    // TotalCaloriesDetailScreenと同じ理由・同じ条件（isLandscapeOrientation()参照）。
    val fullScreenChart = isLandscapeOrientation() && tab == DetailTab.CHART

    Column(modifier = modifier.fillMaxSize()) {
        if (!fullScreenChart) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onBack) {
                    Text(text = stringResource(id = R.string.detail_back))
                }
                Text(text = stringResource(id = R.string.detail_nutrition_title), style = MaterialTheme.typography.titleLarge)

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
                        val currentNutritionLoad = if (currentLoad != null && currentLoad.period == period) currentLoad.result else null
                        when (val currentResult = currentNutritionLoad?.result) {
                            null ->
                                if (period == GraphPeriod.CUSTOM && customRange == null) {
                                    Text(text = stringResource(id = R.string.detail_custom_pick_prompt))
                                } else {
                                    CircularProgressIndicator()
                                    Text(text = stringResource(id = R.string.detail_loading))
                                }
                            NutritionAggregatesResult.Failure -> {
                                Text(text = stringResource(id = R.string.detail_error))
                                Button(onClick = { retryKey++ }) {
                                    Text(text = stringResource(id = R.string.detail_retry))
                                }
                            }
                            is NutritionAggregatesResult.Success -> {
                                val granularity = currentNutritionLoad.granularity
                                val aggregationLabelRes =
                                    if (granularity == BucketGranularity.DAY) {
                                        R.string.detail_nutrition_aggregation_total
                                    } else {
                                        R.string.detail_nutrition_aggregation_avg_per_day
                                    }
                                Text(text = stringResource(id = aggregationLabelRes, stringResource(id = granularity.granularityLabelRes())))
                                if (currentResult.historyLimited) {
                                    Text(text = stringResource(id = R.string.detail_history_limited_notice))
                                }
                                NutritionAggregateChart(
                                    buckets = currentResult.buckets,
                                    granularity = granularity,
                                    denominatorFloor = currentNutritionLoad.denominatorFloor,
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
                                    pageSize = NUTRITION_RECORDS_PAGE_SIZE,
                                    // WeightDetailScreenのPagingConfigコメント参照（初回loadの既定値
                                    // pageSize * 3とページ境界をpageSizeに揃える理由、レビュー指摘）。
                                    initialLoadSize = NUTRITION_RECORDS_PAGE_SIZE,
                                    maxSize = NUTRITION_RECORDS_MAX_SIZE,
                                    enablePlaceholders = false,
                                ),
                            ) {
                                healthConnectManager.nutritionRecordsPagingSource(historyPermissionGranted)
                            }.flow
                        }.collectAsLazyPagingItems()
                    RecordsTab(
                        pagingItems = pagingItems,
                        historyLimited = !historyPermissionGranted,
                        rowContent = { record -> NutritionRecordRow(record = record) },
                    )
                }
                DetailTab.SOURCES -> RecordCountSourcesTab(load = sourcesLoad, onRetry = { sourcesRetryKey++ })
            }
        }
    }
}

// NutritionRecord.mealTypeの5種類の定数を文言へ変換する。ExerciseDetailScreen.exerciseTypeLabelRes()
// と同じ考え方（未知の値はmeal_type_unknownへフォールバック）だが、mealTypeは約60種類もあるexerciseType
// と異なり5種類のみ（javap逆コンパイルで確認、D-054）。
private fun mealTypeLabelRes(mealType: Int): Int =
    when (mealType) {
        MealType.MEAL_TYPE_BREAKFAST -> R.string.meal_type_breakfast
        MealType.MEAL_TYPE_LUNCH -> R.string.meal_type_lunch
        MealType.MEAL_TYPE_DINNER -> R.string.meal_type_dinner
        MealType.MEAL_TYPE_SNACK -> R.string.meal_type_snack
        else -> R.string.meal_type_unknown
    }

// TotalCaloriesDetailScreen.TotalCaloriesRecordRow()と同じ形だが、表示する値が多い（D-054で絞った
// エネルギー・タンパク質・脂質・炭水化物・食物繊維の5項目）ため2行に分けている。名前（record.name）が
// 空ならmealTypeの文言にフォールバックする。各値は書き込み元アプリが入力していなければnullのため、
// NUTRITION_VALUE_UNKNOWNで個別に示す（レコード自体を非表示にはしない）。
@Composable
private fun NutritionRecordRow(record: NutritionRecord) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val decimalFormat =
        remember(locale) {
            NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = 1
                maximumFractionDigits = 1
            }
        }
    val integerFormat = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val startZone = record.startZoneOffset ?: ZoneId.systemDefault()
    val endZone = record.endZoneOffset ?: ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
    val formattedStart = formatter.format(record.startTime.atZone(startZone))
    val formattedEnd = formatter.format(record.endTime.atZone(endZone))
    val mealTypeLabel = stringResource(id = mealTypeLabelRes(record.mealType))
    val nameOrMealType = record.name?.takeIf { it.isNotBlank() } ?: mealTypeLabel
    val energyText = record.energy?.let { integerFormat.format(it.inKilocalories) } ?: NUTRITION_VALUE_UNKNOWN
    val proteinText = record.protein?.let { decimalFormat.format(it.inGrams) } ?: NUTRITION_VALUE_UNKNOWN
    val fatText = record.totalFat?.let { decimalFormat.format(it.inGrams) } ?: NUTRITION_VALUE_UNKNOWN
    val carbText = record.totalCarbohydrate?.let { decimalFormat.format(it.inGrams) } ?: NUTRITION_VALUE_UNKNOWN
    val fiberText = record.dietaryFiber?.let { decimalFormat.format(it.inGrams) } ?: NUTRITION_VALUE_UNKNOWN
    val sourceName =
        remember(record.metadata.dataOrigin.packageName) {
            DataOriginNameResolver.resolve(context, record.metadata.dataOrigin.packageName)
        }

    Column {
        Text(text = stringResource(id = R.string.detail_nutrition_record_header, formattedStart, formattedEnd, nameOrMealType))
        Text(
            text = stringResource(id = R.string.detail_nutrition_record_nutrients, energyText, proteinText, fatText, carbText, fiberText),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(text = sourceName, style = MaterialTheme.typography.bodySmall)
    }
}

private data class NutritionChartPoint(val x: Long, val kilocalories: Double)

// TotalCaloriesDetailScreen.TotalCaloriesAggregateChart()と同じ形（合計1系列のみ）。複数栄養素を
// 1つのグラフに重ねない理由はNutritionAggregatesResult.ktのコメント参照（D-054、D-045(3)と同じ考え方）。
@Composable
private fun NutritionAggregateChart(
    buckets: List<NutritionAggregateBucket>,
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
                    NutritionChartPoint(x = granularity.xValue(bucket.periodStart), kilocalories = value)
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
