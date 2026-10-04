package com.yskms.healthdataviewer.healthconnect

import java.time.LocalDateTime

// WBS 6.10（Blood Glucose追加）: BloodGlucoseRecordにもBodyFatRecord/HeartRateVariabilityRmssdRecord/
// OxygenSaturationRecordと同じく公式のAggregateMetricが存在しない（javap逆コンパイルで確認済み、D-049）。
// そのためBodyFatAggregateBucketと同じ形で、readBloodGlucoseAggregates()がRawレコードを
// 自前で集計して作る。
//
// periodStartはbucketの開始時刻（端末のタイムゾーンに沿った暦日・暦月単位、BodyFatAggregateBucketと
// 同じ考え方）。average/min/maxはこのbucketに1件もレコードがなければnull（0で埋めない）。単位はmg/dL
// （D-050）。
data class BloodGlucoseAggregateBucket(
    val periodStart: LocalDateTime,
    val average: Double?,
    val min: Double?,
    val max: Double?,
)

sealed interface BloodGlucoseAggregatesResult {
    // historyLimited: BodyFatAggregatesResultと同じ意味。
    data class Success(val buckets: List<BloodGlucoseAggregateBucket>, val historyLimited: Boolean) : BloodGlucoseAggregatesResult

    data object Failure : BloodGlucoseAggregatesResult
}
