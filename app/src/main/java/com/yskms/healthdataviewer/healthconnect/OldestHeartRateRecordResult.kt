package com.yskms.healthdataviewer.healthconnect

import java.time.Instant

sealed interface OldestHeartRateRecordResult {
    // OldestWeightRecordResultと同じ意味。time = nullは読み取れる範囲にレコードが1件もないことを表す。
    data class Success(val time: Instant?, val historyLimited: Boolean) : OldestHeartRateRecordResult

    data object Failure : OldestHeartRateRecordResult
}
