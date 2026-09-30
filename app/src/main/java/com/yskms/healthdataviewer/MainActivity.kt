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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yskms.healthdataviewer.healthconnect.HealthConnectAvailability
import com.yskms.healthdataviewer.healthconnect.HealthConnectManager
import com.yskms.healthdataviewer.healthconnect.HealthConnectPermissions
import com.yskms.healthdataviewer.poc.StepsScreen
import com.yskms.healthdataviewer.poc.WeightGraphScreen
import com.yskms.healthdataviewer.poc.WeightRawRecordsScreen
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
                    MainScreen(
                        healthConnectManager = healthConnectManager,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

private enum class PocScreen { STATUS, WEIGHT_RAW_RECORDS, WEIGHT_GRAPH, STEPS }

@Composable
fun MainScreen(healthConnectManager: HealthConnectManager, modifier: Modifier = Modifier) {
    // WBS 2.1〜2.4（PoC 1）専用の画面切り替え。画面数がまだ少ないPoC段階のため、Navigation Composeは
    // 導入せずローカル状態で分岐する（導入は画面が本格的に増えるMVP実装時、WBS 6で検討）。
    // rememberSaveableで保持する: remember だと画面回転（Activity再生成）でどちらも初期値に戻り、
    // 一覧を開いたまま回転するとステータス画面に戻ってしまう（レビュー指摘、実機で再現確認済み）。
    var screen by rememberSaveable { mutableStateOf(PocScreen.STATUS) }
    // Weightの2画面（画面遷移時のスナップショットを受け取る設計）でのみ使う。Steps（StepsScreen）は
    // D-030により、この値を受け取らず自身で毎回問い合わせ直す。
    var historyPermissionGrantedForPocScreens by rememberSaveable { mutableStateOf(false) }

    when (screen) {
        PocScreen.WEIGHT_RAW_RECORDS ->
            WeightRawRecordsScreen(
                healthConnectManager = healthConnectManager,
                historyPermissionGranted = historyPermissionGrantedForPocScreens,
                onBack = { screen = PocScreen.STATUS },
                modifier = modifier,
            )
        PocScreen.WEIGHT_GRAPH ->
            WeightGraphScreen(
                healthConnectManager = healthConnectManager,
                historyPermissionGranted = historyPermissionGrantedForPocScreens,
                onBack = { screen = PocScreen.STATUS },
                modifier = modifier,
            )
        PocScreen.STEPS ->
            // StepsScreenはD-030により、履歴読み取り権限の状態を画面遷移時のスナップショットとして
            // 受け取らず、自身のLaunchedEffect内で毎回問い合わせ直す（lessons.md 3.1）。
            StepsScreen(
                healthConnectManager = healthConnectManager,
                onBack = { screen = PocScreen.STATUS },
                modifier = modifier,
            )
        PocScreen.STATUS ->
            HealthConnectStatusScreen(
                healthConnectManager = healthConnectManager,
                onOpenWeightRawRecords = { historyPermissionGranted ->
                    historyPermissionGrantedForPocScreens = historyPermissionGranted
                    screen = PocScreen.WEIGHT_RAW_RECORDS
                },
                onOpenWeightGraph = { historyPermissionGranted ->
                    historyPermissionGrantedForPocScreens = historyPermissionGranted
                    screen = PocScreen.WEIGHT_GRAPH
                },
                onOpenSteps = { screen = PocScreen.STEPS },
                modifier = modifier,
            )
    }
}

@Composable
fun HealthConnectStatusScreen(
    healthConnectManager: HealthConnectManager,
    onOpenWeightRawRecords: (historyPermissionGranted: Boolean) -> Unit,
    onOpenWeightGraph: (historyPermissionGranted: Boolean) -> Unit,
    onOpenSteps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var availability by remember { mutableStateOf(healthConnectManager.availability) }
    // nullは「未確認」（初回読み込み中、または直前の問い合わせが失敗した状態）を表す。
    var grantedPermissions by remember { mutableStateOf<Set<String>?>(null) }
    // nullは「未確認」。falseで初期化すると、LifecycleResumeEffectが走るまでの最初のフレームで
    // 「この端末では対応していない」と誤表示されてしまう（レビュー指摘）。
    var historyFeatureAvailable by remember { mutableStateOf<Boolean?>(null) }
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
    // isHistoryReadFeatureAvailableもHealth Connectのアップデートで変わり得るため同様に再取得する。
    LifecycleResumeEffect(Unit) {
        availability = healthConnectManager.availability
        historyFeatureAvailable =
            if (availability == HealthConnectAvailability.INSTALLED) {
                healthConnectManager.isHistoryReadFeatureAvailable
            } else {
                null
            }
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
                val weightGranted = grantedPermissions?.contains(HealthConnectPermissions.WEIGHT_READ)
                val stepsGranted = grantedPermissions?.contains(HealthConnectPermissions.STEPS_READ)
                Text(text = stringResource(id = R.string.health_connect_available))
                Text(
                    text =
                        stringResource(
                            id =
                                permissionStatusTextRes(
                                    granted = weightGranted,
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
                                    granted = stepsGranted,
                                    grantedRes = R.string.health_connect_steps_permission_granted,
                                    notGrantedRes = R.string.health_connect_steps_permission_not_granted,
                                ),
                        ),
                )
                Text(
                    text =
                        stringResource(
                            id =
                                historyPermissionStatusTextRes(
                                    featureAvailable = historyFeatureAvailable,
                                    granted = grantedPermissions?.contains(HealthConnectPermissions.HISTORY_READ),
                                ),
                        ),
                )
                Button(
                    onClick = {
                        // 端末のHealth Connectが履歴読み取りに対応していない場合、HISTORY_READを
                        // リクエストセットから除外する。対応していない権限を含めて何度もリクエストしても
                        // 「未許可」から抜け出せないままになるため（WBS 2.1）。
                        val permissions =
                            buildSet {
                                add(HealthConnectPermissions.WEIGHT_READ)
                                add(HealthConnectPermissions.STEPS_READ)
                                if (historyFeatureAvailable == true) add(HealthConnectPermissions.HISTORY_READ)
                            }
                        requestPermissions.launch(permissions)
                    },
                ) {
                    Text(text = stringResource(id = R.string.health_connect_request_permission))
                }
                if (weightGranted == true) {
                    Button(
                        onClick = {
                            onOpenWeightRawRecords(grantedPermissions?.contains(HealthConnectPermissions.HISTORY_READ) == true)
                        },
                    ) {
                        Text(text = stringResource(id = R.string.poc_weight_open_button))
                    }
                    Button(
                        onClick = {
                            onOpenWeightGraph(grantedPermissions?.contains(HealthConnectPermissions.HISTORY_READ) == true)
                        },
                    ) {
                        Text(text = stringResource(id = R.string.poc_weight_graph_open_button))
                    }
                }
                if (stepsGranted == true) {
                    Button(onClick = onOpenSteps) {
                        Text(text = stringResource(id = R.string.poc_steps_open_button))
                    }
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

private fun historyPermissionStatusTextRes(featureAvailable: Boolean?, granted: Boolean?): Int =
    when {
        // featureAvailableが未確認のうちは「未確認」を優先する。falseだと確定してから初めて、
        // ユーザー操作で解決できる「未許可」とは区別した固定の案内を出す。
        featureAvailable == null -> R.string.health_connect_checking
        !featureAvailable -> R.string.health_connect_history_not_supported
        granted == true -> R.string.health_connect_history_permission_granted
        granted == false -> R.string.health_connect_history_permission_not_granted
        else -> R.string.health_connect_checking
    }
