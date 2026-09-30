package com.yskms.healthdataviewer.screen.detail

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
