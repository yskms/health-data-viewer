package com.yskms.healthdataviewer.healthconnect

import java.time.LocalDateTime

// TotalCaloriesAggregateBucket/TotalCaloriesAggregatesResultと同じ形（D-054）。NutritionRecordは
// 公式AggregateMetricをエネルギー含め37個持つが、Chartで使うのはENERGY_TOTAL（エネルギー、摂取
// カロリー）の1系列のみ（D-054、複数栄養素を1つのグラフに重ねると判読できなくなるため。Blood
// Pressureが収縮期/拡張期の2系列に留めた判断、D-045(3)と同じ考え方）。totalKilocalories: bucketごとの
// hasAnyRecord()ガードは意図的に入れていない。TotalCaloriesと同じnull非返却バグは実機で再現しない
// ことを確認済みだが、「レコードはあるがenergyが未設定の日」が0.0として扱われる限界は残る
// （詳細・要検証事項はHealthConnectManager.readNutritionAggregates()のコメント参照）。
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
