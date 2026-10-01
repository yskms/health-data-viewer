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
import com.yskms.healthdataviewer.R

// WBS 6.3: Metric DetailのRecordsタブ（requirements.md §18）。4データ型（Weight/Steps/HeartRate/Sleep）
// で共通のComposableにし、型固有の行表示だけrowContentに委譲する。LazyColumnのitems()はkeyを省略し、
// 位置ベースの暗黙キーに任せる（重複も含めて全件表示するD-007の原則上、ページをまたいで同じ
// metadata.idが現れてもIllegalArgumentExceptionでクラッシュしないようにするため。docs/wbs.md 6.3）。
@Composable
fun <T : Any> RecordsTab(
    pagingItems: LazyPagingItems<T>,
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
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = stringResource(id = R.string.detail_error))
                    Button(onClick = { pagingItems.retry() }) {
                        Text(text = stringResource(id = R.string.detail_retry))
                    }
                }
            }
        }
        pagingItems.itemCount == 0 -> {
            Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(text = stringResource(id = R.string.detail_records_empty))
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
                // androidx.paging:paging-compose 3.5.1では、LazyPagingItems専用のitems(pagingItems)拡張は
                // 提供されず、通常のLazyListScope.items(count, ...)にitemCount/get(index)を渡す形になる
                // （公式のitemKey()/itemContentType()ヘルパーも同じ前提）。keyを省略するのは意図的
                // （このファイル先頭のコメント参照）。
                items(count = pagingItems.itemCount) { index ->
                    val record = pagingItems[index]
                    if (record != null) {
                        rowContent(record)
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
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(text = stringResource(id = R.string.detail_error))
                                Button(onClick = { pagingItems.retry() }) {
                                    Text(text = stringResource(id = R.string.detail_retry))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
