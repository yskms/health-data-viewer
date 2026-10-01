package com.yskms.healthdataviewer.healthconnect

import java.time.LocalDateTime

// DistanceAggregateBucket/DistanceAggregatesResultと同じ形（ACTIVE_CALORIES_TOTALもActive
// Calories＝Activity系の公式重複処理を経た合計1系列のみ、requirements.md §22.2・D-043(1)）。
// WEEK/MONTH bucketの「1日あたり平均」正規化はSumMetricNormalization.daysCoveredBy()を使う
// （決定事項5）。ACTIVE_CALORIES_TOTALはEnergy型のため、DistanceAggregateBucket（Length→
// inKilometers）と同じ考え方でマネージャー層でkcalへ変換済みの値を保持する。
// totalKilocalories: TotalCaloriesAggregateBucketで、レコードのないbucketでも非nullの推計値らしき
// 値が返る挙動を確認している。ACTIVE_CALORIES_TOTALで同じことが起きるかは未確認（要検証、
// ActiveCaloriesAggregateTotalResult.kt参照）。
data class ActiveCaloriesAggregateBucket(
    val periodStart: LocalDateTime,
    val periodEnd: LocalDateTime,
    val totalKilocalories: Double?,
)

sealed interface ActiveCaloriesAggregatesResult {
    // historyLimited: WeightAggregatesResultのhistoryLimitedと同じ意味。
    data class Success(val buckets: List<ActiveCaloriesAggregateBucket>, val historyLimited: Boolean) : ActiveCaloriesAggregatesResult

    data object Failure : ActiveCaloriesAggregatesResult
}
