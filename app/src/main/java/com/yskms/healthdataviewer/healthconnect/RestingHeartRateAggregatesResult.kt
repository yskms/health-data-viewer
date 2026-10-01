package com.yskms.healthdataviewer.healthconnect

import java.time.LocalDateTime

// periodStartはbucketの開始時刻（WeightAggregateBucketと同じ意味）。average/min/maxはそのbucketに
// 1件もレコードがなければnull。BPM_AVG/BPM_MIN/BPM_MAXはいずれもAggregateMetric<Long>のため、
// WeightAggregateBucketの.inKilogramsのような単位変換は不要（HeartRateAggregateBucketと同じ理由）。
data class RestingHeartRateAggregateBucket(
    val periodStart: LocalDateTime,
    val average: Long?,
    val min: Long?,
    val max: Long?,
)

sealed interface RestingHeartRateAggregatesResult {
    data class Success(val buckets: List<RestingHeartRateAggregateBucket>, val historyLimited: Boolean) : RestingHeartRateAggregatesResult

    data object Failure : RestingHeartRateAggregatesResult
}
