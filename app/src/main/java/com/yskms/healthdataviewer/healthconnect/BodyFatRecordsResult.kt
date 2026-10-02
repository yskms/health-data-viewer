package com.yskms.healthdataviewer.healthconnect

import androidx.health.connect.client.records.BodyFatRecord

sealed interface BodyFatRecordsResult {
    // historyLimited: 履歴読み取り権限がない（または取得中に失われた）ため、
    // 直近30日分にフォールバックして取得した結果であることを示す（WeightRecordsResultと同じ意味）。
    data class Success(val records: List<BodyFatRecord>, val historyLimited: Boolean) : BodyFatRecordsResult

    data object Failure : BodyFatRecordsResult
}
