package com.yskms.healthdataviewer.healthconnect

import java.time.Duration
import java.time.LocalDateTime

// periodStartはbucketの開始時刻。totalSleepDurationはそのbucketに1件もSleep Sessionが重ならなければ
// null（WeightAggregateBucket/HeartRateAggregateBucketと同じ理由）。SLEEP_DURATION_TOTALはSleepの
// 公式重複処理（Activity/Sleepにのみ効く、requirements.md §22.2）を経た値で、Weight/Heart Rateの
// 平均・最小・最大と異なり合計（Duration）1系列のみ。Stageのうち覚醒（STAGE_TYPE_AWAKE）区間を除いた
// 時間の合計であり、Session区間の単純合計ではない。日付境界をまたぐSessionは、丸ごと1つのbucketに
// 計上されるのではなく、両日のbucketに実時間の重なりに応じて按分される。公式ドキュメントに明記はなく、
// WBS 5.1でPixel 11実機の実データ（0時より前の時間が4分〜86.5分と幅のある6事例）を使って確認したが、
// Health Connectが実際にどう計算しているか（Stage単位の計算か、Session全体を一様分布とみなす近似的な
// 計算か）と複数ソース時の重複処理（Source priority）は単一ソースのデータでしか確認しておらず未確認の
// まま（lessons.md 6.9、D-032、requirements.md §27）。
data class SleepAggregateBucket(
    val periodStart: LocalDateTime,
    val totalSleepDuration: Duration?,
)

sealed interface SleepAggregatesResult {
    data class Success(val buckets: List<SleepAggregateBucket>, val historyLimited: Boolean) : SleepAggregatesResult

    data object Failure : SleepAggregatesResult
}
