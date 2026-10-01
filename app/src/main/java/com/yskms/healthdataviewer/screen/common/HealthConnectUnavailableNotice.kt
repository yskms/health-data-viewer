package com.yskms.healthdataviewer.screen.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yskms.healthdataviewer.R
import com.yskms.healthdataviewer.healthconnect.HealthConnectAvailability

// WBS 6.9: WBS 1.3の仮UI（MainActivityのステータス画面、WBS 6.1でHomeScreenに置き換え済み）は
// NOT_INSTALLED/UPDATE_REQUIREDのメッセージを出すだけで次の操作がなく、要件§5「Health Connect
// 未対応端末への案内」が未達成のまま残っていた。HomeScreen・SettingsScreenの両方に同じ文言・導線
// （Playストアの該当ページを開くボタン）を追加するにあたり、重複を避けてここに共通化した。
// NOT_INSTALLED/UPDATE_REQUIRED以外（INSTALLED）で呼び出すことは想定していない。
@Composable
fun HealthConnectUnavailableNotice(
    availability: HealthConnectAvailability,
    onOpenPlayStoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text =
                stringResource(
                    id =
                        if (availability == HealthConnectAvailability.UPDATE_REQUIRED) {
                            R.string.health_connect_update_required
                        } else {
                            R.string.health_connect_not_installed
                        },
                ),
        )
        Button(onClick = onOpenPlayStoreClick) {
            Text(text = stringResource(id = R.string.health_connect_open_play_store))
        }
    }
}
