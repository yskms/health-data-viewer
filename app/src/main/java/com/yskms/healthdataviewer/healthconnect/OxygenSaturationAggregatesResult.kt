package com.yskms.healthdataviewer.healthconnect

import java.time.LocalDateTime

// WBS 6.10（SpO2追加）: OxygenSaturationRecordにもBodyFatRecord/HeartRateVariabilityRmssdRecordと
// 同じく公式のAggregateMetricが存在しない（javap逆コンパイルで確認。CLAUDE.md・lessons.md参照）。
// そのためBodyFatAggregateBucketと同じ形で、readOxygenSaturationAggregates()がRawレコードを
// 自前で集計して作る。
//
// periodStartはbucketの開始時刻（端末のタイムゾーンに沿った暦日・暦月単位、BodyFatAggregateBucketと
// 同じ考え方）。average/min/maxはこのbucketに1件もレコードがなければnull（0で埋めない）。
data class OxygenSaturationAggregateBucket(
    val periodStart: LocalDateTime,
    val average: Double?,
    val min: Double?,
    val max: Double?,
)

sealed interface OxygenSaturationAggregatesResult {
    // historyLimited: BodyFatAggregatesResultと同じ意味。
    data class Success(val buckets: List<OxygenSaturationAggregateBucket>, val historyLimited: Boolean) : OxygenSaturationAggregatesResult

    data object Failure : OxygenSaturationAggregatesResult
}
