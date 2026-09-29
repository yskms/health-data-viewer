package com.yskms.healthdataviewer

import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yskms.healthdataviewer.healthconnect.HealthConnectAvailability
import com.yskms.healthdataviewer.healthconnect.HealthConnectManager
import com.yskms.healthdataviewer.healthconnect.HealthConnectPermissions
import com.yskms.healthdataviewer.ui.theme.HealthDataViewerTheme
import kotlinx.coroutines.launch

// AppCompatDelegate.setApplicationLocales()（アプリ内言語切替、要件§21）は
// AppCompatActivityを前提とする（D-022）。
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val healthConnectManager = HealthConnectManager(applicationContext)
        setContent {
            HealthDataViewerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HealthConnectStatusScreen(
                        healthConnectManager = healthConnectManager,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
fun HealthConnectStatusScreen(
    healthConnectManager: HealthConnectManager,
    modifier: Modifier = Modifier,
) {
    var availability by remember { mutableStateOf(healthConnectManager.availability) }
    // nullは「未確認」（初回読み込み中、または直前の問い合わせが失敗した状態）を表す。
    var grantedPermissions by remember { mutableStateOf<Set<String>?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // 権限リクエストのActivityから戻ると必ずON_RESUMEが来るため、状態更新は下のLifecycleResumeEffectに
    // 任せる。ここで結果セットをそのまま反映すると、一部の権限だけをリクエストしたときに、
    // 今回リクエストしなかった（が実際は許可済みの）権限が一時的に「未許可」に見えてしまう。
    val requestPermissions =
        rememberLauncherForActivityResult(
            contract = healthConnectManager.createPermissionRequestContract(),
        ) {}

    // Health Connectの権限は設定画面などアプリの外から変わり得るため、起動時だけでなく
    // 画面復帰のたびに問い合わせ直す（lessons.md 3.1）。問い合わせが失敗した場合はnull
    // （＝未確認）に戻し、取り消し直後の失敗で古い許可状態を表示し続けないようにする。
    LifecycleResumeEffect(Unit) {
        availability = healthConnectManager.availability
        val job =
            if (availability == HealthConnectAvailability.INSTALLED) {
                coroutineScope.launch {
                    grantedPermissions = healthConnectManager.getGrantedPermissions()
                }
            } else {
                null
            }
        onPauseOrDispose { job?.cancel() }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(id = R.string.app_name))

        when (availability) {
            HealthConnectAvailability.INSTALLED -> {
                Text(text = stringResource(id = R.string.health_connect_available))
                Text(
                    text =
                        stringResource(
                            id =
                                permissionStatusTextRes(
                                    granted = grantedPermissions?.contains(HealthConnectPermissions.WEIGHT_READ),
                                    grantedRes = R.string.health_connect_permission_granted,
                                    notGrantedRes = R.string.health_connect_permission_not_granted,
                                ),
                        ),
                )
                Text(
                    text =
                        stringResource(
                            id =
                                permissionStatusTextRes(
                                    granted = grantedPermissions?.contains(HealthConnectPermissions.HISTORY_READ),
                                    grantedRes = R.string.health_connect_history_permission_granted,
                                    notGrantedRes = R.string.health_connect_history_permission_not_granted,
                                ),
                        ),
                )
                Button(
                    onClick = {
                        requestPermissions.launch(
                            setOf(HealthConnectPermissions.WEIGHT_READ, HealthConnectPermissions.HISTORY_READ),
                        )
                    },
                ) {
                    Text(text = stringResource(id = R.string.health_connect_request_permission))
                }
            }
            HealthConnectAvailability.UPDATE_REQUIRED ->
                Text(text = stringResource(id = R.string.health_connect_update_required))
            HealthConnectAvailability.NOT_INSTALLED ->
                Text(text = stringResource(id = R.string.health_connect_not_installed))
        }
    }
}

private fun permissionStatusTextRes(granted: Boolean?, grantedRes: Int, notGrantedRes: Int): Int =
    when (granted) {
        true -> grantedRes
        false -> notGrantedRes
        null -> R.string.health_connect_checking
    }
