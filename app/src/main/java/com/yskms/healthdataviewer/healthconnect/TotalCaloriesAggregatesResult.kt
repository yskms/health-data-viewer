package com.yskms.healthdataviewer.healthconnect

import java.time.LocalDateTime

// ActiveCaloriesAggregateBucket/ActiveCaloriesAggregatesResultと同じ形（ENERGY_TOTALもTotal
// Calories＝Activity系の公式重複処理を経た合計1系列のみ、requirements.md §22.2・D-043(1)）。
data class TotalCaloriesAggregateBucket(
    val periodStart: LocalDateTime,
    val periodEnd: LocalDateTime,
    val totalKilocalories: Double?,
)

sealed interface TotalCaloriesAggregatesResult {
    // historyLimited: WeightAggregatesResultのhistoryLimitedと同じ意味。
    data class Success(val buckets: List<TotalCaloriesAggregateBucket>, val historyLimited: Boolean) : TotalCaloriesAggregatesResult

    data object Failure : TotalCaloriesAggregatesResult
}
