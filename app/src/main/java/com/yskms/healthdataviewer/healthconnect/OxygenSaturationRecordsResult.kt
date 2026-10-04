package com.yskms.healthdataviewer.healthconnect

import androidx.health.connect.client.records.OxygenSaturationRecord

sealed interface OxygenSaturationRecordsResult {
    // historyLimited: BodyFatRecordsResultと同じ意味。
    data class Success(val records: List<OxygenSaturationRecord>, val historyLimited: Boolean) : OxygenSaturationRecordsResult

    data object Failure : OxygenSaturationRecordsResult
}
