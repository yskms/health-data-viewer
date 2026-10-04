package com.yskms.healthdataviewer.healthconnect

import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord

sealed interface HrvRecordsResult {
    // historyLimited: BodyFatRecordsResultと同じ意味。
    data class Success(val records: List<HeartRateVariabilityRmssdRecord>, val historyLimited: Boolean) : HrvRecordsResult

    data object Failure : HrvRecordsResult
}
