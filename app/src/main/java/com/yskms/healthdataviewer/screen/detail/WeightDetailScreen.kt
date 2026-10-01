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
import androidx.health.connect.client.records.WeightRecord
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
import com.yskms.healthdataviewer.healthconnect.OldestRecordResult
import com.yskms.healthdataviewer.healthconnect.WeightAggregateBucket
import com.yskms.healthdataviewer.healthconnect.WeightAggregatesResult
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// WBS 6.2: poc/WeightGraphScreen（PoC 1〜2）を置き換える正式なDetail画面。bucket境界・periodタグ付け
// パターンはscreen/detail配下の共通ファイル（GraphPeriod/BucketGranularity/MetricDensity/
// DetailGraphRange/PeriodTaggedResult）に一本化した。WeightはMetricDensity.LOW
// （1W/1M/3M/6M/1Yは日bucket、ALLは月bucket。既存PoCの閾値と同じ）。
private val WEIGHT_DENSITY = MetricDensity.LOW

// WBS 6.3: RecordsタブのPagingConfig。Weightは記録頻度が低く、OOM対策（lessons.md 6.7）が
// 必須というほどではないが、他3型と一貫させるため同じ仕組みでページングする。maxSizeは
// Paging3の制約（maxSize >= pageSize + 2 * prefetchDistance、prefetchDistance既定値はpageSizeと同値）
// を満たす値にする。具体的な値は実機で検証して調整する（docs/wbs.md 6.3の未決事項）。
private const val WEIGHT_RECORDS_PAGE_SIZE = 50
private const val WEIGHT_RECORDS_MAX_SIZE = 200

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
    var tab by rememberSaveable { mutableStateOf(DetailTab.CHART) }
    var period by rememberSaveable { mutableStateOf(GraphPeriod.MONTH) }
    var retryKey by remember { mutableIntStateOf(0) }
    var customRange by rememberSaveable { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    var aggregatesLoad by remember { mutableStateOf<PeriodTaggedResult<WeightLoad>?>(null) }
    // WBS 2.3: ALLの開始日を決めるための最古のレコード時刻。選択中の期間に関わらず一度だけ取得する。
    var oldestResult by remember { mutableStateOf<OldestRecordResult?>(null) }
    // WBS 6.4: Sourcesタブは全件走査1回分のIPCを発行するため、タブ切替のたびに再実行しない
    // （rememberLazyTabResult()参照、コードレビュー指摘）。
    var sourcesRetryKey by remember { mutableIntStateOf(0) }
    val sourcesLoad =
        rememberLazyTabResult(active = tab == DetailTab.SOURCES, retryKey = sourcesRetryKey) {
            healthConnectManager.readSourceRecordCounts(WeightRecord::class, historyPermissionGranted)
        }

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

    // WBS 6.5: 要件§11「横向きにすると画面幅を最大限使った長期グラフを表示する」の対象はChartタブに
    // 限定する（Records/Sourcesタブは横向きでも現状のヘッダー付きレイアウトのまま）。ヘッダー
    // （戻るボタン・タイトル・最古レコード情報・タブ切替）を畳んで戻る手段を失うが、Android標準の
    // システム戻る操作（ジェスチャー／ボタン）で画面を抜けられるため、専用の戻るUIは設けない。
    val fullScreenChart = isLandscapeOrientation() && tab == DetailTab.CHART

    // WBS 6.3: Chart（verticalScroll付きColumn、ChartTabColumn参照）とRecords（LazyColumn）はスクロール
    // 戦略が異なるため、同じColumnに両方を入れられない（verticalScrollの親は子に無限大の高さ制約を与え、
    // 内側のLazyColumnが測定できずクラッシュ、または全件を一度にコンポーズしてしまいPaging3の目的が
    // 無効化される）。タブ切替部分の下をBox(Modifier.weight(1f))で高さ確定した領域にし、タブごとに別の
    // スクロール可能コンポーザブルを切り替えて配置する。
    Column(modifier = modifier.fillMaxSize()) {
        if (!fullScreenChart) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onBack) {
                    Text(text = stringResource(id = R.string.detail_back))
                }
                Text(text = stringResource(id = R.string.detail_weight_title), style = MaterialTheme.typography.titleLarge)

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
                                WeightAggregateChart(
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
                    // タブ切替ごとにPagerを作り直す（＝Records再訪時はページ0から再表示）。このremember
                    // はDetailTab.RECORDSがコンポジションに存在する間だけ生存し、Chartタブに切り替えると
                    // 破棄される（画面回転時に状態を保持せず再取得する既存の前例（旧poc/WeightRawRecordsScreen、
                    // WBS 6.3で削除）と一貫した、最も単純な選択。D-035(4)参照）。
                    val pagingItems =
                        remember {
                            Pager(
                                PagingConfig(
                                    pageSize = WEIGHT_RECORDS_PAGE_SIZE,
                                    // 初回load（REFRESH）の既定値はpageSize * 3で、2ページ目以降のappend/prepend
                                    // のloadSizeより大きい。これを指定しないと、初回で読んだ「ページ0」が
                                    // pageSize * 3件分のレコードを含むのに対し、maxSizeで破棄された後の
                                    // prepend再読み込みはpageSize件しか読まず、pageStartTokens[1]が指す
                                    // 境界との間で差分レコードが欠落する（レビュー指摘、HealthRecordsPagingSource
                                    // の「1ページ = 1 loadSize」という前提を崩さないため揃える）。
                                    initialLoadSize = WEIGHT_RECORDS_PAGE_SIZE,
                                    maxSize = WEIGHT_RECORDS_MAX_SIZE,
                                    enablePlaceholders = false,
                                ),
                            ) {
                                healthConnectManager.weightRecordsPagingSource(historyPermissionGranted)
                            }.flow
                        }.collectAsLazyPagingItems()
                    RecordsTab(
                        pagingItems = pagingItems,
                        historyLimited = !historyPermissionGranted,
                        rowContent = { record -> WeightRecordRow(record = record) },
                    )
                }
                DetailTab.SOURCES -> RecordCountSourcesTab(load = sourcesLoad, onRetry = { sourcesRetryKey++ })
            }
        }
    }
}

// 旧poc/WeightRawRecordsScreen.WeightRecordsList()の行表示と同じフォーマット（WBS 6.3で移植、元のPoC画面は削除）。
@Composable
private fun WeightRecordRow(record: WeightRecord) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val zone = record.zoneOffset ?: ZoneId.systemDefault()
    val formattedDateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).format(record.time.atZone(zone))
    // Raw画面は重複も含めて見せることが目的（D-007）のため、わずかな値の違いが丸めで同じに見えないよう、
    // 小数1桁ではなく2桁で表示する（既存PoCから踏襲）。
    val formattedWeight = String.format(locale, "%.2f kg", record.weight.inKilograms)
    val sourceName =
        remember(record.metadata.dataOrigin.packageName) {
            DataOriginNameResolver.resolve(context, record.metadata.dataOrigin.packageName)
        }

    Column {
        Text(text = "$formattedDateTime  $formattedWeight")
        Text(text = sourceName, style = MaterialTheme.typography.bodySmall)
    }
}

// 1点分のプロット対象。average/min/maxのいずれかが欠けているbucketは3系列とも対象から外す
// （既存PoCと同じ理由）。
private data class ChartPoint(val x: Long, val average: Double, val min: Double, val max: Double)

// WBS 2.2: 同日複数レコードのグラフ上の扱いはD-027の通り、平均を主系列、最小・最大を補助的な折れ線
// として重ねて描画し、値がないbucketはプロットしない（前後の点が線でつながる）。
@Composable
private fun WeightAggregateChart(
    buckets: List<WeightAggregateBucket>,
    granularity: BucketGranularity,
    modifier: Modifier = Modifier,
) {
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
        // 呼び出し元から渡されるmodifier（高さ指定）をデータなし表示にも適用し、グラフと同じ領域の
        // 中央に表示する（レビュー指摘。fillMaxWidth()が無いと文字列の幅だけのBoxになり、
        // contentAlignment=Centerが縦方向にしか効かず左寄りに見えてしまう）。
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
}
