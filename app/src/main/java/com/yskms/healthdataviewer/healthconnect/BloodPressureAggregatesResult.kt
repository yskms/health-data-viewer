package com.yskms.healthdataviewer.healthconnect

import java.time.LocalDateTime

// WBS 6.10（D-045）: BloodPressureRecordはSYSTOLIC_AVG/MIN/MAX・DIASTOLIC_AVG/MIN/MAXの6つの公式
// AggregateMetric（いずれもAggregateMetric<Pressure>）を持つが、グラフでは「収縮期/拡張期の2値」を
// 1枚のグラフに示すことを優先し、平均（AVG）のみを使う。6系列（各最小・最大を含む）を凡例なしで
// 1つのグラフに重ねると判読できなくなるため（既存のWeight/HeartRate/RestingHeartRateチャートは
// いずれも1指標のavg/min/maxの3系列どまりで、2指標分の6系列を表示した前例がない）。
// periodStartはbucketの開始時刻（WeightAggregateBucketと同じ意味）。
data class BloodPressureAggregateBucket(
    val periodStart: LocalDateTime,
    val systolicAverage: Double?,
    val diastolicAverage: Double?,
)

sealed interface BloodPressureAggregatesResult {
    data class Success(val buckets: List<BloodPressureAggregateBucket>, val historyLimited: Boolean) : BloodPressureAggregatesResult

    data object Failure : BloodPressureAggregatesResult
}
