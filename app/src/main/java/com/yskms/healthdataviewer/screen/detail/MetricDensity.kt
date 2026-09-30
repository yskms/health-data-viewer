package com.yskms.healthdataviewer.screen.detail

// WBS 6.2: データ型ごとの記録頻度プロファイル。aggregateGroupByPeriod()の所要時間はbucket数に加え
// 「高頻度データを多数のbucketに割って集計する」コストの影響を受ける（lessons.md 6.8。HeartRateの
// 1Y日bucket=365bucketが、月bucket=28bucketのALLよりおよそ一桁近く遅かった実測値がある）。
// LOW（Weight, Sleep）はレコード頻度が低く1Yも日bucketのままで問題ない見込みだが、HIGH
// （Steps, HeartRate）は3M以上でWEEK bucketに粗くする。Stepsのbucket集計は今回が初実装で
// 所要時間が未実測のため、HeartRateと同じ保守的な閾値を暫定的に適用する（要検証。実機計測の結果
// 次第でこの閾値・D-034を見直す）。
enum class MetricDensity {
    LOW,
    HIGH,
}

fun MetricDensity.bucketGranularityFor(spanDays: Long): BucketGranularity =
    when (this) {
        MetricDensity.LOW -> if (spanDays <= 366) BucketGranularity.DAY else BucketGranularity.MONTH
        MetricDensity.HIGH ->
            when {
                spanDays <= 31 -> BucketGranularity.DAY
                spanDays <= 366 -> BucketGranularity.WEEK
                else -> BucketGranularity.MONTH
            }
    }
