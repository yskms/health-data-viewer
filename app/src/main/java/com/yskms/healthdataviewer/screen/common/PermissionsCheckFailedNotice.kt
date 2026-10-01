package com.yskms.healthdataviewer.screen.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yskms.healthdataviewer.R

// WBS 6.9（コードレビュー指摘）: 権限確認（getGrantedPermissions()）のIPC失敗時の案内＋再試行ボタン。
// HomeScreen・SettingsScreenで同じ見た目だったため共通化した（HealthConnectUnavailableNoticeと同じ理由）。
// 再試行の実処理（refreshPermissions()）は画面ごとに異なる（HomeScreenは他カードの再取得トリガーである
// resumeKeyの更新も伴う）ため、ここでは持たずonRetryClickで呼び出し元に委ねる。
@Composable
fun PermissionsCheckFailedNotice(onRetryClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(id = R.string.health_connect_check_failed_notice),
            modifier = Modifier.fillMaxWidth(0.6f),
        )
        Button(onClick = onRetryClick) {
            Text(text = stringResource(id = R.string.health_connect_retry))
        }
    }
}
