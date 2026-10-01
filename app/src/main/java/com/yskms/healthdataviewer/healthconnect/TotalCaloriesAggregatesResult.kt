package com.yskms.healthdataviewer.healthconnect

import java.time.LocalDateTime

// ActiveCaloriesAggregateBucket/ActiveCaloriesAggregatesResultと同じ形（ENERGY_TOTALもTotal
// Calories＝Activity系の公式重複処理を経た合計1系列のみ、requirements.md §22.2・D-043(1)）。
// totalKilocalories: TotalCaloriesAggregateTotalResult.Successのコメント参照。あちらと異なり、
// こちらはbucketごとの実レコード確認（hasAnyRecord()）を**意図的に入れていない**（実機で致命的に
// 遅いことを確認したため、HealthConnectManager.readTotalCaloriesAggregates()のコメント参照）。
// そのため、記録が1件もないbucketでも非null値（推計値と見られる値）が返ったままになる可能性がある
// （未解消、要検証、lessons.md 6.26）。
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
