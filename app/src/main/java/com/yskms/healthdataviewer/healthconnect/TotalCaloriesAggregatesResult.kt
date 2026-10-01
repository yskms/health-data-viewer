package com.yskms.healthdataviewer.healthconnect

import java.time.LocalDateTime

// ActiveCaloriesAggregateBucket/ActiveCaloriesAggregatesResultと同じ形（ENERGY_TOTALもTotal
// Calories＝Activity系の公式重複処理を経た合計1系列のみ、requirements.md §22.2・D-043(1)）。
// totalKilocalories: TotalCaloriesAggregateTotalResult.Successのコメント参照。bucketに実際の
// レコードがない日でも非null（推計値と見られる値）を返す可能性があり、Chartが実際には記録のない日の
// bucketを「記録があるように」描画してしまう余地が未検証のまま残る（要検証、lessons.md 6.25）。
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
