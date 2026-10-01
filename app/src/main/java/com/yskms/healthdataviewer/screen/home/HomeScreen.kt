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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.yskms.healthdataviewer.healthconnect.ActiveCaloriesAggregateTotalResult
import com.yskms.healthdataviewer.healthconnect.DistanceAggregateTotalResult
import com.yskms.healthdataviewer.healthconnect.HealthConnectAvailability
import com.yskms.healthdataviewer.healthconnect.HealthConnectManager
import com.yskms.healthdataviewer.healthconnect.HealthConnectPermissions
import com.yskms.healthdataviewer.healthconnect.HeartRateAggregateSummaryResult
import com.yskms.healthdataviewer.healthconnect.PermissionsCheckState
import com.yskms.healthdataviewer.healthconnect.SleepAggregateSummaryResult
import com.yskms.healthdataviewer.healthconnect.StepsAggregateTotalResult
import com.yskms.healthdataviewer.healthconnect.TotalCaloriesAggregateTotalResult
import com.yskms.healthdataviewer.healthconnect.WeightRecordsResult
import com.yskms.healthdataviewer.healthconnect.isGranted
import com.yskms.healthdataviewer.screen.common.HealthConnectUnavailableNotice
import com.yskms.healthdataviewer.screen.common.PermissionsCheckFailedNotice
import com.yskms.healthdataviewer.settings.UserSettingsRepository
import com.yskms.healthdataviewer.ui.theme.ActiveCaloriesAccent
import com.yskms.healthdataviewer.ui.theme.DistanceAccent
import com.yskms.healthdataviewer.ui.theme.HeartRateAccent
import com.yskms.healthdataviewer.ui.theme.SleepAccent
import com.yskms.healthdataviewer.ui.theme.StepsAccent
import com.yskms.healthdataviewer.ui.theme.TotalCaloriesAccent
import com.yskms.healthdataviewer.ui.theme.WeightAccent
import kotlinx.coroutines.Job
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

private data class DistanceCardLoad(val period: DashboardPeriod, val result: DistanceAggregateTotalResult?)

private data class ActiveCaloriesCardLoad(val period: DashboardPeriod, val result: ActiveCaloriesAggregateTotalResult?)

private data class TotalCaloriesCardLoad(val period: DashboardPeriod, val result: TotalCaloriesAggregateTotalResult?)

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
    userSettingsRepository: UserSettingsRepository,
    onOpenWeightGraph: (historyPermissionGranted: Boolean) -> Unit,
    onOpenSteps: (historyPermissionGranted: Boolean) -> Unit,
    onOpenHeartRateGraph: (historyPermissionGranted: Boolean) -> Unit,
    onOpenSleepGraph: (historyPermissionGranted: Boolean) -> Unit,
    onOpenDistance: (historyPermissionGranted: Boolean) -> Unit,
    onOpenActiveCalories: (historyPermissionGranted: Boolean) -> Unit,
    onOpenTotalCalories: (historyPermissionGranted: Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // StateFlow（常に最新値を持つ）のためinitialは不要（UserSettingsRepository.kt参照）。
    val settings by userSettingsRepository.settingsFlow.collectAsState()
    var availability by remember { mutableStateOf(healthConnectManager.availability) }
    var permissionsCheckState by remember { mutableStateOf<PermissionsCheckState>(PermissionsCheckState.Loading) }
    var historyFeatureAvailable by remember { mutableStateOf<Boolean?>(null) }
    var selectedPeriod by rememberSaveable { mutableStateOf(DashboardPeriod.TODAY) }
    // 画面復帰のたびに各カードのデータを再取得させるためのキー（lessons.md 3.1と同じ理由）。
    var resumeKey by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    val requestPermissions =
        rememberLauncherForActivityResult(contract = healthConnectManager.createPermissionRequestContract()) {}

    // WBS 6.9: 権限確認（IPC呼び出し）の失敗を「まだ確認していない」と区別して伝えるため、
    // 結果をnullではなくPermissionsCheckStateで保持する（lessons.md 4.1、Failureはnull時）。
    // 画面復帰時の自動実行と、確認失敗時の案内に付ける「再試行」ボタンの両方から呼ぶ。
    //
    // コードレビュー指摘: 再試行ボタンから呼んだJobを画面復帰時のJobと別々に管理していなかったため、
    // 両方が並行して走ると後から完了した方が結果を上書きしてしまい得た（新しいSuccessを古いFailureが
    // 上書きする等）。`refreshJob`に直近のJobだけを保持し、新しく始める前に必ず前のJobをキャンセルする。
    // `resetToLoading`は再試行の手動実行時のみtrueにする: 画面復帰時の自動実行までLoadingへ戻すと、
    // 確認済みの状態が一瞬「確認中…」にちらつく（4つのLaunchedEffectで対応済みの問題、D-039(10)）を
    // 権限確認でも再現してしまう。手動再試行は明示的な操作のフィードバックとして許容する。
    var refreshJob by remember { mutableStateOf<Job?>(null) }

    fun refreshPermissions(resetToLoading: Boolean = false) {
        refreshJob?.cancel()
        if (resetToLoading) permissionsCheckState = PermissionsCheckState.Loading
        refreshJob =
            coroutineScope.launch {
                permissionsCheckState =
                    when (val result = healthConnectManager.getGrantedPermissions()) {
                        null -> PermissionsCheckState.Failure
                        else -> PermissionsCheckState.Success(result)
                    }
                resumeKey++
            }
    }

    LifecycleResumeEffect(Unit) {
        availability = healthConnectManager.availability
        historyFeatureAvailable =
            if (availability == HealthConnectAvailability.INSTALLED) {
                healthConnectManager.isHistoryReadFeatureAvailable
            } else {
                null
            }
        if (availability == HealthConnectAvailability.INSTALLED) refreshPermissions()
        onPauseOrDispose { refreshJob?.cancel() }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = stringResource(id = R.string.app_name), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onOpenSettings) {
                Text(text = stringResource(id = R.string.settings_title))
            }
        }

        when (availability) {
            HealthConnectAvailability.UNAVAILABLE, HealthConnectAvailability.UPDATE_REQUIRED ->
                HealthConnectUnavailableNotice(availability = availability, healthConnectManager = healthConnectManager)
            HealthConnectAvailability.INSTALLED -> {
                val weightGranted = permissionsCheckState.isGranted(HealthConnectPermissions.WEIGHT_READ)
                val stepsGranted = permissionsCheckState.isGranted(HealthConnectPermissions.STEPS_READ)
                val heartRateGranted = permissionsCheckState.isGranted(HealthConnectPermissions.HEART_RATE_READ)
                val sleepGranted = permissionsCheckState.isGranted(HealthConnectPermissions.SLEEP_READ)
                val distanceGranted = permissionsCheckState.isGranted(HealthConnectPermissions.DISTANCE_READ)
                val activeCaloriesGranted = permissionsCheckState.isGranted(HealthConnectPermissions.ACTIVE_CALORIES_READ)
                val totalCaloriesGranted = permissionsCheckState.isGranted(HealthConnectPermissions.TOTAL_CALORIES_READ)
                val historyPermissionGranted = permissionsCheckState.isGranted(HealthConnectPermissions.HISTORY_READ) == true

                if (listOf(
                        weightGranted,
                        stepsGranted,
                        heartRateGranted,
                        sleepGranted,
                        distanceGranted,
                        activeCaloriesGranted,
                        totalCaloriesGranted,
                    ).any { it == false }
                ) {
                    PermissionBanner(
                        onRequestClick = {
                            val permissions =
                                buildSet {
                                    add(HealthConnectPermissions.WEIGHT_READ)
                                    add(HealthConnectPermissions.STEPS_READ)
                                    add(HealthConnectPermissions.HEART_RATE_READ)
                                    add(HealthConnectPermissions.SLEEP_READ)
                                    add(HealthConnectPermissions.DISTANCE_READ)
                                    add(HealthConnectPermissions.ACTIVE_CALORIES_READ)
                                    add(HealthConnectPermissions.TOTAL_CALORIES_READ)
                                    if (historyFeatureAvailable == true) add(HealthConnectPermissions.HISTORY_READ)
                                }
                            requestPermissions.launch(permissions)
                        },
                    )
                }

                // WBS 6.9: 権限確認のIPC呼び出し自体が失敗した場合（Health Connect側の更新中など、
                // lessons.md 4.1）の案内。上のPermissionBannerとは区別する: こちらは「許可されていない」
                // ことが分かっている場合（isGranted() == false）ではなく、許可状態そのものが
                // 分からない場合に出す。
                if (permissionsCheckState is PermissionsCheckState.Failure) {
                    PermissionsCheckFailedNotice(onRetryClick = { refreshPermissions(resetToLoading = true) })
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

                // 7つのLaunchedEffectとも、再取得開始時に結果をnullへ戻さない（コードレビュー指摘）。
                // 戻すと、表示指標トグルがOFFの状態で「データなし」と確定していたカードが、画面復帰
                // （resumeKeyの変化）や期間タブ切り替えのたびに一瞬「読み込み中」として出現してから
                // また消える、というちらつきが起きる。前回の結果を表示したまま裏で再取得し、新しい
                // 結果が届いた時点でだけ置き換える（period違いの結果はcurrentResult側のガードで
                // 弾かれるため、期間タブ切替時に古い期間の値が誤って見え続けることはない）。
                var weightLoad by remember { mutableStateOf<WeightRecordsResult?>(null) }
                LaunchedEffect(weightGranted, historyPermissionGranted, resumeKey) {
                    if (weightGranted != true) return@LaunchedEffect
                    weightLoad = healthConnectManager.findLatestWeightRecords(limit = 2, historyPermissionGranted = historyPermissionGranted)
                }

                var stepsLoad by remember { mutableStateOf<StepsCardLoad?>(null) }
                LaunchedEffect(selectedPeriod, stepsGranted, historyPermissionGranted, resumeKey) {
                    if (stepsGranted != true) return@LaunchedEffect
                    val filter = selectedPeriod.timeRangeFilter(Instant.now(), historyPermissionGranted)
                    stepsLoad = StepsCardLoad(period = selectedPeriod, result = healthConnectManager.readStepsAggregateTotal(filter))
                }

                var heartRateLoad by remember { mutableStateOf<HeartRateCardLoad?>(null) }
                LaunchedEffect(selectedPeriod, heartRateGranted, historyPermissionGranted, resumeKey) {
                    if (heartRateGranted != true) return@LaunchedEffect
                    val filter = selectedPeriod.timeRangeFilter(Instant.now(), historyPermissionGranted)
                    heartRateLoad =
                        HeartRateCardLoad(period = selectedPeriod, result = healthConnectManager.readHeartRateAggregateSummary(filter))
                }

                var sleepLoad by remember { mutableStateOf<SleepCardLoad?>(null) }
                LaunchedEffect(selectedPeriod, sleepGranted, historyPermissionGranted, resumeKey) {
                    if (sleepGranted != true) return@LaunchedEffect
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

                var distanceLoad by remember { mutableStateOf<DistanceCardLoad?>(null) }
                LaunchedEffect(selectedPeriod, distanceGranted, historyPermissionGranted, resumeKey) {
                    if (distanceGranted != true) return@LaunchedEffect
                    val filter = selectedPeriod.timeRangeFilter(Instant.now(), historyPermissionGranted)
                    distanceLoad =
                        DistanceCardLoad(period = selectedPeriod, result = healthConnectManager.readDistanceAggregateTotal(filter))
                }

                var activeCaloriesLoad by remember { mutableStateOf<ActiveCaloriesCardLoad?>(null) }
                LaunchedEffect(selectedPeriod, activeCaloriesGranted, historyPermissionGranted, resumeKey) {
                    if (activeCaloriesGranted != true) return@LaunchedEffect
                    val filter = selectedPeriod.timeRangeFilter(Instant.now(), historyPermissionGranted)
                    activeCaloriesLoad =
                        ActiveCaloriesCardLoad(period = selectedPeriod, result = healthConnectManager.readActiveCaloriesAggregateTotal(filter))
                }

                var totalCaloriesLoad by remember { mutableStateOf<TotalCaloriesCardLoad?>(null) }
                LaunchedEffect(selectedPeriod, totalCaloriesGranted, historyPermissionGranted, resumeKey) {
                    if (totalCaloriesGranted != true) return@LaunchedEffect
                    val filter = selectedPeriod.timeRangeFilter(Instant.now(), historyPermissionGranted)
                    totalCaloriesLoad =
                        TotalCaloriesCardLoad(period = selectedPeriod, result = healthConnectManager.readTotalCaloriesAggregateTotal(filter))
                }

                // period違いの結果を弾くガード（Steps/HeartRate/Sleep/Distance/Calories共通）。各Cardの描画と
                // 「表示指標」での非表示判定（isXxxCardHidden()）の両方がこれを使うため、親で
                // 一度だけ計算する（コードレビュー指摘: 全カードが非表示になった場合に理由が
                // 分からず画面が空白になる問題への対応で、両者が同じ値を見る必要があるため）。
                val stepsResult = if (stepsLoad?.period == selectedPeriod) stepsLoad?.result else null
                val heartRateResult = if (heartRateLoad?.period == selectedPeriod) heartRateLoad?.result else null
                val sleepResult = if (sleepLoad?.period == selectedPeriod) sleepLoad?.result else null
                val distanceResult = if (distanceLoad?.period == selectedPeriod) distanceLoad?.result else null
                val activeCaloriesResult = if (activeCaloriesLoad?.period == selectedPeriod) activeCaloriesLoad?.result else null
                val totalCaloriesResult = if (totalCaloriesLoad?.period == selectedPeriod) totalCaloriesLoad?.result else null
                val showMetricsWithoutData = settings.showMetricsWithoutData

                val allCardsHidden =
                    !showMetricsWithoutData &&
                        isWeightCardHidden(weightGranted, weightLoad, historyFeatureAvailable, showMetricsWithoutData) &&
                        isStepsCardHidden(stepsGranted, stepsResult, showMetricsWithoutData) &&
                        isHeartRateCardHidden(heartRateGranted, heartRateResult, showMetricsWithoutData) &&
                        isSleepCardHidden(sleepGranted, sleepResult, showMetricsWithoutData) &&
                        isDistanceCardHidden(distanceGranted, distanceResult, showMetricsWithoutData) &&
                        isActiveCaloriesCardHidden(activeCaloriesGranted, activeCaloriesResult, showMetricsWithoutData) &&
                        isTotalCaloriesCardHidden(totalCaloriesGranted, totalCaloriesResult, showMetricsWithoutData)

                if (allCardsHidden) {
                    Text(text = stringResource(id = R.string.home_all_metrics_hidden_notice), style = MaterialTheme.typography.bodySmall)
                }

                WeightCard(
                    permissionsCheckState = permissionsCheckState,
                    granted = weightGranted,
                    load = weightLoad,
                    historyFeatureAvailable = historyFeatureAvailable,
                    showMetricsWithoutData = showMetricsWithoutData,
                    onClick = { onOpenWeightGraph(historyPermissionGranted) },
                )
                StepsCard(
                    permissionsCheckState = permissionsCheckState,
                    granted = stepsGranted,
                    currentResult = stepsResult,
                    showMetricsWithoutData = showMetricsWithoutData,
                    onClick = { onOpenSteps(historyPermissionGranted) },
                )
                HeartRateCard(
                    permissionsCheckState = permissionsCheckState,
                    granted = heartRateGranted,
                    currentResult = heartRateResult,
                    showMetricsWithoutData = showMetricsWithoutData,
                    onClick = { onOpenHeartRateGraph(historyPermissionGranted) },
                )
                SleepCard(
                    permissionsCheckState = permissionsCheckState,
                    granted = sleepGranted,
                    currentResult = sleepResult,
                    showMetricsWithoutData = showMetricsWithoutData,
                    onClick = { onOpenSleepGraph(historyPermissionGranted) },
                )
                DistanceCard(
                    permissionsCheckState = permissionsCheckState,
                    granted = distanceGranted,
                    currentResult = distanceResult,
                    showMetricsWithoutData = showMetricsWithoutData,
                    onClick = { onOpenDistance(historyPermissionGranted) },
                )
                ActiveCaloriesCard(
                    permissionsCheckState = permissionsCheckState,
                    granted = activeCaloriesGranted,
                    currentResult = activeCaloriesResult,
                    showMetricsWithoutData = showMetricsWithoutData,
                    onClick = { onOpenActiveCalories(historyPermissionGranted) },
                )
                TotalCaloriesCard(
                    permissionsCheckState = permissionsCheckState,
                    granted = totalCaloriesGranted,
                    currentResult = totalCaloriesResult,
                    showMetricsWithoutData = showMetricsWithoutData,
                    onClick = { onOpenTotalCalories(historyPermissionGranted) },
                )
            }
        }
    }
}

// コードレビュー指摘（PermissionsCheckFailedNoticeと同じ理由）: Modifier.fillMaxWidth(0.6f)でTextの幅を
// 固定していたため、ボタン側が残り約40%に窮屈になり得た。RowScope.weight(1f)に変更した。
@Composable
private fun PermissionBanner(onRequestClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(id = R.string.home_permission_banner), modifier = Modifier.weight(1f))
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

// granted == nullは「読み込み中」「未許可」以外にも、権限確認自体が失敗した場合（lessons.md 4.1）
// があり得る。permissionsCheckStateで3つ目の状態として区別し、確認中のまま戻らない誤解を避ける
// （WBS 6.9）。
@Composable
private fun PermissionNotGrantedOrLoadingText(permissionsCheckState: PermissionsCheckState, granted: Boolean?) {
    val textRes =
        when {
            granted == false -> R.string.home_permission_not_granted
            permissionsCheckState is PermissionsCheckState.Failure -> R.string.health_connect_check_failed
            else -> R.string.health_connect_checking
        }
    Text(text = stringResource(id = textRes))
}

// 要件§6「データがない項目」の表示設定（WBS 6.6）。権限が未許可・未確認のカードは
// 「データがない」ではなく別の案件（許可すれば解決する）のため、この設定の対象外にする
// （granted == trueを要求する）。HomeScreen本体（全カード非表示時の案内）とCard自身の両方が
// 同じ判定を使うための共有の純粋関数（コードレビュー指摘）。
//
// Weightのみ、records.isEmpty()だけで即座に隠さない。load.historyLimited（履歴読み取り権限が
// ないため直近30日しか見ていない）がtrueで、かつ端末が履歴読み取りに対応している
// （historyFeatureAvailable != false、つまり権限を許可すれば解決する余地がある）場合は、
// 「データがない」のではなく「権限を許可すればデータが見える可能性がある」状態なので隠さない
// （コードレビュー指摘: 隠すとhistoryLimitedの案内ごと消え、ユーザーが気付く手段がなくなる）。
private fun isWeightCardHidden(
    granted: Boolean?,
    load: WeightRecordsResult?,
    historyFeatureAvailable: Boolean?,
    showMetricsWithoutData: Boolean,
): Boolean =
    !showMetricsWithoutData &&
        granted == true &&
        load is WeightRecordsResult.Success &&
        load.records.isEmpty() &&
        !(load.historyLimited && historyFeatureAvailable != false)

private fun isStepsCardHidden(granted: Boolean?, currentResult: StepsAggregateTotalResult?, showMetricsWithoutData: Boolean): Boolean =
    !showMetricsWithoutData && granted == true && currentResult is StepsAggregateTotalResult.Success && currentResult.total == null

private fun isHeartRateCardHidden(
    granted: Boolean?,
    currentResult: HeartRateAggregateSummaryResult?,
    showMetricsWithoutData: Boolean,
): Boolean =
    !showMetricsWithoutData && granted == true && currentResult is HeartRateAggregateSummaryResult.Success && currentResult.averageBpm == null

private fun isSleepCardHidden(granted: Boolean?, currentResult: SleepCardResult?, showMetricsWithoutData: Boolean): Boolean =
    !showMetricsWithoutData && granted == true && currentResult is SleepCardResult.Success && currentResult.value.duration == null

private fun isDistanceCardHidden(granted: Boolean?, currentResult: DistanceAggregateTotalResult?, showMetricsWithoutData: Boolean): Boolean =
    !showMetricsWithoutData &&
        granted == true &&
        currentResult is DistanceAggregateTotalResult.Success &&
        currentResult.totalKilometers == null

private fun isActiveCaloriesCardHidden(
    granted: Boolean?,
    currentResult: ActiveCaloriesAggregateTotalResult?,
    showMetricsWithoutData: Boolean,
): Boolean =
    !showMetricsWithoutData &&
        granted == true &&
        currentResult is ActiveCaloriesAggregateTotalResult.Success &&
        currentResult.totalKilocalories == null

// レビュー指摘: TotalCaloriesAggregateTotalResult.Successのコメント参照。ENERGY_TOTALはレコードが
// 1件もない期間でも非null値（推計値と見られる）を返すことがあると実機で確認しており、
// totalKilocalories == nullを「データなし」とみなすこの判定が意図通り機能しない可能性がある
// （要検証、lessons.md 6.25）。他のisXxxCardHidden()と形を揃えるため、判定ロジック自体は変更せず
// 残しているが、確認が取れるまで動作を過信しないこと。
private fun isTotalCaloriesCardHidden(
    granted: Boolean?,
    currentResult: TotalCaloriesAggregateTotalResult?,
    showMetricsWithoutData: Boolean,
): Boolean =
    !showMetricsWithoutData &&
        granted == true &&
        currentResult is TotalCaloriesAggregateTotalResult.Success &&
        currentResult.totalKilocalories == null

@Composable
private fun WeightCard(
    permissionsCheckState: PermissionsCheckState,
    granted: Boolean?,
    load: WeightRecordsResult?,
    historyFeatureAvailable: Boolean?,
    showMetricsWithoutData: Boolean,
    onClick: () -> Unit,
) {
    if (isWeightCardHidden(granted, load, historyFeatureAvailable, showMetricsWithoutData)) return
    val locale = LocalLocale.current.platformLocale
    MetricCardContainer(
        title = stringResource(id = R.string.home_weight_title),
        accentColor = WeightAccent,
        onClick = if (granted == true) onClick else null,
    ) {
        when {
            granted != true -> PermissionNotGrantedOrLoadingText(permissionsCheckState, granted)
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
private fun StepsCard(
    permissionsCheckState: PermissionsCheckState,
    granted: Boolean?,
    currentResult: StepsAggregateTotalResult?,
    showMetricsWithoutData: Boolean,
    onClick: () -> Unit,
) {
    if (isStepsCardHidden(granted, currentResult, showMetricsWithoutData)) return
    val locale = LocalLocale.current.platformLocale
    val numberFormat = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    MetricCardContainer(
        title = stringResource(id = R.string.home_steps_title),
        accentColor = StepsAccent,
        onClick = if (granted == true) onClick else null,
    ) {
        when {
            granted != true -> PermissionNotGrantedOrLoadingText(permissionsCheckState, granted)
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
private fun HeartRateCard(
    permissionsCheckState: PermissionsCheckState,
    granted: Boolean?,
    currentResult: HeartRateAggregateSummaryResult?,
    showMetricsWithoutData: Boolean,
    onClick: () -> Unit,
) {
    if (isHeartRateCardHidden(granted, currentResult, showMetricsWithoutData)) return
    MetricCardContainer(
        title = stringResource(id = R.string.home_heart_rate_title),
        accentColor = HeartRateAccent,
        onClick = if (granted == true) onClick else null,
    ) {
        when {
            granted != true -> PermissionNotGrantedOrLoadingText(permissionsCheckState, granted)
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
private fun SleepCard(
    permissionsCheckState: PermissionsCheckState,
    granted: Boolean?,
    currentResult: SleepCardResult?,
    showMetricsWithoutData: Boolean,
    onClick: () -> Unit,
) {
    if (isSleepCardHidden(granted, currentResult, showMetricsWithoutData)) return
    MetricCardContainer(
        title = stringResource(id = R.string.home_sleep_title),
        accentColor = SleepAccent,
        onClick = if (granted == true) onClick else null,
    ) {
        when {
            granted != true -> PermissionNotGrantedOrLoadingText(permissionsCheckState, granted)
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

// StepsCardと同じ形（期間合計をそのまま表示、Sleepのような1日あたり平均化はしない）。
// DISTANCE_TOTALはkmへ変換済み（HealthConnectManager.readDistanceAggregateTotal()参照）。
// WeightCardと同じ"%.2f"書式は桁区切りが付かず、Weightは常に1,000未満（体重）のため問題にならないが、
// Distanceは年タブで1,000kmを超えうる（実データで1日あたり5〜8km×365日≒2,000km前後）ため、
// StepsCardのNumberFormatと同じ考え方でNumberFormat（桁区切り＋小数2桁固定）を使う
// （コードレビュー指摘）。
@Composable
private fun DistanceCard(
    permissionsCheckState: PermissionsCheckState,
    granted: Boolean?,
    currentResult: DistanceAggregateTotalResult?,
    showMetricsWithoutData: Boolean,
    onClick: () -> Unit,
) {
    if (isDistanceCardHidden(granted, currentResult, showMetricsWithoutData)) return
    val locale = LocalLocale.current.platformLocale
    val decimalFormat =
        remember(locale) {
            NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = 2
                maximumFractionDigits = 2
            }
        }
    MetricCardContainer(
        title = stringResource(id = R.string.home_distance_title),
        accentColor = DistanceAccent,
        onClick = if (granted == true) onClick else null,
    ) {
        when {
            granted != true -> PermissionNotGrantedOrLoadingText(permissionsCheckState, granted)
            currentResult == null -> Text(text = stringResource(id = R.string.home_loading))
            currentResult is DistanceAggregateTotalResult.Failure -> Text(text = stringResource(id = R.string.home_error))
            currentResult is DistanceAggregateTotalResult.Success -> {
                val total = currentResult.totalKilometers
                if (total == null) {
                    Text(text = stringResource(id = R.string.home_no_data))
                } else {
                    Text(
                        text = stringResource(id = R.string.home_distance_value, decimalFormat.format(total)),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
        }
    }
}

// StepsCardと同じ形（期間合計をそのまま表示、Sleepのような1日あたり平均化はしない）。
// ACTIVE_CALORIES_TOTALはkcalへ変換済み（HealthConnectManager.readActiveCaloriesAggregateTotal()参照）。
// DistanceCardのkm（小数2桁）とは異なり、kcal合計は歩数と同じく小数の意味が薄いため、StepsCardと同じ
// 桁区切り付き整数表示にする（D-043(4)）。グラフ側（ActiveCaloriesAggregateChart）の縦軸・マーカーは
// Vicoの既定書式のままで、この桁区切り整数化はしていない（Steps/Distanceの既存Chartと同じ扱いで
// 回帰ではないが、D-043(4)・wbs.md 6.10の「グラフも桁区切り付き整数」という記述はカード限定の話として
// 読み替えること）。
@Composable
private fun ActiveCaloriesCard(
    permissionsCheckState: PermissionsCheckState,
    granted: Boolean?,
    currentResult: ActiveCaloriesAggregateTotalResult?,
    showMetricsWithoutData: Boolean,
    onClick: () -> Unit,
) {
    if (isActiveCaloriesCardHidden(granted, currentResult, showMetricsWithoutData)) return
    val locale = LocalLocale.current.platformLocale
    val numberFormat = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    MetricCardContainer(
        title = stringResource(id = R.string.home_active_calories_title),
        accentColor = ActiveCaloriesAccent,
        onClick = if (granted == true) onClick else null,
    ) {
        when {
            granted != true -> PermissionNotGrantedOrLoadingText(permissionsCheckState, granted)
            currentResult == null -> Text(text = stringResource(id = R.string.home_loading))
            currentResult is ActiveCaloriesAggregateTotalResult.Failure -> Text(text = stringResource(id = R.string.home_error))
            currentResult is ActiveCaloriesAggregateTotalResult.Success -> {
                val total = currentResult.totalKilocalories
                if (total == null) {
                    Text(text = stringResource(id = R.string.home_no_data))
                } else {
                    Text(
                        text = stringResource(id = R.string.home_active_calories_value, numberFormat.format(total)),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
        }
    }
}

// ActiveCaloriesCardと同じ形・同じ理由。
@Composable
private fun TotalCaloriesCard(
    permissionsCheckState: PermissionsCheckState,
    granted: Boolean?,
    currentResult: TotalCaloriesAggregateTotalResult?,
    showMetricsWithoutData: Boolean,
    onClick: () -> Unit,
) {
    if (isTotalCaloriesCardHidden(granted, currentResult, showMetricsWithoutData)) return
    val locale = LocalLocale.current.platformLocale
    val numberFormat = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    MetricCardContainer(
        title = stringResource(id = R.string.home_total_calories_title),
        accentColor = TotalCaloriesAccent,
        onClick = if (granted == true) onClick else null,
    ) {
        when {
            granted != true -> PermissionNotGrantedOrLoadingText(permissionsCheckState, granted)
            currentResult == null -> Text(text = stringResource(id = R.string.home_loading))
            currentResult is TotalCaloriesAggregateTotalResult.Failure -> Text(text = stringResource(id = R.string.home_error))
            currentResult is TotalCaloriesAggregateTotalResult.Success -> {
                val total = currentResult.totalKilocalories
                if (total == null) {
                    Text(text = stringResource(id = R.string.home_no_data))
                } else {
                    Text(
                        text = stringResource(id = R.string.home_total_calories_value, numberFormat.format(total)),
                        style = MaterialTheme.typography.headlineSmall,
                    )
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
