package com.yskms.healthdataviewer.healthconnect

import java.time.Instant

sealed interface OldestSleepSessionRecordResult {
    // OldestWeightRecordResult/OldestHeartRateRecordResultと同じ意味。time = nullは読み取れる範囲に
    // レコードが1件もないことを表す。時刻はSession開始時刻（startTime）。
    data class Success(val time: Instant?, val historyLimited: Boolean) : OldestSleepSessionRecordResult

    data object Failure : OldestSleepSessionRecordResult
}
