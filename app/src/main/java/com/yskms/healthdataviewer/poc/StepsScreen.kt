package com.yskms.healthdataviewer.poc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.time.TimeRangeFilter
import com.yskms.healthdataviewer.R
import com.yskms.healthdataviewer.healthconnect.DataOriginNameResolver
import com.yskms.healthdataviewer.healthconnect.HealthConnectManager
import com.yskms.healthdataviewer.healthconnect.HealthConnectManager.Companion.HISTORY_FALLBACK_DAYS
import com.yskms.healthdataviewer.healthconnect.HealthConnectPermissions
import com.yskms.healthdataviewer.healthconnect.StepsAggregateTotalResult
import com.yskms.healthdataviewer.healthconnect.StepsRecordsResult
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

// WBS 3.1（PoC 2）: Stepsで確認する内容は、RawレコードとAggregate（公式）の差、複数Sourceの扱い、
// Health Connect公式の重複処理の結果（Steps=Activity系のため、Weight（PoC 1）とは異なり
// aggregate()に公式の重複処理が効く、requirements.md §22.2）。
// WeightRawRecordsScreenと同様、WBS 6.3/6.4の正式なDetail画面（Records/Sources）に置き換えられる
// 前提の簡略実装（画面回転時は再取得する）。WBS 6.2ではWeight/Steps/HeartRate/Sleepのグラフビューを
// screen/detail配下のDetail画面に置き換えたが、この比較PoC画面自体はグラフではないため対象外。
private enum class StepsPeriod { TODAY, LAST_7_DAYS, ALL }

// TODAY・LAST_7_DAYSは常に30日以内に収まるため、履歴読み取り権限の有無に関わらず同じ範囲になる。
// ALLだけがWeight同様、履歴権限の有無で開始日が変わる。ここで決めたfilterをRaw（readStepsRecords）と
// Aggregate（readStepsAggregateTotal、全ソース分・ソース単体分とも）の全呼び出しに共通で使う
// ことで、比較対象の範囲を完全に一致させている（HealthConnectManager.readStepsRecords()のコメント参照）。
// 終了側は必ず同じInstant（引数のnow）で明示的に閉じる（between(start, now)）。終了側を無制限
//（after()）にすると、Raw読み取り→全ソースAggregate→ソース別Aggregate…と複数回に分けて呼ぶ間に
// Health Connect側へ新しいレコードが書き込まれた場合、後続の呼び出しだけがそれを含んでしまい、
// 公式の重複処理とは無関係な差分が生じ得る（同じ`now`を使っていても、`after()`はその値を上限としては
// 使わない）。
//
// LAST_7_DAYSは暦日ではなく「現在時刻から168時間前」までの単純なローリングウィンドウ（このPoC専用の
// 簡略化。WBS 6.2でWeight等のDetail画面は暦ベースの計算に変更したが、この比較PoC画面はWBS 6.3/6.4で
// 置き換えられるまでこの簡略化のままにする）。
private fun StepsPeriod.timeRangeFilter(now: Instant, historyPermissionGranted: Boolean): TimeRangeFilter =
    when (this) {
        StepsPeriod.TODAY -> TimeRangeFilter.between(now.atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant(), now)
        StepsPeriod.LAST_7_DAYS -> TimeRangeFilter.between(now.minus(7, ChronoUnit.DAYS), now)
        StepsPeriod.ALL ->
            if (historyPermissionGranted) {
                TimeRangeFilter.before(now)
            } else {
                TimeRangeFilter.between(now.minus(HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS), now)
            }
    }

private fun StepsPeriod.isHistoryLimited(historyPermissionGranted: Boolean): Boolean =
    this == StepsPeriod.ALL && !historyPermissionGranted

private fun StepsPeriod.labelRes(): Int =
    when (this) {
        StepsPeriod.TODAY -> R.string.poc_steps_period_today
        StepsPeriod.LAST_7_DAYS -> R.string.poc_steps_period_week
        StepsPeriod.ALL -> R.string.poc_steps_period_all
    }

// 1ソース分の比較結果。naiveSumはRaw画面と同じ「重複排除なしの単純合計」（そのソースの分だけ）、
// officialTotalはそのソース単体を指定したAggregate（dataOriginFilter = そのソースのみ）の結果。
// 実機では、単一ソースだけを指定した場合でもnaiveSumとofficialTotalが一致しないことがある
// （lessons.md 6.6）。zeroLengthCount/zeroLengthSum・overlappingCount/overlappingSum・
// estimatedOfficialTotalは、いずれも原因切り分け用の診断値（下記関数のコメント参照）。
private data class StepsSourceRow(
    val dataOrigin: DataOrigin,
    val recordCount: Int,
    val naiveSum: Long,
    val officialTotal: Long?,
    val officialTotalFailed: Boolean,
    val zeroLengthCount: Int,
    val zeroLengthSum: Long,
    val overlappingCount: Int,
    val overlappingSum: Long,
    val estimatedOfficialTotal: Long,
)

private data class StepsComparison(
    val records: List<StepsRecord>,
    val naiveSum: Long,
    val historyLimited: Boolean,
    val bySource: List<StepsSourceRow>,
    val combinedOfficialTotal: Long?,
    val combinedOfficialTotalFailed: Boolean,
)

private sealed interface StepsComparisonResult {
    data class Success(val comparison: StepsComparison) : StepsComparisonResult

    data object Failure : StepsComparisonResult
}

// 表示中のperiodと非同期結果のperiodが一致するかを確認してから描画する
// （WeightGraphScreenのAggregatesLoadと同じ理由、lessons.md 7.6）。
private data class ComparisonLoad(val period: StepsPeriod, val result: StepsComparisonResult?)

private data class RawSourceSummary(
    val dataOrigin: DataOrigin,
    val recordCount: Int,
    val naiveSum: Long,
    val zeroLengthCount: Int,
    val zeroLengthSum: Long,
    val overlappingCount: Int,
    val overlappingSum: Long,
    val estimatedOfficialTotal: Long,
)

// startTime昇順に並べ、それまでに見た区間の終了時刻の最大値（runningMaxEnd）より前に始まる
// レコードを「重なっている」とみなす（区間スケジューリングの標準的な走査）。同一ソース内でRawの
// 区間同士がInstant精度で実際に重なっているかどうかを確認するための診断（lessons.md 6.6）。
// ただしこの合計（overlappingSum）は、重なっているレコードの歩数を全額計上するため、実際の
// naiveSum - officialTotalよりかなり大きくなる。「重なりの有無」の確認にとどまり、差の大きさを
// 説明する指標ではない（下のunionDurationSeconds()／estimatedOfficialTotalの方が定量的）。
private fun countOverlapping(records: List<StepsRecord>): Pair<Int, Long> {
    var runningMaxEnd: Instant? = null
    var count = 0
    var sum = 0L
    for (record in records.sortedBy { it.startTime }) {
        val maxEnd = runningMaxEnd
        if (maxEnd != null && record.startTime < maxEnd) {
            count++
            sum += record.count
        }
        if (maxEnd == null || record.endTime > maxEnd) runningMaxEnd = record.endTime
    }
    return count to sum
}

// 区間の和集合の長さ（ミリ秒）。重なっている部分は1回だけ数える（標準的な区間マージ）。
// ミリ秒単位で計算する。秒単位（Duration.seconds）だとレコードごとに1秒未満の端数を切り捨てるため、
// 大量の短いレコードがあるとtotalDurationMillis側の切り捨て誤差が蓄積し、
// unionDurationMillis / totalDurationMillisの比が1を超えかねない（union は物理的にtotal以下のはず）。
private fun unionDurationMillis(records: List<StepsRecord>): Long {
    var union = 0L
    var mergedStart: Instant? = null
    var mergedEnd: Instant? = null
    for (record in records.sortedBy { it.startTime }) {
        val end = mergedEnd
        if (end == null || record.startTime > end) {
            if (mergedStart != null && end != null) union += Duration.between(mergedStart, end).toMillis()
            mergedStart = record.startTime
            mergedEnd = record.endTime
        } else if (record.endTime > end) {
            mergedEnd = record.endTime
        }
    }
    if (mergedStart != null && mergedEnd != null) union += Duration.between(mergedStart, mergedEnd).toMillis()
    return union
}

// 各レコードの歩数がその区間の長さに比例して一様に発生していると仮定し、単純合計に
// 「区間の和集合の長さ ÷ 区間の延べ長さ」（＝重なっていない時間の割合）を掛けた推定値。
// 秒単位で実際に重なっている区間をHealth Connectが時間按分で除去しているなら、この推定値は
// 実際のofficialTotalに近づくはず（lessons.md 6.6）。overlappingSumと違い、
// 差の「大きさ」を定量的に検証できる。
// 既知の限界: レコードの区間をfilterの範囲（TODAY・LAST_7_DAYSでは境界がある）に切り詰めていない。
// 境界をまたぐレコードがあると、その分だけ推定値がずれ得る（ALLは開始側無制限のため影響を受けない）。
// 実際に確認した範囲ではこの影響は小さかったが、厳密にするには呼び出し元のfilterを受け取り、
// 各レコードのstartTime/endTimeをfilterの範囲にクランプしてから計算する必要がある（未対応）。
private fun estimateOfficialTotal(records: List<StepsRecord>, naiveSum: Long): Long {
    val totalMillis = records.sumOf { Duration.between(it.startTime, it.endTime).toMillis() }
    if (totalMillis <= 0) return naiveSum
    val unionMillis = unionDurationMillis(records)
    return Math.round(naiveSum.toDouble() * unionMillis / totalMillis)
}

// zeroLength判定はstartTime == endTime（開始＝終了、区間の長さ0）のレコード。いずれもlessons.md 6.6の
// 原因切り分け用の診断値。
private fun summarizeBySource(records: List<StepsRecord>): List<RawSourceSummary> =
    records
        .groupBy { it.metadata.dataOrigin }
        .map { (origin, recs) ->
            val zeroLength = recs.filter { it.startTime == it.endTime }
            val (overlappingCount, overlappingSum) = countOverlapping(recs)
            val naiveSum = recs.sumOf { it.count }
            RawSourceSummary(
                dataOrigin = origin,
                recordCount = recs.size,
                naiveSum = naiveSum,
                zeroLengthCount = zeroLength.size,
                zeroLengthSum = zeroLength.sumOf { it.count },
                overlappingCount = overlappingCount,
                overlappingSum = overlappingSum,
                estimatedOfficialTotal = estimateOfficialTotal(recs, naiveSum),
            )
        }
        .sortedByDescending { it.naiveSum }

@Composable
fun StepsScreen(
    healthConnectManager: HealthConnectManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var period by rememberSaveable { mutableStateOf(StepsPeriod.LAST_7_DAYS) }
    var retryKey by remember { mutableIntStateOf(0) }
    var comparisonLoad by remember { mutableStateOf<ComparisonLoad?>(null) }

    LaunchedEffect(period, retryKey) {
        comparisonLoad = ComparisonLoad(period = period, result = null)

        // 権限の状態は保存した値ではなく毎回問い合わせる（lessons.md 3.1、D-030(2)）。D-029でSecurityException
        // 時の自動フォールバックをあえて持たせていないため、これを怠ると、表示中に履歴読み取り権限を
        // 取り消された場合に「全期間」が失敗し続け、再試行しても同じ古い判定のままになる。
        // getGrantedPermissions()が問い合わせ自体に失敗した場合（null）も安全側（未許可）として扱うが、
        // その場合historyLimitedの表示文言（「履歴読み取り権限がない」）は、実際の原因（問い合わせ失敗）と
        // 厳密には一致しない。PoCとして許容し、正式なDetail画面（WBS 6.2/6.3）では区別を検討する。
        val historyPermissionGranted =
            healthConnectManager.getGrantedPermissions()?.contains(HealthConnectPermissions.HISTORY_READ) == true

        val now = Instant.now()
        val filter = period.timeRangeFilter(now, historyPermissionGranted)
        val recordsResult = healthConnectManager.readStepsRecords(filter)

        val result =
            when (recordsResult) {
                is StepsRecordsResult.Success -> {
                    val combinedTotalResult = healthConnectManager.readStepsAggregateTotal(filter)
                    val bySource =
                        summarizeBySource(recordsResult.records).map { raw ->
                            val sourceTotalResult = healthConnectManager.readStepsAggregateTotal(filter, setOf(raw.dataOrigin))
                            StepsSourceRow(
                                dataOrigin = raw.dataOrigin,
                                recordCount = raw.recordCount,
                                naiveSum = raw.naiveSum,
                                officialTotal = (sourceTotalResult as? StepsAggregateTotalResult.Success)?.total,
                                officialTotalFailed = sourceTotalResult is StepsAggregateTotalResult.Failure,
                                zeroLengthCount = raw.zeroLengthCount,
                                zeroLengthSum = raw.zeroLengthSum,
                                overlappingCount = raw.overlappingCount,
                                overlappingSum = raw.overlappingSum,
                                estimatedOfficialTotal = raw.estimatedOfficialTotal,
                            )
                        }
                    StepsComparisonResult.Success(
                        StepsComparison(
                            records = recordsResult.records,
                            naiveSum = recordsResult.records.sumOf { it.count },
                            historyLimited = period.isHistoryLimited(historyPermissionGranted),
                            bySource = bySource,
                            combinedOfficialTotal = (combinedTotalResult as? StepsAggregateTotalResult.Success)?.total,
                            combinedOfficialTotalFailed = combinedTotalResult is StepsAggregateTotalResult.Failure,
                        ),
                    )
                }
                StepsRecordsResult.Failure -> StepsComparisonResult.Failure
            }
        comparisonLoad = ComparisonLoad(period = period, result = result)
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) {
            Text(text = stringResource(id = R.string.poc_steps_back))
        }
        Text(text = stringResource(id = R.string.poc_steps_title), style = MaterialTheme.typography.titleLarge)

        SingleChoiceSegmentedButtonRow {
            StepsPeriod.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = period == entry,
                    onClick = { period = entry },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = StepsPeriod.entries.size),
                ) {
                    Text(text = stringResource(id = entry.labelRes()))
                }
            }
        }

        val currentLoad = comparisonLoad
        val currentResult = if (currentLoad != null && currentLoad.period == period) currentLoad.result else null
        when (currentResult) {
            null -> {
                CircularProgressIndicator()
                Text(text = stringResource(id = R.string.poc_steps_loading))
            }
            StepsComparisonResult.Failure -> {
                Text(text = stringResource(id = R.string.poc_steps_error))
                Button(onClick = { retryKey++ }) {
                    Text(text = stringResource(id = R.string.poc_steps_retry))
                }
            }
            is StepsComparisonResult.Success -> {
                StepsComparisonContent(comparison = currentResult.comparison)
            }
        }
    }
}

@Composable
private fun StepsComparisonContent(comparison: StepsComparison) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val numberFormat = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val appNames =
        remember(comparison.bySource) {
            comparison.bySource.associate { it.dataOrigin to DataOriginNameResolver.resolve(context, it.dataOrigin.packageName) }
        }

    if (comparison.records.isEmpty()) {
        Text(text = stringResource(id = R.string.poc_steps_empty))
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (comparison.historyLimited) {
                    Text(text = stringResource(id = R.string.poc_steps_history_limited_notice))
                }
                Text(text = pluralStringResource(id = R.plurals.poc_steps_record_count, count = comparison.records.size, comparison.records.size))
                Text(text = stringResource(id = R.string.poc_steps_raw_sum, numberFormat.format(comparison.naiveSum)))
                Text(
                    text =
                        stringResource(
                            id = R.string.poc_steps_official_total,
                            formatOptionalTotal(comparison.combinedOfficialTotal, comparison.combinedOfficialTotalFailed, numberFormat),
                        ),
                )
                DedupSummary(comparison = comparison, numberFormat = numberFormat)
            }
        }
        item {
            Text(text = stringResource(id = R.string.poc_steps_by_source_title), style = MaterialTheme.typography.titleMedium)
        }
        items(comparison.bySource, key = { it.dataOrigin.packageName }) { row ->
            StepsSourceRowContent(row = row, sourceName = appNames[row.dataOrigin].orEmpty(), numberFormat = numberFormat)
        }
        item {
            Text(text = stringResource(id = R.string.poc_steps_raw_records_title), style = MaterialTheme.typography.titleMedium)
        }
        items(comparison.records, key = { it.metadata.id }) { record ->
            StepsRecordRow(
                record = record,
                sourceName = appNames[record.metadata.dataOrigin].orEmpty(),
                locale = locale,
                numberFormat = numberFormat,
            )
        }
    }
}

// WBS 3.1: Steps（Activity系）は、複数ソースにまたがる重複をHealth Connectがaggregate()側で除去し得る
// （requirements.md §22.2）。ただし単純合計と公式合計の差を無条件に「複数ソースの重複除去」と言い切らない
// （lessons.md 6.6、単一ソース内の重複区間が原因である可能性も高い）。公式合計が単純合計を上回るケース
// （本来想定しない結果）も「一致」と誤表示しないよう、一致・下回る・上回るの3通りに分けて表示する。
// 取得できていない（Failure）場合は比較自体を示さない。
@Composable
private fun DedupSummary(comparison: StepsComparison, numberFormat: NumberFormat) {
    val officialTotal = comparison.combinedOfficialTotal
    if (comparison.combinedOfficialTotalFailed || officialTotal == null) return

    val diff = comparison.naiveSum - officialTotal
    val text =
        when {
            diff > 0 -> stringResource(id = R.string.poc_steps_diff_official_lower, numberFormat.format(diff))
            diff < 0 -> stringResource(id = R.string.poc_steps_diff_official_higher, numberFormat.format(-diff))
            else -> stringResource(id = R.string.poc_steps_diff_match)
        }
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun StepsSourceRowContent(row: StepsSourceRow, sourceName: String, numberFormat: NumberFormat) {
    Column {
        Text(text = sourceName, style = MaterialTheme.typography.bodyLarge)
        Text(
            text =
                stringResource(
                    id = R.string.poc_steps_source_summary,
                    numberFormat.format(row.recordCount),
                    numberFormat.format(row.naiveSum),
                    formatOptionalTotal(row.officialTotal, row.officialTotalFailed, numberFormat),
                ),
            style = MaterialTheme.typography.bodySmall,
        )
        if (row.zeroLengthCount > 0) {
            Text(
                text =
                    stringResource(
                        id = R.string.poc_steps_zero_length,
                        numberFormat.format(row.zeroLengthCount),
                        numberFormat.format(row.zeroLengthSum),
                    ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (row.overlappingCount > 0) {
            Text(
                text =
                    stringResource(
                        id = R.string.poc_steps_overlapping,
                        numberFormat.format(row.overlappingCount),
                        numberFormat.format(row.overlappingSum),
                    ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(id = R.string.poc_steps_estimated_official, numberFormat.format(row.estimatedOfficialTotal)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// FormatStyle.MEDIUM（秒まで表示、WeightRawRecordsScreenと同じスタイル）を使う。PoCの目的の1つが
// レコード同士の区間が実際に重なっているかどうかの確認であり、分単位（SHORT）だと本来重ならない
// 区間（例: 12:31:00–12:31:30と12:31:30–12:33:00）が同じ表示になり誤読を招く。
@Composable
private fun StepsRecordRow(record: StepsRecord, sourceName: String, locale: Locale, numberFormat: NumberFormat) {
    val startZone = record.startZoneOffset ?: ZoneId.systemDefault()
    val endZone = record.endZoneOffset ?: ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
    val formattedStart = formatter.format(record.startTime.atZone(startZone))
    val formattedEnd = formatter.format(record.endTime.atZone(endZone))

    Column {
        Text(text = "$formattedStart – $formattedEnd  ${numberFormat.format(record.count)}")
        Text(text = sourceName, style = MaterialTheme.typography.bodySmall)
    }
}

// totalがnullなのは、このAggregate呼び出しの範囲・ソース指定でHealth Connectが「データなし」と
// 返した場合（AggregationResult.get()の仕様）。bySource/combinedOfficialTotalの計算対象は必ず
// 実際にRawレコードが存在する範囲・ソースなので、ここでnullが返ること自体が本来想定しない結果であり、
// 「0」と表示して埋めてしまわずFailureと同じ「取得できず」の表示にする（実機確認で見つけたいPoCの
// 対象そのもののため、隠さず区別する）。
private fun formatOptionalTotal(total: Long?, failed: Boolean, numberFormat: NumberFormat): String =
    if (failed || total == null) "—" else numberFormat.format(total)
