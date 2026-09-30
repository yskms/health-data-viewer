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
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.time.TimeRangeFilter
import com.yskms.healthdataviewer.R
import com.yskms.healthdataviewer.healthconnect.DataOriginNameResolver
import com.yskms.healthdataviewer.healthconnect.HealthConnectManager
import com.yskms.healthdataviewer.healthconnect.HealthConnectManager.Companion.HISTORY_FALLBACK_DAYS
import com.yskms.healthdataviewer.healthconnect.HealthConnectPermissions
import com.yskms.healthdataviewer.healthconnect.SleepSessionRecordsResult
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

// WBS 5.1（PoC 4）: Sleepで確認する内容はSession/Stage、Source priority（複数ソースが重なる場合の扱い）、
// 日付境界をまたぐSession、可視化（requirements.md §26）。この画面はSession/Stageの生データと
// ソース別の内訳（Source priorityを見る材料）を担当し、公式Aggregateの集計結果（日付境界の扱いを含む）は
// SleepGraphScreenで確認する。StepsScreen/HeartRateRawRecordsScreenと同様、期間選択
// （今日／過去7日間／全期間）はStepsPeriodのパターンを踏襲し、WBS 6.2/6.3の正式なDetail画面に
// 置き換えられる前提の簡略実装（画面回転時は再取得する）。
private enum class SleepPeriod { TODAY, LAST_7_DAYS, ALL }

// StepsPeriod.timeRangeFilter()/HeartRatePeriod.timeRangeFilter()と同じロジック。TODAYは「今日の0時〜今」の
// 範囲だが、Sleep Sessionは前日夜に始まり今日開始時刻をまたぐことが多いため、TODAYで前夜からの睡眠が
// 一部しか見えない（またはまったく見えない）ことがあり得る点に注意。`readRecords()`が区間の重なりで
// 返すのか開始時刻の含有で返すのかによって挙動が変わるはずだが、PoC 4の検証時点では対象となる実例
// （日付境界をまたぐSessionがある日にTODAYを選ぶケース）が手元になく、未検証のまま残っている
// （requirements.md §27）。
private fun SleepPeriod.timeRangeFilter(now: Instant, historyPermissionGranted: Boolean): TimeRangeFilter =
    when (this) {
        SleepPeriod.TODAY ->
            TimeRangeFilter.between(now.atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant(), now)
        SleepPeriod.LAST_7_DAYS -> TimeRangeFilter.between(now.minus(7, ChronoUnit.DAYS), now)
        SleepPeriod.ALL ->
            if (historyPermissionGranted) {
                TimeRangeFilter.before(now)
            } else {
                TimeRangeFilter.between(now.minus(HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS), now)
            }
    }

private fun SleepPeriod.isHistoryLimited(historyPermissionGranted: Boolean): Boolean =
    this == SleepPeriod.ALL && !historyPermissionGranted

private fun SleepPeriod.labelRes(): Int =
    when (this) {
        SleepPeriod.TODAY -> R.string.poc_sleep_period_today
        SleepPeriod.LAST_7_DAYS -> R.string.poc_sleep_period_week
        SleepPeriod.ALL -> R.string.poc_sleep_period_all
    }

// H:MM形式（例: "7:23"）。既存のWeightRawRecordsScreen（kg表示）と同じく、時間・分の単語を
// 言語ごとに用意せず、String.formatで直接組み立てる。分は2桁固定（"7:03"）にして、時間の桁数だけ
// ロケールにかかわらず可変にする。
private fun formatDuration(duration: Duration): String {
    val totalMinutes = duration.toMinutes()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return String.format(Locale.ROOT, "%d:%02d", hours, minutes)
}

private fun stageTypeLabelRes(stageType: Int): Int =
    when (stageType) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> R.string.poc_sleep_stage_awake
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> R.string.poc_sleep_stage_sleeping
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> R.string.poc_sleep_stage_out_of_bed
        SleepSessionRecord.STAGE_TYPE_LIGHT -> R.string.poc_sleep_stage_light
        SleepSessionRecord.STAGE_TYPE_DEEP -> R.string.poc_sleep_stage_deep
        SleepSessionRecord.STAGE_TYPE_REM -> R.string.poc_sleep_stage_rem
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> R.string.poc_sleep_stage_awake_in_bed
        else -> R.string.poc_sleep_stage_unknown
    }

// 表示順は「覚醒・段階不明系」を先に、「眠っている系」を後に置く固定順（durationの大小ではなく、
// Stage定数の並び方に近い意味的な順序。Stage型が示す意味自体はrequirements.md §26のSource priorityとは
// 独立した公式の分類）。
private val STAGE_DISPLAY_ORDER =
    listOf(
        SleepSessionRecord.STAGE_TYPE_AWAKE,
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
        SleepSessionRecord.STAGE_TYPE_SLEEPING,
        SleepSessionRecord.STAGE_TYPE_LIGHT,
        SleepSessionRecord.STAGE_TYPE_DEEP,
        SleepSessionRecord.STAGE_TYPE_REM,
        SleepSessionRecord.STAGE_TYPE_UNKNOWN,
    )

// 1つのSession内のstagesを型ごとに合計する。stagesが空のSession（Stageを持たないソースもあり得る）は
// 空のMapになり、呼び出し側で「段階データなし」として扱う。
private fun stageTotals(stages: List<SleepSessionRecord.Stage>): Map<Int, Duration> =
    stages
        .groupBy { it.stage }
        .mapValues { (_, list) -> list.fold(Duration.ZERO) { acc, stage -> acc + Duration.between(stage.startTime, stage.endTime) } }

// naiveTotalDuration: Session区間（startTime〜endTime）の単純合計（重複排除なし）。StepsScreenのnaiveSumと
// 同じ考え方で、Source priority（複数ソースが同じ夜を別々に記録した場合にどう見えるか）を確認する材料にする。
// 実際の公式集計（SLEEP_DURATION_TOTAL、重複処理あり）はSleepGraphScreen側で別途確認する。
private data class SleepSourceSummary(val dataOrigin: DataOrigin, val sessionCount: Int, val naiveTotalDuration: Duration)

private fun summarizeBySource(records: List<SleepSessionRecord>): List<SleepSourceSummary> =
    records
        .groupBy { it.metadata.dataOrigin }
        .map { (origin, recs) ->
            SleepSourceSummary(
                dataOrigin = origin,
                sessionCount = recs.size,
                naiveTotalDuration = recs.fold(Duration.ZERO) { acc, r -> acc + Duration.between(r.startTime, r.endTime) },
            )
        }.sortedByDescending { it.naiveTotalDuration }

private data class SleepLoad(val records: List<SleepSessionRecord>, val historyLimited: Boolean)

private sealed interface SleepLoadOutcome {
    data class Success(val load: SleepLoad) : SleepLoadOutcome

    data object Failure : SleepLoadOutcome
}

// 表示中のperiodと非同期結果のperiodが一致するかを確認してから描画する
// （WeightGraphScreenのAggregatesLoadと同じ理由、lessons.md 7.6）。
private data class SleepPeriodLoad(val period: SleepPeriod, val outcome: SleepLoadOutcome?)

@Composable
fun SleepRawRecordsScreen(
    healthConnectManager: HealthConnectManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var period by rememberSaveable { mutableStateOf(SleepPeriod.LAST_7_DAYS) }
    var retryKey by remember { mutableIntStateOf(0) }
    var periodLoad by remember { mutableStateOf<SleepPeriodLoad?>(null) }

    LaunchedEffect(period, retryKey) {
        periodLoad = SleepPeriodLoad(period = period, outcome = null)

        // 権限の状態は保存した値ではなく毎回問い合わせる（lessons.md 3.1、D-030(2)と同じ理由）。
        val historyPermissionGranted =
            healthConnectManager.getGrantedPermissions()?.contains(HealthConnectPermissions.HISTORY_READ) == true

        val now = Instant.now()
        val filter = period.timeRangeFilter(now, historyPermissionGranted)
        val recordsResult = healthConnectManager.readSleepSessionRecords(filter)

        val outcome =
            when (recordsResult) {
                is SleepSessionRecordsResult.Success ->
                    SleepLoadOutcome.Success(
                        SleepLoad(
                            records = recordsResult.records,
                            historyLimited = period.isHistoryLimited(historyPermissionGranted),
                        ),
                    )
                SleepSessionRecordsResult.Failure -> SleepLoadOutcome.Failure
            }
        periodLoad = SleepPeriodLoad(period = period, outcome = outcome)
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) {
            Text(text = stringResource(id = R.string.poc_sleep_back))
        }
        Text(text = stringResource(id = R.string.poc_sleep_title), style = MaterialTheme.typography.titleLarge)

        SingleChoiceSegmentedButtonRow {
            SleepPeriod.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = period == entry,
                    onClick = { period = entry },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = SleepPeriod.entries.size),
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
                Text(text = stringResource(id = R.string.poc_sleep_loading))
            }
            SleepLoadOutcome.Failure -> {
                Text(text = stringResource(id = R.string.poc_sleep_error))
                Button(onClick = { retryKey++ }) {
                    Text(text = stringResource(id = R.string.poc_sleep_retry))
                }
            }
            is SleepLoadOutcome.Success -> {
                SleepRawContent(load = currentOutcome.load)
            }
        }
    }
}

@Composable
private fun SleepRawContent(load: SleepLoad) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale

    if (load.records.isEmpty()) {
        Text(text = stringResource(id = R.string.poc_sleep_empty))
        return
    }

    val bySource = remember(load.records) { summarizeBySource(load.records) }
    val appNames =
        remember(bySource) {
            bySource.associate { it.dataOrigin to DataOriginNameResolver.resolve(context, it.dataOrigin.packageName) }
        }
    val sortedRecords = remember(load.records) { load.records.sortedByDescending { it.startTime } }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (load.historyLimited) {
                    Text(text = stringResource(id = R.string.poc_sleep_history_limited_notice))
                }
                Text(text = pluralStringResource(id = R.plurals.poc_sleep_record_count, count = load.records.size, load.records.size))
            }
        }
        item {
            Text(text = stringResource(id = R.string.poc_sleep_by_source_title), style = MaterialTheme.typography.titleMedium)
        }
        items(bySource, key = { it.dataOrigin.packageName }) { row ->
            Column {
                Text(text = appNames[row.dataOrigin].orEmpty(), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(id = R.string.poc_sleep_source_summary, row.sessionCount, formatDuration(row.naiveTotalDuration)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            Text(text = stringResource(id = R.string.poc_sleep_raw_records_title), style = MaterialTheme.typography.titleMedium)
        }
        items(sortedRecords, key = { it.metadata.id }) { record ->
            SleepRecordRow(
                record = record,
                sourceName = appNames[record.metadata.dataOrigin].orEmpty(),
                locale = locale,
            )
        }
    }
}

// FormatStyle.MEDIUM（秒まで表示、Steps/HeartRateと同じスタイル）。開始・終了が別の暦日になる
// Sessionでは日付部分も表示されるため、日付境界をまたぐSessionであることがそのまま見える
// （requirements.md §10「Sleep Sessionが日付境界をまたぐ場合の扱い」の生データ側の確認）。
@Composable
private fun SleepRecordRow(record: SleepSessionRecord, sourceName: String, locale: Locale) {
    val startZone = record.startZoneOffset ?: ZoneId.systemDefault()
    val endZone = record.endZoneOffset ?: ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
    val formattedStart = formatter.format(record.startTime.atZone(startZone))
    val formattedEnd = formatter.format(record.endTime.atZone(endZone))
    val duration = Duration.between(record.startTime, record.endTime)
    val stages = remember(record) { stageTotals(record.stages) }

    Column {
        Text(text = "$formattedStart – $formattedEnd  ${formatDuration(duration)}")
        Text(text = sourceName, style = MaterialTheme.typography.bodySmall)
        if (stages.isEmpty()) {
            Text(text = stringResource(id = R.string.poc_sleep_no_stage_data), style = MaterialTheme.typography.bodySmall)
        } else {
            // joinToString(transform = ...)は非inline関数のため、渡したラムダの中で@Composableな
            // stringResource()を直接呼べない（コンパイルエラーになる）。mapNotNull（inline）側で
            // ラベルを文字列に解決してから、transformなしのjoinToStringで連結する。
            val stageText =
                STAGE_DISPLAY_ORDER
                    .mapNotNull { stageType ->
                        stages[stageType]?.let { stageDuration -> "${stringResource(id = stageTypeLabelRes(stageType))} ${formatDuration(stageDuration)}" }
                    }.joinToString(separator = " ・ ")
            Text(text = stageText, style = MaterialTheme.typography.bodySmall)
        }
    }
}
