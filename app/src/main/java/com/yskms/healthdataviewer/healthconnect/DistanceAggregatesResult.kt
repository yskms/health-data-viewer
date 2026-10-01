package com.yskms.healthdataviewer.healthconnect

import java.time.LocalDateTime

// StepsAggregateBucket/StepsAggregatesResultと同じ形（DISTANCE_TOTALもDistance=Activity系の
// 公式重複処理を経た合計1系列のみ、requirements.md §22.2）。WEEK/MONTH bucketの「1日あたり平均」
// 正規化はSumMetricNormalization.daysCoveredBy()を使う（決定事項5）。DISTANCE_TOTALはLength型の
// ため、WeightAggregateBucket（Mass→inKilograms）と同じ考え方でマネージャー層でkmへ変換済みの
// 値を保持する。
data class DistanceAggregateBucket(
    val periodStart: LocalDateTime,
    val periodEnd: LocalDateTime,
    val totalKilometers: Double?,
)

sealed interface DistanceAggregatesResult {
    // historyLimited: WeightAggregatesResultのhistoryLimitedと同じ意味。
    data class Success(val buckets: List<DistanceAggregateBucket>, val historyLimited: Boolean) : DistanceAggregatesResult

    data object Failure : DistanceAggregatesResult
}
