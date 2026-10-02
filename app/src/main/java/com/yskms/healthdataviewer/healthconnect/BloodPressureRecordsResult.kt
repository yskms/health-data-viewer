package com.yskms.healthdataviewer.healthconnect

import androidx.health.connect.client.records.BloodPressureRecord

sealed interface BloodPressureRecordsResult {
    // historyLimited: 履歴読み取り権限がない（または取得中に失われた）ため、
    // 直近30日分にフォールバックして取得した結果であることを示す。
    data class Success(val records: List<BloodPressureRecord>, val historyLimited: Boolean) : BloodPressureRecordsResult

    data object Failure : BloodPressureRecordsResult
}
