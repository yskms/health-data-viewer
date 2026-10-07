package com.yskms.healthdataviewer.healthconnect

import java.time.LocalDateTime

// TotalCaloriesAggregateBucket/TotalCaloriesAggregatesResultと同じ形（D-054）。NutritionRecordは
// 公式AggregateMetricをエネルギー含め37個持つが、Chartで使うのはENERGY_TOTAL（エネルギー、摂取
// カロリー）の1系列のみ（D-054、複数栄養素を1つのグラフに重ねると判読できなくなるため。Blood
// Pressureが収縮期/拡張期の2系列に留めた判断、D-045(3)と同じ考え方）。totalKilocalories: bucketごとの
// hasAnyRecord()ガードは意図的に入れていない（HealthConnectManager.readNutritionAggregates()の
// コメント参照、要検証）。
data class NutritionAggregateBucket(
    val periodStart: LocalDateTime,
    val periodEnd: LocalDateTime,
    val totalKilocalories: Double?,
)

sealed interface NutritionAggregatesResult {
    // historyLimited: WeightAggregatesResultのhistoryLimitedと同じ意味。
    data class Success(val buckets: List<NutritionAggregateBucket>, val historyLimited: Boolean) : NutritionAggregatesResult

    data object Failure : NutritionAggregatesResult
}
