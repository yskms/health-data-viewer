package com.yskms.healthdataviewer.screen.detail

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yskms.healthdataviewer.R
import com.yskms.healthdataviewer.healthconnect.DataOriginNameResolver
import com.yskms.healthdataviewer.healthconnect.HeartRateSourceSampleCount
import com.yskms.healthdataviewer.healthconnect.HeartRateSourceSampleCountsResult
import com.yskms.healthdataviewer.healthconnect.SourceRecordCount
import com.yskms.healthdataviewer.healthconnect.SourceRecordCountsResult
import java.text.NumberFormat

// WBS 6.4: Metric DetailのSourcesタブ（requirements.md §18）。ソース一覧・件数の意味づけは
// データ型ごとに異なる（Weight/Steps/Sleep＝全件走査した正確なレコード件数、Heart Rate＝Aggregateの
// ソース別サンプル数、D-036）ため型ごとに別のComposableにするが、読み込み中・エラー・空状態の
// 表示パターンはRecordsTabと同じ形（ただしページングではなく一括読み込みのため、LazyPagingItemsでは
// なくプレーンなListを使う）。

@Composable
fun RecordCountSourcesTab(load: SourceRecordCountsResult?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    when (load) {
        null -> SourcesLoading(modifier)
        SourceRecordCountsResult.Failure -> SourcesError(onRetry, modifier)
        is SourceRecordCountsResult.Success ->
            if (load.counts.isEmpty()) {
                SourcesEmpty(historyLimited = load.historyLimited, modifier = modifier)
            } else {
                val context = LocalContext.current
                val locale = LocalLocale.current.platformLocale
                val numberFormat = remember(locale) { NumberFormat.getIntegerInstance(locale) }
                LazyColumn(
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (load.historyLimited) {
                        item { Text(text = stringResource(id = R.string.detail_history_limited_notice)) }
                    }
                    items(load.counts, key = { it.dataOrigin.packageName }) { row ->
                        SourceRecordCountRow(row = row, numberFormat = numberFormat, context = context)
                    }
                }
            }
    }
}

@Composable
private fun SourceRecordCountRow(row: SourceRecordCount, numberFormat: NumberFormat, context: Context) {
    val sourceName = remember(row.dataOrigin.packageName) { DataOriginNameResolver.resolve(context, row.dataOrigin.packageName) }
    Column {
        Text(text = sourceName, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(id = R.string.detail_sources_record_count, numberFormat.format(row.count)),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// WBS 6.4（D-036）: Heart Rateは正確なレコード件数を全件走査しない（OutOfMemoryErrorの実例、
// lessons.md 6.7）。Aggregateのソース別MEASUREMENTS_COUNT（サンプル数）を「サンプル数」と明記して
// 代替表示する。個別ソースのAggregate呼び出しが失敗した場合（failed=true）やsampleCountがnullの
// 場合は、件数を示さずソース名のみ表示する。
@Composable
fun HeartRateSourcesTab(load: HeartRateSourceSampleCountsResult?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    when (load) {
        null -> SourcesLoading(modifier)
        HeartRateSourceSampleCountsResult.Failure -> SourcesError(onRetry, modifier)
        is HeartRateSourceSampleCountsResult.Success ->
            if (load.counts.isEmpty()) {
                SourcesEmpty(historyLimited = load.historyLimited, modifier = modifier)
            } else {
                val context = LocalContext.current
                val locale = LocalLocale.current.platformLocale
                val numberFormat = remember(locale) { NumberFormat.getIntegerInstance(locale) }
                LazyColumn(
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (load.historyLimited) {
                        item { Text(text = stringResource(id = R.string.detail_history_limited_notice)) }
                    }
                    items(load.counts, key = { it.dataOrigin.packageName }) { row ->
                        HeartRateSourceSampleCountRow(row = row, numberFormat = numberFormat, context = context)
                    }
                }
            }
    }
}

// コードレビュー指摘: failed=trueの行を何も表示しない設計だと、「取得に失敗した」のか「仕様として
// 件数を出さない」のかが区別できず、D-036（ソース名のみ表示へのフォールバックは発動させない）とも
// 矛盾して見える。failedの行、およびsampleCountがnull（このソースはdataOriginsに含まれていた＝
// 範囲内に記録があるにもかかわらず、個別のAggregate呼び出しが値を返さなかった想定外のケース。
// poc/StepsScreen.formatOptionalTotal()と同じ考え方で、0埋めせず「取得できず」として扱う）の行は、
// 両方とも明示的に「取得できませんでした」と表示する。
@Composable
private fun HeartRateSourceSampleCountRow(row: HeartRateSourceSampleCount, numberFormat: NumberFormat, context: Context) {
    val sourceName = remember(row.dataOrigin.packageName) { DataOriginNameResolver.resolve(context, row.dataOrigin.packageName) }
    Column {
        Text(text = sourceName, style = MaterialTheme.typography.bodyLarge)
        val sampleCount = row.sampleCount
        val detailText =
            if (!row.failed && sampleCount != null) {
                stringResource(id = R.string.detail_heart_rate_source_sample_count, numberFormat.format(sampleCount))
            } else {
                stringResource(id = R.string.detail_heart_rate_source_sample_count_failed)
            }
        Text(text = detailText, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SourcesLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator()
            Text(text = stringResource(id = R.string.detail_loading))
        }
    }
}

@Composable
private fun SourcesError(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(id = R.string.detail_error))
            Button(onClick = onRetry) {
                Text(text = stringResource(id = R.string.detail_retry))
            }
        }
    }
}

@Composable
private fun SourcesEmpty(historyLimited: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (historyLimited) {
                Text(text = stringResource(id = R.string.detail_history_limited_notice))
            }
            Text(text = stringResource(id = R.string.detail_sources_empty))
        }
    }
}
