package com.yskms.healthdataviewer.screen.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yskms.healthdataviewer.R
import com.yskms.healthdataviewer.healthconnect.HealthConnectAvailability
import com.yskms.healthdataviewer.healthconnect.HealthConnectManager

// WBS 6.9: WBS 1.3の仮UI（MainActivityのステータス画面、WBS 6.1でHomeScreenに置き換え済み）は
// UPDATE_REQUIRED/UNAVAILABLEのメッセージを出すだけで次の操作がなく、要件§5「Health Connect
// 未対応端末への案内」が未達成のまま残っていた。HomeScreen・SettingsScreenの両方に同じ文言・導線が
// 必要だったため共通化した。
//
// コードレビュー指摘（当初の実装の誤り）: 当初はUPDATE_REQUIRED・UNAVAILABLE（旧名NOT_INSTALLED）の
// 両方にPlayストアへ誘導するボタンを出していたが、connect-client 1.1.0のHealthConnectClient
// .getSdkStatus()をbytecodeレベルで確認した結果、Playストアへの誘導で実際に解決するのはUPDATE_REQUIRED
// （API 28〜33限定、未インストール・無効化・バージョン古いのいずれか）のときだけだと判明した。
// UNAVAILABLE（minSdk 28のこのアプリでは実質的にAPI 34以降のwork profile／system service不在でしか
// 発生しない）は、Playストアでインストール操作をしても解決しないシステム側の制約のため、ボタンを
// 出さずテキストのみ表示する（HealthConnectAvailability.kt、HealthConnectManager.availabilityの
// コメント、lessons.md 6.23参照）。
@Composable
fun HealthConnectUnavailableNotice(
    availability: HealthConnectAvailability,
    healthConnectManager: HealthConnectManager,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var openFailed by remember { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text =
                stringResource(
                    id =
                        if (availability == HealthConnectAvailability.UPDATE_REQUIRED) {
                            R.string.health_connect_update_required
                        } else {
                            R.string.health_connect_unavailable
                        },
                ),
        )
        if (availability == HealthConnectAvailability.UPDATE_REQUIRED) {
            Button(onClick = {
                // コードレビュー指摘: setPackage()でPlayストアアプリを明示的に指定したIntentは、
                // Playストアが無効化・未搭載の端末では解決に失敗する（lessons.md 6.22のパッケージ
                // 可視性の話とは別の問題）。失敗した場合はsetPackage()なしのブラウザ経由Intentへ
                // フォールバックし、それも失敗した場合のみ案内を表示する（無言で何も起きないことを防ぐ）。
                val openedWithMarket = runCatching { context.startActivity(healthConnectManager.createOpenInPlayStoreIntent()) }.isSuccess
                openFailed =
                    if (openedWithMarket) {
                        false
                    } else {
                        runCatching { context.startActivity(healthConnectManager.createOpenInPlayStoreWebIntent()) }.isFailure
                    }
            }) {
                Text(text = stringResource(id = R.string.health_connect_open_play_store))
            }
            if (openFailed) {
                Text(
                    text = stringResource(id = R.string.health_connect_open_play_store_failed),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
