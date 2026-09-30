package com.yskms.healthdataviewer

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.yskms.healthdataviewer.healthconnect.HealthConnectManager
import com.yskms.healthdataviewer.screen.detail.HeartRateDetailScreen
import com.yskms.healthdataviewer.screen.detail.SleepDetailScreen
import com.yskms.healthdataviewer.screen.detail.StepsDetailScreen
import com.yskms.healthdataviewer.screen.detail.WeightDetailScreen
import com.yskms.healthdataviewer.screen.home.HomeScreen
import com.yskms.healthdataviewer.ui.theme.HealthDataViewerTheme

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
                    MainNavHost(
                        healthConnectManager = healthConnectManager,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

// WBS 6.2: ホーム画面（screen/home/HomeScreen.kt）を起点にし、指標カードのタップ先を
// screen/detail配下の正式なDetail画面へ接続する（D-033のpoc/各Graph画面への暫定的なブリッジを
// 置き換えた。Rawレコード一覧＝WeightRawRecordsScreen等・Steps比較PoC＝poc/StepsScreenは
// 引き続きどこからも遷移させない。WBS 6.3/6.4で扱う）。
private const val ROUTE_HOME = "home"
private const val ROUTE_WEIGHT_DETAIL = "weight_detail"
private const val ROUTE_STEPS_DETAIL = "steps_detail"
private const val ROUTE_HEART_RATE_DETAIL = "heart_rate_detail"
private const val ROUTE_SLEEP_DETAIL = "sleep_detail"

// カードの連続タップや「戻る」の連打への対策（レビュー指摘）。現在の画面（backstack先頭）が
// まだRESUMEDになっていない間はnavigate()/popBackStack()を呼ばない。ガードなしだと、素早く2回
// タップした場合に同じGraph画面が2つ積まれたり、「戻る」の連打でpopBackStack()が2回処理されて
// 開始画面（home）を超えてpopしてしまい空白画面になり得る（Navigation Composeで知られた問題）。
private fun NavHostController.navigateOnce(route: String) {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
        navigate(route)
    }
}

private fun NavHostController.popBackStackOnce() {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
        popBackStack()
    }
}

@Composable
private fun MainNavHost(healthConnectManager: HealthConnectManager, modifier: Modifier = Modifier) {
    val navController: NavHostController = rememberNavController()
    // WeightDetailScreen/StepsDetailScreen/HeartRateDetailScreen/SleepDetailScreenが引数として
    // 受け取るhistoryPermissionGrantedのスナップショット。遷移直前にHomeScreen側の最新の許可状態を
    // 書き込む（D-029/D-030はStepsの比較PoC画面専用の設計判断で、Steps Detail画面には適用しない。
    // 他の3データ型と同じスナップショット方式に揃える）。
    var historyPermissionGrantedSnapshot by rememberSaveable { mutableStateOf(false) }

    NavHost(navController = navController, startDestination = ROUTE_HOME, modifier = modifier) {
        composable(ROUTE_HOME) {
            HomeScreen(
                healthConnectManager = healthConnectManager,
                onOpenWeightGraph = { historyPermissionGranted ->
                    historyPermissionGrantedSnapshot = historyPermissionGranted
                    navController.navigateOnce(ROUTE_WEIGHT_DETAIL)
                },
                onOpenSteps = { historyPermissionGranted ->
                    historyPermissionGrantedSnapshot = historyPermissionGranted
                    navController.navigateOnce(ROUTE_STEPS_DETAIL)
                },
                onOpenHeartRateGraph = { historyPermissionGranted ->
                    historyPermissionGrantedSnapshot = historyPermissionGranted
                    navController.navigateOnce(ROUTE_HEART_RATE_DETAIL)
                },
                onOpenSleepGraph = { historyPermissionGranted ->
                    historyPermissionGrantedSnapshot = historyPermissionGranted
                    navController.navigateOnce(ROUTE_SLEEP_DETAIL)
                },
            )
        }
        composable(ROUTE_WEIGHT_DETAIL) {
            WeightDetailScreen(
                healthConnectManager = healthConnectManager,
                historyPermissionGranted = historyPermissionGrantedSnapshot,
                onBack = { navController.popBackStackOnce() },
            )
        }
        composable(ROUTE_STEPS_DETAIL) {
            StepsDetailScreen(
                healthConnectManager = healthConnectManager,
                historyPermissionGranted = historyPermissionGrantedSnapshot,
                onBack = { navController.popBackStackOnce() },
            )
        }
        composable(ROUTE_HEART_RATE_DETAIL) {
            HeartRateDetailScreen(
                healthConnectManager = healthConnectManager,
                historyPermissionGranted = historyPermissionGrantedSnapshot,
                onBack = { navController.popBackStackOnce() },
            )
        }
        composable(ROUTE_SLEEP_DETAIL) {
            SleepDetailScreen(
                healthConnectManager = healthConnectManager,
                historyPermissionGranted = historyPermissionGrantedSnapshot,
                onBack = { navController.popBackStackOnce() },
            )
        }
    }
}
