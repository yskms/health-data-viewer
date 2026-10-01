package com.yskms.healthdataviewer.screen.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import com.yskms.healthdataviewer.R
import com.yskms.healthdataviewer.healthconnect.PagedRecord

// WBS 6.3: Metric DetailのRecordsタブ（requirements.md §18）。4データ型（Weight/Steps/HeartRate/Sleep）
// で共通のComposableにし、型固有の行表示だけrowContentに委譲する。
//
// LazyColumnのitems()には(pageIndex, indexInPage)を組み合わせたキーを渡す（レコードの中身に頼らない、
// healthconnect/HealthRecordsPagingSource.ktのPagedRecord参照）。重複も含めて全件表示するD-007の原則上、
// ページをまたいで同じmetadata.idが現れうるため、metadata.idそのものをkeyにはできない。一方でkeyを
// 完全に省略すると、PagingConfig.maxSizeによる先頭ページの破棄やprependでの挿入が起きるたびに、
// Compose側がindexだけでスクロール位置を保持してしまい、画面内の表示内容が静かに入れ替わって
// 一部のレコードを見ないまま通過しうる（レビュー指摘。LazyColumnのkeyは、追加・削除があっても
// スクロール位置を正しい要素に追従させるための仕組み）。(pageIndex, indexInPage)はtoken境界が
// 固定されている限り破棄・再読込をまたいでも安定するため、この両方を満たせる。ただし「token境界が
// 固定されている限り」が前提で、閲覧中に過去時刻のレコードが後から同期・削除された場合はこの前提が
// 崩れ、prependで読み直したページの中身が最初と変わりうる（同じキーの行に別のレコードが表示される。
// 未対応、要検証）。
@Composable
fun <T : Any> RecordsTab(
    pagingItems: LazyPagingItems<PagedRecord<T>>,
    historyLimited: Boolean,
    rowContent: @Composable (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val refreshState = pagingItems.loadState.refresh
    when {
        refreshState is LoadState.Loading -> {
            Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        refreshState is LoadState.Error -> {
            Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                RecordsErrorMessage(error = refreshState.error, onRetry = { pagingItems.retry() })
            }
        }
        pagingItems.itemCount == 0 -> {
            Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // WBS 6.3コードレビュー指摘: 以前はこの分岐でhistoryLimitedの案内を出しておらず、
                    // 履歴読み取り権限がなく直近30日にもレコードがない場合に「記録なし」としか
                    // 表示されなかった（全件表示されていないことが最も伝わるべきケースで欠けていた）。
                    // Chartタブ（各*DetailScreen.kt）は空の集計結果でもこの案内を出しており、挙動を揃える。
                    if (historyLimited) {
                        Text(text = stringResource(id = R.string.detail_history_limited_notice))
                    }
                    Text(text = stringResource(id = R.string.detail_records_empty))
                }
            }
        }
        else -> {
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (historyLimited) {
                    item { Text(text = stringResource(id = R.string.detail_history_limited_notice)) }
                }
                items(
                    count = pagingItems.itemCount,
                    key = pagingItems.itemKey { "${it.pageIndex}:${it.indexInPage}" },
                ) { index ->
                    val paged = pagingItems[index]
                    if (paged != null) {
                        rowContent(paged.value)
                    }
                }
                val appendState = pagingItems.loadState.append
                if (appendState is LoadState.Loading) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
                if (appendState is LoadState.Error) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            RecordsErrorMessage(error = appendState.error, onRetry = { pagingItems.retry() })
                        }
                    }
                }
            }
        }
    }
}

// WBS 6.3コードレビュー指摘: LazyPagingItems.retry()は同じPagingSource・同じfilterで再試行するだけ
// なので、SecurityException（履歴読み取り権限の取り消し等）が原因の失敗では、再試行ボタンを押しても
// 権限状態が変わらない限り同じ失敗を繰り返す。汎用のエラー文言のまま再試行ボタンだけ出すと、
// ボタンが無意味であることが伝わらないため、SecurityExceptionの場合は専用の文言にする（D-035(1)の
// 「入り口で一度だけfilterを決め、ページング中は切り替えない」という設計自体は変更しない）。
@Composable
private fun RecordsErrorMessage(error: Throwable, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val messageRes = if (error is SecurityException) R.string.detail_error_permission else R.string.detail_error
        Text(text = stringResource(id = messageRes))
        Button(onClick = onRetry) {
            Text(text = stringResource(id = R.string.detail_retry))
        }
    }
}
