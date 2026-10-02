package com.yskms.healthdataviewer.healthconnect

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord

// データ型を追加するときは、実際に読むデータ型のREAD_権限だけを増やす（lessons.md 1.1）。
// 履歴読み取り権限はデータ型を問わない横断的な権限のため、データ型の読み取り権限とは
// 別に許可状態を扱う（権限リクエストのダイアログ上も別画面で個別に許可・拒否できる。lessons.md 3.5）。
//
// ACTIVE_CALORIES_READ/TOTAL_CALORIES_READ（WBS 6.10、D-043）: ActiveCaloriesBurnedRecord/
// TotalCaloriesBurnedRecordはHealth Connect上で別々のRecord型のため、getReadPermission()が
// 返す権限文字列もREAD_ACTIVE_CALORIES_BURNED/READ_TOTAL_CALORIES_BURNEDとそれぞれ別になる。
// 「android.permission.health.READ_」+内部のRECORD_TYPE_TO_PERMISSIONマップから引いた接尾辞、
// という組み立てロジック自体はjavap逆コンパイルで確認できたが、接尾辞の値自体はマップの初期化コードが
// javapのメソッド逆アセンブル出力に現れず、.classバイナリの文字列直接検索（grep -a）で確認した
// （D-043(5)）。
object HealthConnectPermissions {
    val WEIGHT_READ: String = HealthPermission.getReadPermission(WeightRecord::class)
    val STEPS_READ: String = HealthPermission.getReadPermission(StepsRecord::class)
    val HEART_RATE_READ: String = HealthPermission.getReadPermission(HeartRateRecord::class)
    val RESTING_HEART_RATE_READ: String = HealthPermission.getReadPermission(RestingHeartRateRecord::class)
    val SLEEP_READ: String = HealthPermission.getReadPermission(SleepSessionRecord::class)
    val DISTANCE_READ: String = HealthPermission.getReadPermission(DistanceRecord::class)
    val ACTIVE_CALORIES_READ: String = HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
    val TOTAL_CALORIES_READ: String = HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
    val BLOOD_PRESSURE_READ: String = HealthPermission.getReadPermission(BloodPressureRecord::class)
    const val HISTORY_READ: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
}
