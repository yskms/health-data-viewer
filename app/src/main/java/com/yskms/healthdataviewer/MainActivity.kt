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
import com.yskms.healthdataviewer.screen.detail.ActiveCaloriesDetailScreen
import com.yskms.healthdataviewer.screen.detail.BloodGlucoseDetailScreen
import com.yskms.healthdataviewer.screen.detail.BloodPressureDetailScreen
import com.yskms.healthdataviewer.screen.detail.BodyFatDetailScreen
import com.yskms.healthdataviewer.screen.detail.DistanceDetailScreen
import com.yskms.healthdataviewer.screen.detail.ExerciseDetailScreen
import com.yskms.healthdataviewer.screen.detail.HeartRateDetailScreen
import com.yskms.healthdataviewer.screen.detail.HrvDetailScreen
import com.yskms.healthdataviewer.screen.detail.NutritionDetailScreen
import com.yskms.healthdataviewer.screen.detail.OxygenSaturationDetailScreen
import com.yskms.healthdataviewer.screen.detail.RestingHeartRateDetailScreen
import com.yskms.healthdataviewer.screen.detail.SleepDetailScreen
import com.yskms.healthdataviewer.screen.detail.StepsDetailScreen
import com.yskms.healthdataviewer.screen.detail.TotalCaloriesDetailScreen
import com.yskms.healthdataviewer.screen.detail.WeightDetailScreen
import com.yskms.healthdataviewer.screen.home.HomeScreen
import com.yskms.healthdataviewer.screen.settings.SettingsScreen
import com.yskms.healthdataviewer.settings.UserSettingsRepository
import com.yskms.healthdataviewer.ui.theme.HealthDataViewerTheme

// AppCompatDelegate.setApplicationLocales()（アプリ内言語切替、要件§21）は
// AppCompatActivityを前提とする（D-022）。
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val healthConnectManager = HealthConnectManager(applicationContext)
        // プロセス生存期間中ただ1つのインスタンス（HealthDataViewerApplication参照）。
        // Activity再生成のたびに作り直さない。
        val userSettingsRepository = (application as HealthDataViewerApplication).userSettingsRepository
        setContent {
            HealthDataViewerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainNavHost(
                        healthConnectManager = healthConnectManager,
                        userSettingsRepository = userSettingsRepository,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

// WBS 6.2: ホーム画面（screen/home/HomeScreen.kt）を起点にし、指標カードのタップ先を
// screen/detail配下の正式なDetail画面へ接続する（D-033のpoc/各Graph画面への暫定的なブリッジを
// 置き換えた）。WBS 6.3で各Detail画面にRecordsタブ（ページング生レコード一覧）を追加し、旧Raw一覧
// PoC画面（WeightRawRecordsScreen等）は削除した。Steps比較PoC＝poc/StepsScreenは、ソース別件数の
// 仕様を確定するWBS 6.4まで引き続きどこからも遷移させない。WBS 6.6でHomeScreenから設定画面への
// 導線を追加した。
private const val ROUTE_HOME = "home"
private const val ROUTE_WEIGHT_DETAIL = "weight_detail"
private const val ROUTE_STEPS_DETAIL = "steps_detail"
private const val ROUTE_HEART_RATE_DETAIL = "heart_rate_detail"
private const val ROUTE_RESTING_HEART_RATE_DETAIL = "resting_heart_rate_detail"
private const val ROUTE_SLEEP_DETAIL = "sleep_detail"
private const val ROUTE_DISTANCE_DETAIL = "distance_detail"
private const val ROUTE_ACTIVE_CALORIES_DETAIL = "active_calories_detail"
private const val ROUTE_TOTAL_CALORIES_DETAIL = "total_calories_detail"
private const val ROUTE_BLOOD_PRESSURE_DETAIL = "blood_pressure_detail"
private const val ROUTE_BODY_FAT_DETAIL = "body_fat_detail"
private const val ROUTE_HRV_DETAIL = "hrv_detail"
private const val ROUTE_OXYGEN_SATURATION_DETAIL = "oxygen_saturation_detail"
private const val ROUTE_BLOOD_GLUCOSE_DETAIL = "blood_glucose_detail"
private const val ROUTE_EXERCISE_DETAIL = "exercise_detail"
private const val ROUTE_NUTRITION_DETAIL = "nutrition_detail"
private const val ROUTE_SETTINGS = "settings"

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
private fun MainNavHost(
    healthConnectManager: HealthConnectManager,
    userSettingsRepository: UserSettingsRepository,
    modifier: Modifier = Modifier,
) {
    val navController: NavHostController = rememberNavController()
    // WeightDetailScreen/StepsDetailScreen/HeartRateDetailScreen/SleepDetailScreen/DistanceDetailScreenが
    // 引数として受け取るhistoryPermissionGrantedのスナップショット。遷移直前にHomeScreen側の最新の
    // 許可状態を書き込む（D-029/D-030はStepsの比較PoC画面専用の設計判断で、Steps Detail画面には
    // 適用しない。他のデータ型と同じスナップショット方式に揃える）。
    var historyPermissionGrantedSnapshot by rememberSaveable { mutableStateOf(false) }

    NavHost(navController = navController, startDestination = ROUTE_HOME, modifier = modifier) {
        composable(ROUTE_HOME) {
            HomeScreen(
                healthConnectManager = healthConnectManager,
                userSettingsRepository = userSettingsRepository,
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
                onOpenRestingHeartRateGraph = { historyPermissionGranted ->
                    historyPermissionGrantedSnapshot = historyPermissionGranted
                    navController.navigateOnce(ROUTE_RESTING_HEART_RATE_DETAIL)
                },
                onOpenSleepGraph = { historyPermissionGranted ->
                    historyPermissionGrantedSnapshot = historyPermissionGranted
                    navController.navigateOnce(ROUTE_SLEEP_DETAIL)
                },
                onOpenDistance = { historyPermissionGranted ->
                    historyPermissionGrantedSnapshot = historyPermissionGranted
                    navController.navigateOnce(ROUTE_DISTANCE_DETAIL)
                },
                onOpenActiveCalories = { historyPermissionGranted ->
                    historyPermissionGrantedSnapshot = historyPermissionGranted
                    navController.navigateOnce(ROUTE_ACTIVE_CALORIES_DETAIL)
                },
                onOpenTotalCalories = { historyPermissionGranted ->
                    historyPermissionGrantedSnapshot = historyPermissionGranted
                    navController.navigateOnce(ROUTE_TOTAL_CALORIES_DETAIL)
                },
                onOpenBloodPressure = { historyPermissionGranted ->
                    historyPermissionGrantedSnapshot = historyPermissionGranted
                    navController.navigateOnce(ROUTE_BLOOD_PRESSURE_DETAIL)
                },
                onOpenBodyFatGraph = { historyPermissionGranted ->
                    historyPermissionGrantedSnapshot = historyPermissionGranted
                    navController.navigateOnce(ROUTE_BODY_FAT_DETAIL)
                },
                onOpenHrvGraph = { historyPermissionGranted ->
                    historyPermissionGrantedSnapshot = historyPermissionGranted
                    navController.navigateOnce(ROUTE_HRV_DETAIL)
                },
                onOpenOxygenSaturationGraph = { historyPermissionGranted ->
                    historyPermissionGrantedSnapshot = historyPermissionGranted
                    navController.navigateOnce(ROUTE_OXYGEN_SATURATION_DETAIL)
                },
                onOpenBloodGlucoseGraph = { historyPermissionGranted ->
                    historyPermissionGrantedSnapshot = historyPermissionGranted
                    navController.navigateOnce(ROUTE_BLOOD_GLUCOSE_DETAIL)
                },
                onOpenExerciseGraph = { historyPermissionGranted ->
                    historyPermissionGrantedSnapshot = historyPermissionGranted
                    navController.navigateOnce(ROUTE_EXERCISE_DETAIL)
                },
                onOpenNutrition = { historyPermissionGranted ->
                    historyPermissionGrantedSnapshot = historyPermissionGranted
                    navController.navigateOnce(ROUTE_NUTRITION_DETAIL)
                },
                onOpenSettings = { navController.navigateOnce(ROUTE_SETTINGS) },
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                healthConnectManager = healthConnectManager,
                userSettingsRepository = userSettingsRepository,
                onBack = { navController.popBackStackOnce() },
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
        composable(ROUTE_RESTING_HEART_RATE_DETAIL) {
            RestingHeartRateDetailScreen(
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
        composable(ROUTE_DISTANCE_DETAIL) {
            DistanceDetailScreen(
                healthConnectManager = healthConnectManager,
                historyPermissionGranted = historyPermissionGrantedSnapshot,
                onBack = { navController.popBackStackOnce() },
            )
        }
        composable(ROUTE_ACTIVE_CALORIES_DETAIL) {
            ActiveCaloriesDetailScreen(
                healthConnectManager = healthConnectManager,
                historyPermissionGranted = historyPermissionGrantedSnapshot,
                onBack = { navController.popBackStackOnce() },
            )
        }
        composable(ROUTE_TOTAL_CALORIES_DETAIL) {
            TotalCaloriesDetailScreen(
                healthConnectManager = healthConnectManager,
                historyPermissionGranted = historyPermissionGrantedSnapshot,
                onBack = { navController.popBackStackOnce() },
            )
        }
        composable(ROUTE_BLOOD_PRESSURE_DETAIL) {
            BloodPressureDetailScreen(
                healthConnectManager = healthConnectManager,
                historyPermissionGranted = historyPermissionGrantedSnapshot,
                onBack = { navController.popBackStackOnce() },
            )
        }
        composable(ROUTE_BODY_FAT_DETAIL) {
            BodyFatDetailScreen(
                healthConnectManager = healthConnectManager,
                historyPermissionGranted = historyPermissionGrantedSnapshot,
                onBack = { navController.popBackStackOnce() },
            )
        }
        composable(ROUTE_HRV_DETAIL) {
            HrvDetailScreen(
                healthConnectManager = healthConnectManager,
                historyPermissionGranted = historyPermissionGrantedSnapshot,
                onBack = { navController.popBackStackOnce() },
            )
        }
        composable(ROUTE_OXYGEN_SATURATION_DETAIL) {
            OxygenSaturationDetailScreen(
                healthConnectManager = healthConnectManager,
                historyPermissionGranted = historyPermissionGrantedSnapshot,
                onBack = { navController.popBackStackOnce() },
            )
        }
        composable(ROUTE_BLOOD_GLUCOSE_DETAIL) {
            BloodGlucoseDetailScreen(
                healthConnectManager = healthConnectManager,
                historyPermissionGranted = historyPermissionGrantedSnapshot,
                onBack = { navController.popBackStackOnce() },
            )
        }
        composable(ROUTE_EXERCISE_DETAIL) {
            ExerciseDetailScreen(
                healthConnectManager = healthConnectManager,
                historyPermissionGranted = historyPermissionGrantedSnapshot,
                onBack = { navController.popBackStackOnce() },
            )
        }
        composable(ROUTE_NUTRITION_DETAIL) {
            NutritionDetailScreen(
                healthConnectManager = healthConnectManager,
                historyPermissionGranted = historyPermissionGrantedSnapshot,
                onBack = { navController.popBackStackOnce() },
            )
        }
    }
}
