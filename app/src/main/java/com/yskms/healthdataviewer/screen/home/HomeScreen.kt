package com.yskms.healthdataviewer.screen.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yskms.healthdataviewer.R
import com.yskms.healthdataviewer.healthconnect.HealthConnectAvailability
import com.yskms.healthdataviewer.healthconnect.HealthConnectManager
import com.yskms.healthdataviewer.healthconnect.HealthConnectPermissions
import com.yskms.healthdataviewer.healthconnect.HeartRateAggregateSummaryResult
import com.yskms.healthdataviewer.healthconnect.SleepAggregateSummaryResult
import com.yskms.healthdataviewer.healthconnect.StepsAggregateTotalResult
import com.yskms.healthdataviewer.healthconnect.WeightRecordsResult
import com.yskms.healthdataviewer.ui.theme.HeartRateAccent
import com.yskms.healthdataviewer.ui.theme.SleepAccent
import com.yskms.healthdataviewer.ui.theme.StepsAccent
import com.yskms.healthdataviewer.ui.theme.WeightAccent
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.text.NumberFormat
import java.util.Locale

// WBS 6.1: 今日／週／月／年は要件の字面通り、ローリングウィンドウ（直近7日等、StepsScreenの
// LAST_7_DAYSと同じ簡略化）ではなく暦基準にする。週の始まりは要件に明記がなく、ここでは
// ISO週（月曜始まり）を仮の判断とした。
enum class DashboardPeriod { TODAY, WEEK, MONTH, YEAR }

private fun DashboardPeriod.labelRes(): Int =
    when (this) {
        DashboardPeriod.TODAY -> R.string.home_period_today
        DashboardPeriod.WEEK -> R.string.home_period_week
        DashboardPeriod.MONTH -> R.string.home_period_month
        DashboardPeriod.YEAR -> R.string.home_period_year
    }

private fun DashboardPeriod.startOfPeriod(now: Instant): Instant {
    val zone = ZoneId.systemDefault()
    val today = now.atZone(zone).toLocalDate()
    val startDate =
        when (this) {
            DashboardPeriod.TODAY -> today
            DashboardPeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            DashboardPeriod.MONTH -> today.withDayOfMonth(1)
            DashboardPeriod.YEAR -> today.withDayOfYear(1)
        }
    return startDate.atStartOfDay(zone).toInstant()
}

// 開始時刻が現在から30日以上前かつ履歴読み取り権限がない場合は直近30日にクランプする
// （今日・週は常に30日以内のため実質「月」「年」だけで効く）。Sleepカードの「1日あたり平均」
// （週/月/年タブ）は、この実際にクエリした開始時刻（クランプ後）を使って日数を割る必要がある。
// startOfPeriod()（暦の開始、クランプ前）をそのまま使うと、履歴読み取り権限がない状態で
// 年タブを見たときに「直近30日分の合計 ÷ 年初からの日数」という誤った平均になる
// （実機で発見。1日あたり平均が不自然に小さい値になっていた）。
//
// クランプ後の開始時刻は、nowと同じ時刻のまま30日前（日の途中）にするのではなく、暦日に揃えた
// 翌日0時（切り上げ）にする（レビュー指摘）。日の途中を境界にすると、Sleep平均の分母
// （暦日数、両端含む）にはその境界日を1日分含めてしまう一方、実際のクエリ範囲からはその日の
// 朝の睡眠（境界時刻より前）が漏れてしまい、分子と分母がわずかにずれて平均が実際より小さくなる。
// 安全側に倒し切り上げる考え方はrecentRangeFilterLocal()と同じ（lessons.md 6.1）。
private fun DashboardPeriod.actualStart(now: Instant, historyPermissionGranted: Boolean): Instant {
    val zone = ZoneId.systemDefault()
    val idealStart = startOfPeriod(now)
    val fallbackStart =
        now
            .minus(HealthConnectManager.HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS)
            .atZone(zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
    return if (!historyPermissionGranted && idealStart.isBefore(fallbackStart)) fallbackStart else idealStart
}

// 終了は必ずnowで明示的に固定する（D-029/D-030と同じ理由：範囲を呼び出しごとに閉じておく）。
private fun DashboardPeriod.timeRangeFilter(now: Instant, historyPermissionGranted: Boolean): TimeRangeFilter =
    TimeRangeFilter.between(actualStart(now, historyPermissionGranted), now)

// actualStart()自身の判定から導く（クランプが実際に発生したかどうかの基準を1箇所にまとめ、
// 別の基準（例: 「現在時刻の30日前」を境界にする単純な比較）で再実装して食い違うのを防ぐ）。
private fun DashboardPeriod.isHistoryLimited(now: Instant, historyPermissionGranted: Boolean): Boolean =
    actualStart(now, historyPermissionGranted) != startOfPeriod(now)

private data class StepsCardLoad(val period: DashboardPeriod, val result: StepsAggregateTotalResult?)

private data class HeartRateCardLoad(val period: DashboardPeriod, val result: HeartRateAggregateSummaryResult?)

// 週/月/年タブは「期間合計 ÷ 経過日数」の単純な1日あたり平均（記録のない日も分母に含む近似。
// D-027のWEIGHT_AVGと同様、簡略化した近似であることをここに残す）。今日タブは平均せず、
// 当日bucketの値をそのまま使う（D-032のAWAKE除外・日付境界按分をそのまま反映する）。
private data class SleepCardValue(val duration: Duration?, val isAveraged: Boolean)

private sealed interface SleepCardResult {
    data class Success(val value: SleepCardValue) : SleepCardResult

    data object Failure : SleepCardResult
}

private data class SleepCardLoad(val period: DashboardPeriod, val result: SleepCardResult?)

@Composable
fun HomeScreen(
    healthConnectManager: HealthConnectManager,
    onOpenWeightGraph: (historyPermissionGranted: Boolean) -> Unit,
    onOpenSteps: (historyPermissionGranted: Boolean) -> Unit,
    onOpenHeartRateGraph: (historyPermissionGranted: Boolean) -> Unit,
    onOpenSleepGraph: (historyPermissionGranted: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var availability by remember { mutableStateOf(healthConnectManager.availability) }
    var grantedPermissions by remember { mutableStateOf<Set<String>?>(null) }
    var historyFeatureAvailable by remember { mutableStateOf<Boolean?>(null) }
    var selectedPeriod by rememberSaveable { mutableStateOf(DashboardPeriod.TODAY) }
    // 画面復帰のたびに各カードのデータを再取得させるためのキー（lessons.md 3.1と同じ理由）。
    var resumeKey by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    val requestPermissions =
        rememberLauncherForActivityResult(contract = healthConnectManager.createPermissionRequestContract()) {}

    LifecycleResumeEffect(Unit) {
        availability = healthConnectManager.availability
        historyFeatureAvailable =
            if (availability == HealthConnectAvailability.INSTALLED) {
                healthConnectManager.isHistoryReadFeatureAvailable
            } else {
                null
            }
        val job =
            if (availability == HealthConnectAvailability.INSTALLED) {
                coroutineScope.launch {
                    grantedPermissions = healthConnectManager.getGrantedPermissions()
                    resumeKey++
                }
            } else {
                null
            }
        onPauseOrDispose { job?.cancel() }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(id = R.string.app_name), style = MaterialTheme.typography.titleLarge)

        when (availability) {
            HealthConnectAvailability.NOT_INSTALLED -> Text(text = stringResource(id = R.string.health_connect_not_installed))
            HealthConnectAvailability.UPDATE_REQUIRED -> Text(text = stringResource(id = R.string.health_connect_update_required))
            HealthConnectAvailability.INSTALLED -> {
                val weightGranted = grantedPermissions?.contains(HealthConnectPermissions.WEIGHT_READ)
                val stepsGranted = grantedPermissions?.contains(HealthConnectPermissions.STEPS_READ)
                val heartRateGranted = grantedPermissions?.contains(HealthConnectPermissions.HEART_RATE_READ)
                val sleepGranted = grantedPermissions?.contains(HealthConnectPermissions.SLEEP_READ)
                val historyPermissionGranted = grantedPermissions?.contains(HealthConnectPermissions.HISTORY_READ) == true

                if (listOf(weightGranted, stepsGranted, heartRateGranted, sleepGranted).any { it == false }) {
                    PermissionBanner(
                        onRequestClick = {
                            val permissions =
                                buildSet {
                                    add(HealthConnectPermissions.WEIGHT_READ)
                                    add(HealthConnectPermissions.STEPS_READ)
                                    add(HealthConnectPermissions.HEART_RATE_READ)
                                    add(HealthConnectPermissions.SLEEP_READ)
                                    if (historyFeatureAvailable == true) add(HealthConnectPermissions.HISTORY_READ)
                                }
                            requestPermissions.launch(permissions)
                        },
                    )
                }

                // 旧HealthConnectStatusScreenが表示していた「この端末は履歴読み取りに対応していない」
                // という案内（レビュー指摘: HomeScreen化で表示先がなくなっていた）。
                // historyFeatureAvailable == falseと確定した場合のみ表示する（nullは未確認）。
                if (historyFeatureAvailable == false) {
                    Text(text = stringResource(id = R.string.health_connect_history_not_supported), style = MaterialTheme.typography.bodySmall)
                }

                SingleChoiceSegmentedButtonRow {
                    DashboardPeriod.entries.forEachIndexed { index, entry ->
                        SegmentedButton(
                            selected = selectedPeriod == entry,
                            onClick = { selectedPeriod = entry },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = DashboardPeriod.entries.size),
                        ) {
                            Text(text = stringResource(id = entry.labelRes()))
                        }
                    }
                }

                // 端末が履歴読み取りに対応していない場合（historyFeatureAvailable == false）は、
                // 上でその案内を既に出しているため、ここでは重ねて表示しない（Weightカードと同じ理由。
                // レビュー指摘: この条件が抜けていたため、履歴非対応の端末では「対応していません」と
                // 「権限がないため直近30日のみ表示」が並んで出て、権限を許可すれば解決するように誤読され得た）。
                val historyLimited = selectedPeriod.isHistoryLimited(Instant.now(), historyPermissionGranted)
                if (historyLimited && historyFeatureAvailable != false) {
                    Text(text = stringResource(id = R.string.home_history_limited_notice), style = MaterialTheme.typography.bodySmall)
                }

                var weightLoad by remember { mutableStateOf<WeightRecordsResult?>(null) }
                LaunchedEffect(weightGranted, historyPermissionGranted, resumeKey) {
                    if (weightGranted != true) return@LaunchedEffect
                    weightLoad = null
                    weightLoad = healthConnectManager.findLatestWeightRecords(limit = 2, historyPermissionGranted = historyPermissionGranted)
                }

                var stepsLoad by remember { mutableStateOf<StepsCardLoad?>(null) }
                LaunchedEffect(selectedPeriod, stepsGranted, historyPermissionGranted, resumeKey) {
                    if (stepsGranted != true) return@LaunchedEffect
                    stepsLoad = StepsCardLoad(period = selectedPeriod, result = null)
                    val filter = selectedPeriod.timeRangeFilter(Instant.now(), historyPermissionGranted)
                    stepsLoad = StepsCardLoad(period = selectedPeriod, result = healthConnectManager.readStepsAggregateTotal(filter))
                }

                var heartRateLoad by remember { mutableStateOf<HeartRateCardLoad?>(null) }
                LaunchedEffect(selectedPeriod, heartRateGranted, historyPermissionGranted, resumeKey) {
                    if (heartRateGranted != true) return@LaunchedEffect
                    heartRateLoad = HeartRateCardLoad(period = selectedPeriod, result = null)
                    val filter = selectedPeriod.timeRangeFilter(Instant.now(), historyPermissionGranted)
                    heartRateLoad =
                        HeartRateCardLoad(period = selectedPeriod, result = healthConnectManager.readHeartRateAggregateSummary(filter))
                }

                var sleepLoad by remember { mutableStateOf<SleepCardLoad?>(null) }
                LaunchedEffect(selectedPeriod, sleepGranted, historyPermissionGranted, resumeKey) {
                    if (sleepGranted != true) return@LaunchedEffect
                    sleepLoad = SleepCardLoad(period = selectedPeriod, result = null)
                    val effectNow = Instant.now()
                    val actualStart = selectedPeriod.actualStart(effectNow, historyPermissionGranted)
                    val filter = TimeRangeFilter.between(actualStart, effectNow)
                    val result =
                        when (val summary = healthConnectManager.readSleepAggregateSummary(filter)) {
                            is SleepAggregateSummaryResult.Success -> {
                                val total = summary.totalSleepDuration
                                val value =
                                    if (total == null) {
                                        SleepCardValue(duration = null, isAveraged = false)
                                    } else if (selectedPeriod == DashboardPeriod.TODAY) {
                                        SleepCardValue(duration = total, isAveraged = false)
                                    } else {
                                        // 実際にクエリした開始時刻（クランプ後）で割る。暦の開始時刻
                                        // （startOfPeriod()）をそのまま使うと、履歴読み取り権限が
                                        // ない状態で誤った平均になる（上のactualStart()のコメント参照）。
                                        //
                                        // 日数は経過時間（Duration）の単純な切り捨てではなく、
                                        // actualStartの日付からeffectNowの日付までの暦日数（両端含む）
                                        // で数える。睡眠は明け方に集中するため、例えば週の途中（水曜10時）
                                        // に見ると、経過時間の切り捨て（2日）では分子（月・火・水の約3晩分）
                                        // に対して分母が小さすぎ、平均が実際より大きく出てしまう
                                        // （レビュー指摘、水曜朝で約1.5倍、火曜朝で約2倍）。
                                        // 「今日」の日付も1日として分母に数えることで、今日の朝に含まれる
                                        // 前夜分の睡眠と整合させる。
                                        val zone = ZoneId.systemDefault()
                                        val elapsedDays =
                                            (
                                                ChronoUnit.DAYS.between(
                                                    actualStart.atZone(zone).toLocalDate(),
                                                    effectNow.atZone(zone).toLocalDate(),
                                                ) + 1
                                            ).coerceAtLeast(1)
                                        SleepCardValue(duration = total.dividedBy(elapsedDays), isAveraged = true)
                                    }
                                SleepCardResult.Success(value)
                            }
                            SleepAggregateSummaryResult.Failure -> SleepCardResult.Failure
                        }
                    sleepLoad = SleepCardLoad(period = selectedPeriod, result = result)
                }

                WeightCard(
                    granted = weightGranted,
                    load = weightLoad,
                    historyFeatureAvailable = historyFeatureAvailable,
                    onClick = { onOpenWeightGraph(historyPermissionGranted) },
                )
                StepsCard(
                    period = selectedPeriod,
                    granted = stepsGranted,
                    load = stepsLoad,
                    onClick = { onOpenSteps(historyPermissionGranted) },
                )
                HeartRateCard(
                    period = selectedPeriod,
                    granted = heartRateGranted,
                    load = heartRateLoad,
                    onClick = { onOpenHeartRateGraph(historyPermissionGranted) },
                )
                SleepCard(period = selectedPeriod, granted = sleepGranted, load = sleepLoad, onClick = { onOpenSleepGraph(historyPermissionGranted) })
            }
        }
    }
}

@Composable
private fun PermissionBanner(onRequestClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(id = R.string.home_permission_banner), modifier = Modifier.fillMaxWidth(0.6f))
        Button(onClick = onRequestClick) {
            Text(text = stringResource(id = R.string.health_connect_request_permission))
        }
    }
}

@Composable
private fun MetricCardContainer(
    title: String,
    accentColor: Color,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val cardContent: @Composable ColumnScope.() -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.size(10.dp).background(color = accentColor, shape = CircleShape))
            Text(text = title, style = MaterialTheme.typography.titleMedium)
        }
        content()
    }
    // タップ可能かどうかでCardのオーバーロードを分ける（レビュー指摘）。
    // Modifier.clickable()だとrippleがCardの角丸で切り取られず、Roleのセマンティクスも付かない。
    // Card(onClick = ...)はMaterial3標準のインタラクティブ扱いで両方解決する。
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = cardContent)
        }
    } else {
        Card(modifier = modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = cardContent)
        }
    }
}

@Composable
private fun PermissionNotGrantedOrLoadingText(granted: Boolean?) {
    Text(
        text =
            stringResource(
                id = if (granted == null) R.string.health_connect_checking else R.string.home_permission_not_granted,
            ),
    )
}

@Composable
private fun WeightCard(
    granted: Boolean?,
    load: WeightRecordsResult?,
    historyFeatureAvailable: Boolean?,
    onClick: () -> Unit,
) {
    val locale = LocalLocale.current.platformLocale
    MetricCardContainer(
        title = stringResource(id = R.string.home_weight_title),
        accentColor = WeightAccent,
        onClick = if (granted == true) onClick else null,
    ) {
        when {
            granted != true -> PermissionNotGrantedOrLoadingText(granted)
            load == null -> Text(text = stringResource(id = R.string.home_loading))
            load is WeightRecordsResult.Failure -> Text(text = stringResource(id = R.string.home_error))
            load is WeightRecordsResult.Success -> {
                val records = load.records
                val latest = records.firstOrNull()
                if (latest == null) {
                    Text(text = stringResource(id = R.string.home_no_data))
                } else {
                    Text(
                        text = stringResource(id = R.string.home_weight_value, String.format(locale, "%.2f", latest.weight.inKilograms)),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    val previous = records.getOrNull(1)
                    if (previous != null) {
                        val diffKg = latest.weight.inKilograms - previous.weight.inKilograms
                        Text(
                            text = stringResource(id = R.string.home_weight_delta, formatSignedKg(diffKg, locale)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                // Weightは期間タブに依存せず常に全体から最新値を探すため、期間タブ基準の
                // 共有historyLimited通知（DashboardPeriod.isHistoryLimited）では検知できない
                // （Today/Weekタブでは常にfalseになる）。findLatestWeightRecords()自身の結果の
                // historyLimitedを見てこのカード単体で通知する（レビュー指摘: 履歴読み取り権限が
                // ない状態で最新の記録が30日より前しかない場合、説明なしに「データがありません」とだけ
                // 表示されてしまっていた）。
                //
                // load.historyLimitedは「実際に直近30日へのフォールバックが発生したか」ではなく、
                // 単に`!historyPermissionGranted`をそのまま反映しているだけ（readWithHistoryFallback()の
                // 通常経路。他のRaw/Graph画面のhistoryLimited通知も同じ挙動）。そのため履歴読み取り権限が
                // ない限り、最新の記録が実際には30日以内にあっても常に表示される（安全側の簡略化として許容）。
                // ただし端末がそもそも履歴読み取りに対応していない場合（historyFeatureAvailable == false）は、
                // 上部に既に「対応していません」の案内が出ているため、ここでは重ねて表示しない
                // （「権限がないため」という文言が実際の理由＝端末非対応と食い違い、権限を許可すれば
                // 解決するように誤読されるのを避ける。レビュー指摘）。
                if (load.historyLimited && historyFeatureAvailable != false) {
                    Text(text = stringResource(id = R.string.home_history_limited_notice), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun StepsCard(period: DashboardPeriod, granted: Boolean?, load: StepsCardLoad?, onClick: () -> Unit) {
    val locale = LocalLocale.current.platformLocale
    val numberFormat = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    MetricCardContainer(
        title = stringResource(id = R.string.home_steps_title),
        accentColor = StepsAccent,
        onClick = if (granted == true) onClick else null,
    ) {
        val currentResult = if (load != null && load.period == period) load.result else null
        when {
            granted != true -> PermissionNotGrantedOrLoadingText(granted)
            currentResult == null -> Text(text = stringResource(id = R.string.home_loading))
            currentResult is StepsAggregateTotalResult.Failure -> Text(text = stringResource(id = R.string.home_error))
            currentResult is StepsAggregateTotalResult.Success -> {
                val total = currentResult.total
                if (total == null) {
                    Text(text = stringResource(id = R.string.home_no_data))
                } else {
                    Text(
                        text = stringResource(id = R.string.home_steps_value, numberFormat.format(total)),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeartRateCard(period: DashboardPeriod, granted: Boolean?, load: HeartRateCardLoad?, onClick: () -> Unit) {
    MetricCardContainer(
        title = stringResource(id = R.string.home_heart_rate_title),
        accentColor = HeartRateAccent,
        onClick = if (granted == true) onClick else null,
    ) {
        val currentResult = if (load != null && load.period == period) load.result else null
        when {
            granted != true -> PermissionNotGrantedOrLoadingText(granted)
            currentResult == null -> Text(text = stringResource(id = R.string.home_loading))
            currentResult is HeartRateAggregateSummaryResult.Failure -> Text(text = stringResource(id = R.string.home_error))
            currentResult is HeartRateAggregateSummaryResult.Success -> {
                val average = currentResult.averageBpm
                if (average == null) {
                    Text(text = stringResource(id = R.string.home_no_data))
                } else {
                    Text(
                        text = stringResource(id = R.string.home_heart_rate_value, average),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    val min = currentResult.minBpm
                    val max = currentResult.maxBpm
                    if (min != null && max != null) {
                        Text(
                            text = stringResource(id = R.string.home_heart_rate_range, min, max),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SleepCard(period: DashboardPeriod, granted: Boolean?, load: SleepCardLoad?, onClick: () -> Unit) {
    MetricCardContainer(
        title = stringResource(id = R.string.home_sleep_title),
        accentColor = SleepAccent,
        onClick = if (granted == true) onClick else null,
    ) {
        val currentResult = if (load != null && load.period == period) load.result else null
        when {
            granted != true -> PermissionNotGrantedOrLoadingText(granted)
            currentResult == null -> Text(text = stringResource(id = R.string.home_loading))
            currentResult is SleepCardResult.Failure -> Text(text = stringResource(id = R.string.home_error))
            currentResult is SleepCardResult.Success -> {
                val duration = currentResult.value.duration
                if (duration == null) {
                    Text(text = stringResource(id = R.string.home_no_data))
                } else {
                    Text(text = formatSleepDuration(duration), style = MaterialTheme.typography.headlineSmall)
                    if (currentResult.value.isAveraged) {
                        Text(text = stringResource(id = R.string.home_sleep_average_per_day_notice), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// SleepDetailScreen.formatDuration()と同じ定義を複製している（private宣言のため、ファイルをまたいで
// 再利用できない）。WBS 6.2で他の重複（bucket境界・periodタグ付け等）は共通化したが、この5行程度の
// 書式関数まで共通化するのは過剰と判断し、複製のままにしている。
private fun formatSleepDuration(duration: Duration): String {
    val totalMinutes = duration.toMinutes()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return String.format(Locale.ROOT, "%d:%02d", hours, minutes)
}

// 前回比の符号付き表示。丸めた後の値で符号を決める（丸め前の微小な差でマイナス表示になるのを防ぐ、
// -0.0の表示ゆれも吸収する）。
private fun formatSignedKg(diffKg: Double, locale: Locale): String {
    val rounded = Math.round(diffKg * 100) / 100.0
    val sign =
        when {
            rounded > 0 -> "+"
            rounded < 0 -> "-"
            else -> "±"
        }
    return sign + String.format(locale, "%.2f", kotlin.math.abs(rounded))
}
