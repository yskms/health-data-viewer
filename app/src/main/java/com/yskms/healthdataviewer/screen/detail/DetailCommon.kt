package com.yskms.healthdataviewer.screen.detail

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
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
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
@Composable
fun <T> rememberLazyTabResult(active: Boolean, retryKey: Int, load: suspend () -> T): T? {
    var result by remember { mutableStateOf<T?>(null) }
    var loadedForRetryKey by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(active, retryKey) {
        if (!active || loadedForRetryKey == retryKey) return@LaunchedEffect
        result = null
        result = load()
        loadedForRetryKey = retryKey
    }
    return result
}
