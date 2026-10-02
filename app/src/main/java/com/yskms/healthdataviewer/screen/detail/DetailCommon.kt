package com.yskms.healthdataviewer.screen.detail

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yskms.healthdataviewer.R
import com.yskms.healthdataviewer.healthconnect.OldestRecordResult
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// WBS 6.2: 既存poc/*GraphScreenで複製されていたOldestRecordInfo/期間タブUIを共通化したもの。
// findOldestXxxRecordTime()の戻り値をOldestRecordResultに統一した（healthconnect/OldestRecordResult.kt）
// ため、このComposableも4データ型で1つ済むようになった。
@Composable
fun OldestRecordInfo(oldestResult: OldestRecordResult?) {
    val locale = LocalLocale.current.platformLocale
    val text =
        when (oldestResult) {
            null -> stringResource(id = R.string.detail_loading)
            OldestRecordResult.Failure -> stringResource(id = R.string.detail_error)
            is OldestRecordResult.Success -> {
                val time = oldestResult.time
                if (time != null) {
                    val formattedDate =
                        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                            .withLocale(locale)
                            .format(time.atZone(ZoneId.systemDefault()).toLocalDate())
                    stringResource(id = R.string.detail_oldest_record, formattedDate)
                } else {
                    stringResource(id = R.string.detail_oldest_record_none)
                }
            }
        }
    Text(text = text, style = MaterialTheme.typography.bodySmall)
}

// WBS 6.5: 要件§11（横画面で画面幅を最大限使った長期グラフを表示する）の判定に使う。
// MainActivityはconfigChangesを宣言していないため、回転時はActivityごと再生成される
// （タブ・期間選択がrememberSaveableで保持される前提も含め、既存の前例と一貫した設計）。
@Composable
fun isLandscapeOrientation(): Boolean = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

// Chartタブの外側Column（全画面時はpaddingを狭める）は4データ型のDetailScreenで同一のため共通化した。
// 全画面時も含め常にverticalScrollを付けたままにする（detailChartHeight()の見積もりが外れた場合の
// 安全弁）。`Modifier.weight()`は使わない: verticalScroll付きColumnの子は無限大の高さ制約を受けるため、
// weight()と同居できない（Composeの制約）。グラフの高さは、ここで取得した利用可能高さ（`maxHeight`）を
// contentに渡し、呼び出し側がdetailChartHeight()で計算する。BoxWithConstraints（SubcomposeLayout）は
// Chartタブの中でのみ使い、Records（LazyColumn）・Sourcesタブまで巻き込まない。
@Composable
fun ChartTabColumn(fullScreenChart: Boolean, content: @Composable ColumnScope.(availableHeight: Dp) -> Unit) {
    val horizontalPadding = if (fullScreenChart) 8.dp else 24.dp
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val availableHeight = maxHeight
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(horizontal = horizontalPadding)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content(availableHeight)
        }
    }
}

private val PORTRAIT_CHART_HEIGHT = 240.dp
private val FULLSCREEN_CHART_MIN_HEIGHT = 160.dp
private val FULLSCREEN_ELEMENT_SPACING = 16.dp
private val FULLSCREEN_PERIOD_TABS_HEIGHT = 48.dp
private val FULLSCREEN_CAPTION_HEIGHT = 24.dp
private val FULLSCREEN_NOTICE_HEIGHT = 24.dp
private val FULLSCREEN_MEASUREMENT_COUNT_HEIGHT = 20.dp
private val FULLSCREEN_CUSTOM_PICKER_HEIGHT = 56.dp

// 全画面時、グラフ本体は`availableHeight`から他の要素（期間タブ・集計方法の注記は常に表示、
// historyLimitedの注記・Custom選択時のピッカー・HeartRateの計測数テキストは条件付きで表示）が使う
// 高さを差し引いた分を使う。見積もりが外れてもChartTabColumnのverticalScrollが安全弁になるため、
// 正確な値である必要はない（下限160dpも設け、極端に低い横画面でも0近くまで潰れないようにしている）。
fun detailChartHeight(
    fullScreenChart: Boolean,
    availableHeight: Dp,
    historyLimited: Boolean,
    isCustomPeriod: Boolean,
    showsMeasurementCount: Boolean = false,
    // WBS 6.10（D-045、コードレビュー指摘）: Blood Pressureのチャート凡例（`detail_blood_pressure_chart_legend`、
    // デフォルトのTextスタイルで1行、historyLimitedの注記と同じ見た目）用。showsMeasurementCount
    // （bodySmallスタイル、20dp）とは文字サイズが異なるため、同じFULLSCREEN_NOTICE_HEIGHT（24dp）を
    // 再利用する（historyLimitedの注記と同じカテゴリの「追加の注記1行」として扱う）。
    showsChartLegend: Boolean = false,
): Dp {
    if (!fullScreenChart) return PORTRAIT_CHART_HEIGHT
    var reserved = FULLSCREEN_PERIOD_TABS_HEIGHT + FULLSCREEN_ELEMENT_SPACING + FULLSCREEN_CAPTION_HEIGHT + FULLSCREEN_ELEMENT_SPACING
    if (isCustomPeriod) reserved += FULLSCREEN_CUSTOM_PICKER_HEIGHT + FULLSCREEN_ELEMENT_SPACING
    if (historyLimited) reserved += FULLSCREEN_NOTICE_HEIGHT + FULLSCREEN_ELEMENT_SPACING
    if (showsMeasurementCount) reserved += FULLSCREEN_MEASUREMENT_COUNT_HEIGHT + FULLSCREEN_ELEMENT_SPACING
    if (showsChartLegend) reserved += FULLSCREEN_NOTICE_HEIGHT + FULLSCREEN_ELEMENT_SPACING
    return (availableHeight - reserved).coerceAtLeast(FULLSCREEN_CHART_MIN_HEIGHT)
}

// WBS 6.3/6.4: Metric Detail画面のChart/Records/Sources（requirements.md §18）の3タブ。
enum class DetailTab { CHART, RECORDS, SOURCES }

private fun DetailTab.labelRes(): Int =
    when (this) {
        DetailTab.CHART -> R.string.detail_tab_chart
        DetailTab.RECORDS -> R.string.detail_tab_records
        DetailTab.SOURCES -> R.string.detail_tab_sources
    }

@Composable
fun DetailTabs(tab: DetailTab, onTabChange: (DetailTab) -> Unit) {
    val tabs = DetailTab.entries
    PrimaryTabRow(selectedTabIndex = tabs.indexOf(tab)) {
        tabs.forEach { entry ->
            Tab(
                selected = entry == tab,
                onClick = { onTabChange(entry) },
                text = { Text(text = stringResource(id = entry.labelRes())) },
            )
        }
    }
}

// 7期間（1W/1M/3M/6M/1Y/ALL/Custom）は既存のSingleChoiceSegmentedButtonRow（3項目用）では
// 狭い画面で収まらないため、横スクロール可能なPrimaryScrollableTabRowに変更した（決定事項8）。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodTabs(period: GraphPeriod, onPeriodChange: (GraphPeriod) -> Unit) {
    val periods = GraphPeriod.entries
    val selectedIndex = periods.indexOf(period)
    PrimaryScrollableTabRow(selectedTabIndex = selectedIndex) {
        periods.forEach { entry ->
            Tab(
                selected = entry == period,
                onClick = { onPeriodChange(entry) },
                text = { Text(text = stringResource(id = entry.labelRes())) },
            )
        }
    }
}

// WBS 6.4（コードレビュー指摘への対応）: Sourcesタブ用。全件走査（`HealthConnectManager
// .readSourceRecordCounts()`等）は、Recordsタブのページング（見えている分だけ読む）と違い1回で
// 全件分のIPCを発行する。初版は状態をタブのComposableブランチの内側に置いており、他のタブへ切り替えて
// 戻るたびに状態が破棄され全件走査をやり直していた（Stepsのような数十万件規模では画面操作のたびに
// 無駄な再走査が走る）。呼び出し元のComposableが生存している間（画面回転までの間）は結果を保持し、
// `active`になった最初のタイミングでのみ`load()`を実行する。再試行は`retryKey`を変えることで行う
// （結果をnullに戻してから再取得する）。画面回転時はActivity再生成によりこの`remember`自体が破棄され、
// 他のタブ・既存のRecordsタブと同様に再取得される（D-035(4)と一貫した設計）。
//
// **`load()`の起動を一度トリガーした後は、`active`をLaunchedEffectのkeyに含めない**（2回目の
// コードレビューで発見・修正）。初版は`LaunchedEffect(active, retryKey)`としており、Sourcesタブの
// 読み込み中（Stepsだと最大約65秒）に別のタブへ切り替えると`active`の変化でこの実行中のコルーチンが
// キャンセルされ、`loadedForRetryKey`が更新されないまま戻ってしまっていた。その結果、タブを離れて
// 戻るとそれまでのIPCがすべて無駄になり、最初からやり直しになっていた（この修正が解決しようとした
// 問題がタブの往復では再現せず、読み込み中にタブを離れた場合だけ再現する、紛らわしいバグだった）。
// `started`（一度でも`active`になったか）を別のLaunchedEffectで一方向にラッチし、本体の読み込みは
// `started`と`retryKey`だけをkeyにすることで、タブを離れても画面から出るまで（または画面回転まで）は
// 読み込みを継続させる。
@Composable
fun <T> rememberLazyTabResult(active: Boolean, retryKey: Int, load: suspend () -> T): T? {
    var result by remember { mutableStateOf<T?>(null) }
    var loadedForRetryKey by remember { mutableStateOf<Int?>(null) }
    var started by remember { mutableStateOf(false) }

    LaunchedEffect(active) {
        if (active) started = true
    }

    LaunchedEffect(started, retryKey) {
        if (!started || loadedForRetryKey == retryKey) return@LaunchedEffect
        result = null
        result = load()
        loadedForRetryKey = retryKey
    }
    return result
}
