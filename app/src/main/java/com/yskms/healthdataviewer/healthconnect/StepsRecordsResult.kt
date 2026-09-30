package com.yskms.healthdataviewer.healthconnect

import androidx.health.connect.client.records.StepsRecord

sealed interface StepsRecordsResult {
    // WeightRecordsResultと異なり、historyLimitedはここでは持たない。Rawとaggregateの範囲を
    // 完全に一致させるため、範囲（直近30日にするかどうかの判断を含む）は呼び出し元
    // （StepsScreen）が決めてTimeRangeFilterとして渡す設計にしたため（HealthConnectManager.
    // readStepsRecords()のコメント参照）、フォールバックの有無をこの結果型で表す必要がない。
    data class Success(val records: List<StepsRecord>) : StepsRecordsResult

    data object Failure : StepsRecordsResult
}
