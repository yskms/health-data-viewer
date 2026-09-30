package com.yskms.healthdataviewer.healthconnect

import java.time.LocalDateTime

// periodStartはbucketの開始時刻。averageBpm/minBpm/maxBpmはそのbucketに1件もレコードがなければ
// null（WeightAggregateBucketと同じ理由）。measurementCountはサンプル数であり、レコード数ではない
// （requirements.md §22.2）。BPM_AVG/BPM_MIN/BPM_MAX/MEASUREMENTS_COUNTはいずれも
// AggregateMetric<Long>のため、WeightAggregateBucketの.inKilogramsのような単位変換は不要。
data class HeartRateAggregateBucket(
    val periodStart: LocalDateTime,
    val averageBpm: Long?,
    val minBpm: Long?,
    val maxBpm: Long?,
    val measurementCount: Long?,
)

sealed interface HeartRateAggregatesResult {
    data class Success(val buckets: List<HeartRateAggregateBucket>, val historyLimited: Boolean) : HeartRateAggregatesResult

    data object Failure : HeartRateAggregatesResult
}
