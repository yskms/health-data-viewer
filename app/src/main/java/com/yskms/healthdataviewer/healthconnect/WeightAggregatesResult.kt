package com.yskms.healthdataviewer.healthconnect

import java.time.LocalDateTime

// periodStartはbucketの開始時刻（端末のタイムゾーンに沿った暦日・暦月単位。
// HealthConnectManager.readWeightAggregates()のコメント、lessons.md 6.5参照）。
// average/min/maxはこのbucketに1件もレコードがなければnull（AggregationResult.get()がnullを返す）。
// 「値がない期間」（requirements.md §10）をグラフ側で区別できるよう、0で埋めない。
data class WeightAggregateBucket(
    val periodStart: LocalDateTime,
    val average: Double?,
    val min: Double?,
    val max: Double?,
)

sealed interface WeightAggregatesResult {
    // historyLimited: readAllWeightRecords()のhistoryLimitedと同じ意味。
    data class Success(val buckets: List<WeightAggregateBucket>, val historyLimited: Boolean) : WeightAggregatesResult

    data object Failure : WeightAggregatesResult
}
