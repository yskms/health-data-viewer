package com.yskms.healthdataviewer.healthconnect

import androidx.health.connect.client.records.WeightRecord

sealed interface WeightRecordsResult {
    // historyLimited: 履歴読み取り権限がない（または取得中に失われた）ため、
    // 直近30日分にフォールバックして取得した結果であることを示す。
    data class Success(val records: List<WeightRecord>, val historyLimited: Boolean) : WeightRecordsResult

    data object Failure : WeightRecordsResult
}
