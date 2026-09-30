package com.yskms.healthdataviewer.poc

import android.os.SystemClock
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
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.time.TimeRangeFilter
import com.yskms.healthdataviewer.R
import com.yskms.healthdataviewer.healthconnect.DataOriginNameResolver
import com.yskms.healthdataviewer.healthconnect.HealthConnectManager
import com.yskms.healthdataviewer.healthconnect.HealthConnectManager.Companion.HISTORY_FALLBACK_DAYS
import com.yskms.healthdataviewer.healthconnect.HealthConnectPermissions
import com.yskms.healthdataviewer.healthconnect.HeartRateRecordsResult
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

// WBS 4.1〜4.2（PoC 3）: Heart Rateで確認する内容は、大量Record・bucket集約・描画性能（4.1）と、
// ソース別件数の取得コスト・APIレート制限（4.2、PoC 2から持ち越し）。Heart Rateの公式Aggregateには
// Weightと同じく重複処理がなく（requirements.md §22.2）、Stepsのように単純合計と公式合計を
// 突き合わせる必要がないため、この画面はWeightRawRecordsScreenに近い設計にする。ただしHeart Rateは
// 「今日」だけでも大量件数になり得るため、期間選択（今日／過去7日間／全期間）はStepsPeriodのパターンを
// 踏襲する。WeightRawRecordsScreen/StepsScreenと同様、WBS 6.2/6.3の正式なDetail画面に置き換えられる
// 前提の簡略実装（画面回転時は再取得する。大量データでの再取得コスト自体がWBS 4.2の検証対象）。
private enum class HeartRatePeriod { TODAY, LAST_7_DAYS, ALL }

// StepsPeriod.timeRangeFilter()と同じロジック（HR単体では複数呼び出しの範囲一致は不要だが、
// 期間の決め方自体はStepsと揃える）。
private fun HeartRatePeriod.timeRangeFilter(now: Instant, historyPermissionGranted: Boolean): TimeRangeFilter =
    when (this) {
        HeartRatePeriod.TODAY ->
            TimeRangeFilter.between(now.atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant(), now)
        HeartRatePeriod.LAST_7_DAYS -> TimeRangeFilter.between(now.minus(7, ChronoUnit.DAYS), now)
        HeartRatePeriod.ALL ->
            if (historyPermissionGranted) {
                TimeRangeFilter.before(now)
            } else {
                TimeRangeFilter.between(now.minus(HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS), now)
            }
    }

private fun HeartRatePeriod.isHistoryLimited(historyPermissionGranted: Boolean): Boolean =
    this == HeartRatePeriod.ALL && !historyPermissionGranted

private fun HeartRatePeriod.labelRes(): Int =
    when (this) {
        HeartRatePeriod.TODAY -> R.string.poc_heart_rate_period_today
        HeartRatePeriod.LAST_7_DAYS -> R.string.poc_heart_rate_period_week
        HeartRatePeriod.ALL -> R.string.poc_heart_rate_period_all
    }

private data class HeartRateSourceSummary(val dataOrigin: DataOrigin, val recordCount: Int, val sampleCount: Int)

// WBS 4.2: ソース別件数は、Aggregate APIでは取れないため（requirements.md §22.3/§7.2）、
// 画面表示のために読み込み済みのRaw一覧をgroupByするだけで済ませる（Stepsのような追加のAggregate
// 呼び出しは不要。全件走査のコスト自体は、この一覧を読み込むreadHeartRateRecords()呼び出し
// そのものに現れる）。ソートはStepsのsummarizeBySource()（naiveSum降順）と同じ考え方で、
// このPoCが見たい実際のデータ量（サンプル数）が多いソース順にする。1レコードにまとめて
// 大量のサンプルを含めるソースもあり得るため、recordCount降順だと実際のデータ量と順位が
// 逆転し得る（レビュー指摘）。
private fun summarizeBySource(records: List<HeartRateRecord>): List<HeartRateSourceSummary> =
    records
        .groupBy { it.metadata.dataOrigin }
        .map { (origin, recs) -> HeartRateSourceSummary(origin, recs.size, recs.sumOf { it.samples.size }) }
        .sortedByDescending { it.sampleCount }

private data class HeartRateLoad(
    val records: List<HeartRateRecord>,
    val historyLimited: Boolean,
    val elapsedMillis: Long,
    // WBS 4.2: サンプル数上限に達し、途中で打ち切った結果であることを示す（HealthConnectManager.
    // HEART_RATE_RAW_SAMPLE_LIMIT、lessons.md 6.7）。trueの場合、recordsは全件ではない。
    val limitReached: Boolean,
)

private sealed interface HeartRateLoadOutcome {
    data class Success(val load: HeartRateLoad) : HeartRateLoadOutcome

    data object Failure : HeartRateLoadOutcome
}

// 表示中のperiodと非同期結果のperiodが一致するかを確認してから描画する
// （screen/detail/PeriodTaggedResult.ktと同じ理由、lessons.md 7.6）。
private data class PeriodLoad(val period: HeartRatePeriod, val outcome: HeartRateLoadOutcome?)

@Composable
fun HeartRateRawRecordsScreen(
    healthConnectManager: HealthConnectManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var period by rememberSaveable { mutableStateOf(HeartRatePeriod.LAST_7_DAYS) }
    var retryKey by remember { mutableIntStateOf(0) }
    var periodLoad by remember { mutableStateOf<PeriodLoad?>(null) }

    LaunchedEffect(period, retryKey) {
        periodLoad = PeriodLoad(period = period, outcome = null)

        // 権限の状態は保存した値ではなく毎回問い合わせる（lessons.md 3.1、StepsScreenのD-030(2)と同じ理由）。
        // ALLは大量データ・長時間になり得るため、表示中に履歴読み取り権限が取り消された場合の
        // 追従を優先する。
        val historyPermissionGranted =
            healthConnectManager.getGrantedPermissions()?.contains(HealthConnectPermissions.HISTORY_READ) == true

        val now = Instant.now()
        val filter = period.timeRangeFilter(now, historyPermissionGranted)
        // WBS 4.2: ソース別件数の取得コストの実測値を残すため、全件ページング読み込みの所要時間を計測する
        // （既存のPoC1/2には処理時間の計測がなかったため、ここで新規に追加）。
        val startElapsed = SystemClock.elapsedRealtime()
        val recordsResult = healthConnectManager.readHeartRateRecords(filter)
        val elapsedMillis = SystemClock.elapsedRealtime() - startElapsed

        val outcome =
            when (recordsResult) {
                is HeartRateRecordsResult.Success ->
                    HeartRateLoadOutcome.Success(
                        HeartRateLoad(
                            records = recordsResult.records,
                            historyLimited = period.isHistoryLimited(historyPermissionGranted),
                            elapsedMillis = elapsedMillis,
                            limitReached = false,
                        ),
                    )
                is HeartRateRecordsResult.LimitReached ->
                    HeartRateLoadOutcome.Success(
                        HeartRateLoad(
                            records = recordsResult.records,
                            historyLimited = period.isHistoryLimited(historyPermissionGranted),
                            elapsedMillis = elapsedMillis,
                            limitReached = true,
                        ),
                    )
                HeartRateRecordsResult.Failure -> HeartRateLoadOutcome.Failure
            }
        periodLoad = PeriodLoad(period = period, outcome = outcome)
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) {
            Text(text = stringResource(id = R.string.poc_heart_rate_back))
        }
        Text(text = stringResource(id = R.string.poc_heart_rate_title), style = MaterialTheme.typography.titleLarge)

        SingleChoiceSegmentedButtonRow {
            HeartRatePeriod.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = period == entry,
                    onClick = { period = entry },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = HeartRatePeriod.entries.size),
                ) {
                    Text(text = stringResource(id = entry.labelRes()))
                }
            }
        }

        val currentLoad = periodLoad
        val currentOutcome = if (currentLoad != null && currentLoad.period == period) currentLoad.outcome else null
        when (currentOutcome) {
            null -> {
                CircularProgressIndicator()
                Text(text = stringResource(id = R.string.poc_heart_rate_loading))
            }
            HeartRateLoadOutcome.Failure -> {
                Text(text = stringResource(id = R.string.poc_heart_rate_error))
                Button(onClick = { retryKey++ }) {
                    Text(text = stringResource(id = R.string.poc_heart_rate_retry))
                }
            }
            is HeartRateLoadOutcome.Success -> {
                HeartRateRawContent(load = currentOutcome.load)
            }
        }
    }
}

@Composable
private fun HeartRateRawContent(load: HeartRateLoad) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val numberFormat = remember(locale) { NumberFormat.getIntegerInstance(locale) }

    if (load.records.isEmpty()) {
        Text(text = stringResource(id = R.string.poc_heart_rate_empty))
        return
    }

    // bySourceはrecordsから導出できる純粋な計算（I/Oを伴わない）なので、非同期読み込み側の状態
    // （HeartRateLoad）には持たせず、totalSampleCountと同様にここで都度計算する（レビュー指摘）。
    val bySource = remember(load.records) { summarizeBySource(load.records) }
    val totalSampleCount = remember(bySource) { bySource.sumOf { it.sampleCount } }
    val appNames =
        remember(bySource) {
            bySource.associate { it.dataOrigin to DataOriginNameResolver.resolve(context, it.dataOrigin.packageName) }
        }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (load.historyLimited) {
                    Text(text = stringResource(id = R.string.poc_heart_rate_history_limited_notice))
                }
                if (load.limitReached) {
                    Text(text = stringResource(id = R.string.poc_heart_rate_limit_reached_notice))
                }
                Text(text = pluralStringResource(id = R.plurals.poc_heart_rate_record_count, count = load.records.size, load.records.size))
                Text(text = stringResource(id = R.string.poc_heart_rate_sample_count, numberFormat.format(totalSampleCount)))
                Text(text = stringResource(id = R.string.poc_heart_rate_elapsed_time, numberFormat.format(load.elapsedMillis)))
            }
        }
        item {
            Text(text = stringResource(id = R.string.poc_heart_rate_by_source_title), style = MaterialTheme.typography.titleMedium)
        }
        items(bySource, key = { it.dataOrigin.packageName }) { row ->
            Column {
                Text(text = appNames[row.dataOrigin].orEmpty(), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text =
                        stringResource(
                            id = R.string.poc_heart_rate_source_summary,
                            numberFormat.format(row.recordCount),
                            numberFormat.format(row.sampleCount),
                        ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            Text(text = stringResource(id = R.string.poc_heart_rate_raw_records_title), style = MaterialTheme.typography.titleMedium)
        }
        items(load.records, key = { it.metadata.id }) { record ->
            HeartRateRecordRow(
                record = record,
                sourceName = appNames[record.metadata.dataOrigin].orEmpty(),
                locale = locale,
                numberFormat = numberFormat,
            )
        }
    }
}

// FormatStyle.MEDIUM（秒まで表示）はStepsRecordRowと同じ理由（区間の重なりを誤読しないため）。
// 平均bpmは他の数値表示（件数・サンプル数・読み込み時間）と同じくnumberFormatでロケールに沿って整形し、
// 単位の"bpm"はKotlin側でハードコードせず文言リソース側に含める（他言語化の余地を残すため）。
@Composable
private fun HeartRateRecordRow(record: HeartRateRecord, sourceName: String, locale: Locale, numberFormat: NumberFormat) {
    val startZone = record.startZoneOffset ?: ZoneId.systemDefault()
    val endZone = record.endZoneOffset ?: ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
    val formattedStart = formatter.format(record.startTime.atZone(startZone))
    val formattedEnd = formatter.format(record.endTime.atZone(endZone))
    val timeRangeText = "$formattedStart – $formattedEnd"

    Column {
        Text(
            text =
                if (record.samples.isNotEmpty()) {
                    val averageBpm = Math.round(record.samples.map { it.beatsPerMinute }.average())
                    stringResource(
                        id = R.string.poc_heart_rate_record_summary,
                        timeRangeText,
                        record.samples.size,
                        numberFormat.format(averageBpm),
                    )
                } else {
                    stringResource(id = R.string.poc_heart_rate_record_summary_no_samples, timeRangeText, record.samples.size)
                },
        )
        Text(text = sourceName, style = MaterialTheme.typography.bodySmall)
    }
}
