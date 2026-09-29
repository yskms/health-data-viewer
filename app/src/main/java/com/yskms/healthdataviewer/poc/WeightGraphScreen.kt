package com.yskms.healthdataviewer.poc

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
import com.yskms.healthdataviewer.healthconnect.OldestWeightRecordResult
import com.yskms.healthdataviewer.healthconnect.WeightAggregateBucket
import com.yskms.healthdataviewer.healthconnect.WeightAggregatesResult
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

// WBS 2.2〜2.4（PoC 1）専用のグラフ画面。WeightRawRecordsScreen（WBS 2.1）と同様、
// WBS 6.2の正式なDetail画面に置き換えられる前提の簡略実装（画面回転時は再取得する）。
// 1M/1Yは暦月・暦年ではなく単純に30日／365日で近似している（例: 2月を含む1Mでも常に30日）。
// PoCとしては十分だが、MVPで暦月・暦年に合わせるかはWBS 6.2で決める。
private enum class BucketGranularity { DAY, MONTH }

private enum class GraphPeriod(val bucket: Period, val granularity: BucketGranularity) {
    ONE_MONTH(bucket = Period.ofDays(1), granularity = BucketGranularity.DAY),
    ONE_YEAR(bucket = Period.ofDays(1), granularity = BucketGranularity.DAY),
    ALL(bucket = Period.ofMonths(1), granularity = BucketGranularity.MONTH),
}

// readWeightAggregates()はLocalDateTimeベースのTimeRangeFilterを要求する
// （HealthConnectManager.readWeightAggregates()のコメント、lessons.md 6.5参照）。
// ALLはoldestStart（WBS 2.3で取得した最古レコード時刻）が分かるまで呼び出し側で待ち、
// 分かった時点でそれを開始日として使う。未確定のまま開始無制限で問い合わせると、
// 実データがない期間も含めて1970年から大量のbucketを要求してしまう可能性があるため
// （レビュー指摘、要検証）。呼び出し元（WeightGraphScreen）は、oldestStartがまだ分からない
// 間はこの関数を呼ばず待ち、「0件と確定した」場合はAggregate API自体を呼ばずに空の結果を
// 直接返す。そのためoldestStartがnullでここに来るのは、最古レコードの取得自体が失敗
// （OldestWeightRecordResult.Failure）した場合のみで、その場合は開始無制限にフォールバックする
// （TimeRangeFilter.between(x, x)はend must be after startで例外になるため、nullと現在時刻を
// 渡すことはできない。エミュレータでこの例外を確認済み、lessons.md 7.4。Pixel 11実機では未確認）。
//
// aggregateGroupByPeriod()はtimeRangeFilterの開始時刻そのものからPeriod単位で区切る
// （時刻部分を含めて等間隔に区切るだけで、暦日・暦月の境界に自動的には合わせてくれない）。
// そのため開始時刻を渡す前に日初／月初へ切り捨てる（startOfDay/startOfMonth）。これを怠ると、
// 例えば「14:23」に問い合わせた場合、各bucketが「14:23〜翌14:23」になり、同じ暦日の朝と夜の
// 記録が別のbucketに分かれてしまい、D-027で決めた「同日複数レコードをbucket集計でまとめる」
// 前提が崩れる（レビュー指摘）。月初への切り捨ては、最古レコードのタイムゾーン変換
// （下記WeightGraphScreen参照）に起因するずれを吸収する副次効果もあるが、根本的な解決では
// ない（要検証）。この切り捨てだけで実際に暦日・暦月区切りになるかは実データでの確認が必要
// （要検証。エミュレータにはWeightレコードがなく、bucket境界を実データで確認できていない）。
private fun GraphPeriod.timeRangeFilter(now: LocalDateTime, oldestStart: LocalDateTime?): TimeRangeFilter =
    when (this) {
        GraphPeriod.ONE_MONTH -> TimeRangeFilter.between(startOfDay(now.minus(30, ChronoUnit.DAYS)), now)
        GraphPeriod.ONE_YEAR -> TimeRangeFilter.between(startOfDay(now.minus(365, ChronoUnit.DAYS)), now)
        GraphPeriod.ALL ->
            if (oldestStart != null) {
                TimeRangeFilter.between(startOfMonth(oldestStart), now)
            } else {
                TimeRangeFilter.before(now)
            }
    }

private fun startOfDay(dateTime: LocalDateTime): LocalDateTime = dateTime.toLocalDate().atStartOfDay()

private fun startOfMonth(dateTime: LocalDateTime): LocalDateTime = dateTime.toLocalDate().withDayOfMonth(1).atStartOfDay()

// bucketの実際の並び・欠落有無（Health Connectが値のないbucketも1件として返すか、
// 何も返さないか）に依存しないよう、x軸の値はbucket配列の位置ではなく、各bucketの
// periodStartから計算した絶対値（DAYはepoch day、MONTHはepoch monthに相当する値）にする。
// これにより、必要ならbottom軸のvalueFormatter側でも同じ計算の逆変換で日付を再構成でき、
// bucket一覧への参照を持たずに済む（レビュー指摘）。
private fun GraphPeriod.xValue(dateTime: LocalDateTime): Long =
    when (granularity) {
        BucketGranularity.DAY -> dateTime.toLocalDate().toEpochDay()
        BucketGranularity.MONTH -> YearMonth.from(dateTime).let { it.year * 12L + it.monthValue - 1 }
    }

private fun GraphPeriod.dateFromXValue(x: Long): LocalDate =
    when (granularity) {
        BucketGranularity.DAY -> LocalDate.ofEpochDay(x)
        BucketGranularity.MONTH -> YearMonth.of((x / 12).toInt(), (x % 12).toInt() + 1).atDay(1)
    }

// 集計結果に、それがどのperiodの問い合わせで得られたかをタグ付けする。resultがnullは
// そのperiodを読み込み中であることを示す。periodを切り替えた直後、LaunchedEffectが
// aggregatesLoadを更新するまでの間（少なくとも1フレーム）は前のperiodのタグが残るため、
// 表示側でperiod一致を確認してから使う（レビュー指摘。以前は単なる`WeightAggregatesResult?`
// だったため、切り替え直後に前のperiodのbucketを新しいperiodの軸・ラベルで描画してしまい、
// 特に日bucket→月bucketの切り替えでは複数の日が同じx値に潰れてVicoに渡る不具合があった）。
private data class AggregatesLoad(val period: GraphPeriod, val result: WeightAggregatesResult?)

@Composable
fun WeightGraphScreen(
    healthConnectManager: HealthConnectManager,
    historyPermissionGranted: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var period by rememberSaveable { mutableStateOf(GraphPeriod.ONE_MONTH) }
    var retryKey by remember { mutableIntStateOf(0) }
    var aggregatesLoad by remember { mutableStateOf<AggregatesLoad?>(null) }
    // WBS 2.3: ALLの開始日を決めるための最古のレコード時刻。選択中の期間に関わらず一度だけ取得する。
    var oldestResult by remember { mutableStateOf<OldestWeightRecordResult?>(null) }

    LaunchedEffect(retryKey) {
        oldestResult = null
        oldestResult = healthConnectManager.findOldestWeightRecordTime(historyPermissionGranted)
    }

    // ALLだけがoldestResultの確定を待つ必要がある。1M/1YでもoldestResultをそのままキーに使うと、
    // oldestResultがnull→結果ありに変わるたびに1M/1Yの集計まで無駄に再問い合わせしてしまうため
    // （レビュー指摘）、period != ALLのときは変化しない値（null）をキーに使う。
    val oldestGateForAll = if (period == GraphPeriod.ALL) oldestResult else null

    LaunchedEffect(period, retryKey, oldestGateForAll) {
        if (period == GraphPeriod.ALL) {
            when (val currentOldest = oldestGateForAll) {
                null -> {
                    // oldestResultが確定するまでALLの範囲を決められないため待つ。待っている間も
                    // 現在のperiodでタグ付けした「読み込み中」にしておく（レビュー指摘。以前は
                    // ここで何も更新しなかったため、再試行直後にALLを選ぶと古いFailure表示が
                    // 残ったまま読み込み中に切り替わらないことがあった）。
                    aggregatesLoad = AggregatesLoad(period = period, result = null)
                    return@LaunchedEffect
                }
                is OldestWeightRecordResult.Success ->
                    if (currentOldest.time == null) {
                        // 読み取れる範囲にレコードが1件もないと確定している。Aggregate API自体を呼ばず、
                        // 空の結果を直接返す（TimeRangeFilter.between(now, now)はend must be after startで
                        // 例外になるため、範囲が決まらないまま呼び出すことはできない。lessons.md 7.4）。
                        aggregatesLoad =
                            AggregatesLoad(
                                period = period,
                                result = WeightAggregatesResult.Success(buckets = emptyList(), historyLimited = !historyPermissionGranted),
                            )
                        return@LaunchedEffect
                    }
                OldestWeightRecordResult.Failure -> Unit // 開始日不明のまま、開始無制限にフォールバックする
            }
        }
        aggregatesLoad = AggregatesLoad(period = period, result = null)
        val oldestStart =
            (oldestGateForAll as? OldestWeightRecordResult.Success)?.time
                ?.let { LocalDateTime.ofInstant(it, ZoneId.systemDefault()) }
        val result =
            healthConnectManager.readWeightAggregates(
                timeRangeFilter = period.timeRangeFilter(now = LocalDateTime.now(), oldestStart = oldestStart),
                bucket = period.bucket,
                historyPermissionGranted = historyPermissionGranted,
            )
        aggregatesLoad = AggregatesLoad(period = period, result = result)
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) {
            Text(text = stringResource(id = R.string.poc_weight_back))
        }
        Text(text = stringResource(id = R.string.poc_weight_graph_title), style = MaterialTheme.typography.titleLarge)

        OldestRecordInfo(oldestResult)

        SingleChoiceSegmentedButtonRow {
            GraphPeriod.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = period == entry,
                    onClick = { period = entry },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = GraphPeriod.entries.size),
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
                Text(text = stringResource(id = R.string.poc_weight_loading))
            }
            WeightAggregatesResult.Failure -> {
                Text(text = stringResource(id = R.string.poc_weight_error))
                Button(onClick = { retryKey++ }) {
                    Text(text = stringResource(id = R.string.poc_weight_retry))
                }
            }
            is WeightAggregatesResult.Success -> {
                Text(text = stringResource(id = period.aggregationLabelRes()))
                if (currentResult.historyLimited) {
                    Text(text = stringResource(id = R.string.poc_weight_history_limited_notice))
                }
                WeightAggregateChart(buckets = currentResult.buckets, period = period)
            }
        }
    }
}

@Composable
private fun OldestRecordInfo(oldestResult: OldestWeightRecordResult?) {
    val locale = LocalLocale.current.platformLocale
    // 集計（aggregatesLoad）とは別に、最古レコードの取得だけが読み込み中／失敗の状態を
    // 画面から分かるようにする（レビュー指摘。以前はSuccess以外で何も表示していなかった）。
    val text =
        when (oldestResult) {
            null -> stringResource(id = R.string.poc_weight_loading)
            OldestWeightRecordResult.Failure -> stringResource(id = R.string.poc_weight_error)
            is OldestWeightRecordResult.Success -> {
                val time = oldestResult.time
                if (time != null) {
                    val formattedDate =
                        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                            .withLocale(locale)
                            .format(time.atZone(ZoneId.systemDefault()).toLocalDate())
                    stringResource(id = R.string.poc_weight_graph_oldest_record, formattedDate)
                } else {
                    stringResource(id = R.string.poc_weight_graph_oldest_record_none)
                }
            }
        }
    Text(text = text, style = MaterialTheme.typography.bodySmall)
}

private fun GraphPeriod.labelRes(): Int =
    when (this) {
        GraphPeriod.ONE_MONTH -> R.string.poc_weight_graph_period_1m
        GraphPeriod.ONE_YEAR -> R.string.poc_weight_graph_period_1y
        GraphPeriod.ALL -> R.string.poc_weight_graph_period_all
    }

private fun GraphPeriod.aggregationLabelRes(): Int =
    when (this) {
        GraphPeriod.ONE_MONTH, GraphPeriod.ONE_YEAR -> R.string.poc_weight_graph_aggregation_daily
        GraphPeriod.ALL -> R.string.poc_weight_graph_aggregation_monthly
    }

private fun GraphPeriod.axisLabelFormatter(locale: Locale): DateTimeFormatter =
    when (this) {
        GraphPeriod.ONE_MONTH, GraphPeriod.ONE_YEAR -> DateTimeFormatter.ofPattern("M/d", locale)
        GraphPeriod.ALL -> DateTimeFormatter.ofPattern("yyyy/M", locale)
    }

// 1点分のプロット対象。average/min/maxのいずれかが欠けているbucketは、Health Connectの
// AggregationResult.get()の仕様上ありうる組み合わせ（平均だけ取れて最小・最大が取れない等）を
// 安全側に倒し、3系列とも対象から外す（レビュー指摘。以前は`!!`でaverageが非nullならmin/maxも
// 非nullと仮定していた）。
private data class ChartPoint(val x: Long, val average: Double, val min: Double, val max: Double)

// WBS 2.2: 同日複数レコードのグラフ上の扱いはD-027の通り、アプリ独自に平均／最新値を選ばず、
// Health Connect公式のWeight Aggregate Metric（WEIGHT_AVG/MIN/MAX）をbucket集計して使う
// （HealthConnectManager.readWeightAggregates()）。ここでは平均を折れ線、最小・最大を
// 補助的な折れ線として重ねて描画し、値がないbucketはプロットしない（前後の点が線でつながる。
// 「隙間を明示するか」はMVPで別途検討する、requirements.md §10）。
//
// WBS 2.4: Vicoの評価。ProvideVicoTheme(rememberM3VicoTheme())でアプリのMaterial3カラースキーム
// （ライト/ダーク、要件§12）が軸・線の既定色に自動反映される。CartesianChartHostは既定で
// 横スクロール・ピンチズームに対応しており（VicoScrollState/VicoZoomState）、10年規模のALL
// グラフでも個別の実装なしで操作できる見込み（実際の操作感は要検証、lessons.md 7.3）。
@Composable
private fun WeightAggregateChart(buckets: List<WeightAggregateBucket>, period: GraphPeriod, modifier: Modifier = Modifier) {
    val locale = LocalLocale.current.platformLocale
    val modelProducer = remember { CartesianChartModelProducer() }
    val points =
        remember(buckets, period) {
            buckets.mapNotNull { bucket ->
                val average = bucket.average
                val min = bucket.min
                val max = bucket.max
                if (average != null && min != null && max != null) {
                    ChartPoint(x = period.xValue(bucket.periodStart), average = average, min = min, max = max)
                } else {
                    null
                }
            }
        }

    if (points.isEmpty()) {
        Text(text = stringResource(id = R.string.poc_weight_empty))
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
}
