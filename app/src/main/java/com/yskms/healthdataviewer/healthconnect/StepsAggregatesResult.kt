package com.yskms.healthdataviewer.healthconnect

import java.time.LocalDateTime

// periodStart/periodEndはbucketの開始・終了時刻。totalはそのbucketに1件もレコードがなければnull
// （WeightAggregateBucketと同じ理由）。COUNT_TOTALはSteps=Activity系の公式重複処理
// （requirements.md §22.2）を経た合計であり、Weight/Heart Rateの平均・最小・最大と異なり合計1系列のみ
// （SleepAggregateBucketと同じ形）。periodEndは、画面側（screen/detail）でWEEK/MONTH bucketの表示を
// 「1日あたり平均」に正規化する際、periodStart〜periodEndの実日数（問い合わせ範囲の端で切り詰められた
// 部分bucketを含む）で割るために使う。
data class StepsAggregateBucket(
    val periodStart: LocalDateTime,
    val periodEnd: LocalDateTime,
    val total: Long?,
)

sealed interface StepsAggregatesResult {
    // historyLimited: readAllWeightRecords()のhistoryLimitedと同じ意味。
    data class Success(val buckets: List<StepsAggregateBucket>, val historyLimited: Boolean) : StepsAggregatesResult

    data object Failure : StepsAggregatesResult
}
