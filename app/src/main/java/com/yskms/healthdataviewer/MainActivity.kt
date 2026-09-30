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
import com.yskms.healthdataviewer.poc.HeartRateGraphScreen
import com.yskms.healthdataviewer.poc.SleepGraphScreen
import com.yskms.healthdataviewer.poc.StepsScreen
import com.yskms.healthdataviewer.poc.WeightGraphScreen
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

// WBS 6.1: ホーム画面（screen/home/HomeScreen.kt）を起点にし、指標カードのタップ先を既存
// poc/各Graph画面へ暫定的に接続する（WBS 6.2〜6.4で正式なDetail画面に置き換わるまでのブリッジ。
// Rawレコード一覧＝WeightRawRecordsScreen等は今回どこからも遷移させない）。
// 画面数がまだ少ないPoC段階ではNavigation Composeを導入せずローカル状態（PocScreen enum）で
// 分岐していたが、ホーム画面の追加でその前提が崩れたため、ここでNavigation Composeを導入した。
private const val ROUTE_HOME = "home"
private const val ROUTE_WEIGHT_GRAPH = "weight_graph"
private const val ROUTE_STEPS = "steps"
private const val ROUTE_HEART_RATE_GRAPH = "heart_rate_graph"
private const val ROUTE_SLEEP_GRAPH = "sleep_graph"

// カードの連続タップや「戻る」の連打への対策（コードレビュー指摘）。現在の画面（backstack先頭）が
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
    // WeightGraphScreen/HeartRateGraphScreen/SleepGraphScreenが引数として受け取る
    // historyPermissionGrantedのスナップショット。旧MainActivity（PocScreen分岐時代）と
    // 同じ設計で、遷移直前にHomeScreen側の最新の許可状態を書き込む。
    var historyPermissionGrantedSnapshot by rememberSaveable { mutableStateOf(false) }

    NavHost(navController = navController, startDestination = ROUTE_HOME, modifier = modifier) {
        composable(ROUTE_HOME) {
            HomeScreen(
                healthConnectManager = healthConnectManager,
                onOpenWeightGraph = { historyPermissionGranted ->
                    historyPermissionGrantedSnapshot = historyPermissionGranted
                    navController.navigateOnce(ROUTE_WEIGHT_GRAPH)
                },
                onOpenSteps = { navController.navigateOnce(ROUTE_STEPS) },
                onOpenHeartRateGraph = { historyPermissionGranted ->
                    historyPermissionGrantedSnapshot = historyPermissionGranted
                    navController.navigateOnce(ROUTE_HEART_RATE_GRAPH)
                },
                onOpenSleepGraph = { historyPermissionGranted ->
                    historyPermissionGrantedSnapshot = historyPermissionGranted
                    navController.navigateOnce(ROUTE_SLEEP_GRAPH)
                },
            )
        }
        composable(ROUTE_WEIGHT_GRAPH) {
            WeightGraphScreen(
                healthConnectManager = healthConnectManager,
                historyPermissionGranted = historyPermissionGrantedSnapshot,
                onBack = { navController.popBackStackOnce() },
            )
        }
        composable(ROUTE_STEPS) {
            StepsScreen(
                healthConnectManager = healthConnectManager,
                onBack = { navController.popBackStackOnce() },
            )
        }
        composable(ROUTE_HEART_RATE_GRAPH) {
            HeartRateGraphScreen(
                healthConnectManager = healthConnectManager,
                historyPermissionGranted = historyPermissionGrantedSnapshot,
                onBack = { navController.popBackStackOnce() },
            )
        }
        composable(ROUTE_SLEEP_GRAPH) {
            SleepGraphScreen(
                healthConnectManager = healthConnectManager,
                historyPermissionGranted = historyPermissionGrantedSnapshot,
                onBack = { navController.popBackStackOnce() },
            )
        }
    }
}
