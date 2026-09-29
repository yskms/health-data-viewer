package com.yskms.healthdataviewer.healthconnect

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord

// データ型を追加するときは、実際に読むデータ型のREAD_権限だけを増やす（lessons.md 1.1）。
object HealthConnectPermissions {
    val WEIGHT: Set<String> =
        setOf(
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
        )
}
