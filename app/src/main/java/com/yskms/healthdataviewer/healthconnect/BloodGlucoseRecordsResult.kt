package com.yskms.healthdataviewer.healthconnect

import androidx.health.connect.client.records.BloodGlucoseRecord

sealed interface BloodGlucoseRecordsResult {
    // historyLimited: BodyFatRecordsResultと同じ意味。
    data class Success(val records: List<BloodGlucoseRecord>, val historyLimited: Boolean) : BloodGlucoseRecordsResult

    data object Failure : BloodGlucoseRecordsResult
}
