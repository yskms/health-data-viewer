package com.yskms.healthdataviewer.healthconnect

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord

// データ型を追加するときは、実際に読むデータ型のREAD_権限だけを増やす（lessons.md 1.1）。
// 履歴読み取り権限はデータ型を問わない横断的な権限のため、データ型の読み取り権限とは
// 別に許可状態を扱う（権限リクエストのダイアログ上も別画面で個別に許可・拒否できる。lessons.md 3.5）。
object HealthConnectPermissions {
    val WEIGHT_READ: String = HealthPermission.getReadPermission(WeightRecord::class)
    val STEPS_READ: String = HealthPermission.getReadPermission(StepsRecord::class)
    val HEART_RATE_READ: String = HealthPermission.getReadPermission(HeartRateRecord::class)
    const val HISTORY_READ: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
}
