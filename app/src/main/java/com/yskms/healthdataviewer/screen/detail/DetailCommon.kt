package com.yskms.healthdataviewer.screen.detail

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
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

// WBS 6.5（レビュー指摘への対応）: Chartタブの外側Column（全画面時はpaddingを狭める）は4データ型の
// DetailScreenで同一のため共通化した。全画面時も含め常にverticalScrollを付けたままにする（後述の
// 高さ見積もりが外れた場合の安全弁。verticalScrollとModifier.weight()は同居できないため、グラフ本体の
// 高さは`detailChartHeight()`で明示的に計算する方式にしている）。
@Composable
fun ChartTabColumn(fullScreenChart: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val horizontalPadding = if (fullScreenChart) 8.dp else 24.dp
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(horizontal = horizontalPadding)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

private val PORTRAIT_CHART_HEIGHT = 240.dp
private val FULLSCREEN_CHART_MIN_HEIGHT = 160.dp

// WBS 6.5（レビュー指摘への対応）: 全画面時、初版は`Modifier.weight(1f)`でグラフ本体を残り領域
// いっぱいに広げていたが、`ChartTabColumn`はverticalScroll付きのため`weight()`とは同居できず、採用でき
// ない（Composeの制約。ChartTabColumnのコメント参照）。代わりに、呼び出し元の`BoxWithConstraints`から
// 渡される実際の利用可能高さ`availableHeight`から、期間タブ・Custom選択時のピッカー・集計方法の注記・
// 履歴制限の注記・（HeartRateのみ）計測数テキストなどグラフ以外の要素が使うおおよその高さを差し引いて
// グラフの高さを決める。この見積もり（140dp）は固定値で、実際の内訳（要素の有無・フォントサイズ設定）
// によって過不足が出るが、ChartTabColumnのverticalScrollが安全弁になるため、正確な値である必要はない
// （見積もりが小さすぎて余ればグラフの下に余白ができるだけ、大きすぎて足りなければスクロールすれば
// 見える）。下限（160dp）も設け、極端に低い横画面（分割画面等）でもグラフの高さが0近くまで潰れることが
// ないようにしている。
fun detailChartHeight(fullScreenChart: Boolean, availableHeight: Dp): Dp =
    if (fullScreenChart) (availableHeight - 140.dp).coerceAtLeast(FULLSCREEN_CHART_MIN_HEIGHT) else PORTRAIT_CHART_HEIGHT

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
