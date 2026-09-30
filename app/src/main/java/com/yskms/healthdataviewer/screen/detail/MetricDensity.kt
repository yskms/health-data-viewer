package com.yskms.healthdataviewer.screen.detail

// WBS 6.2: データ型ごとの記録頻度プロファイル。aggregateGroupByPeriod()の所要時間はbucket数に加え
// 「高頻度データを多数のbucketに割って集計する」コストの影響を受ける（lessons.md 6.8）。
// LOW（Weight, Sleep）はレコード頻度が低く1Yも日bucketのままで問題ない。HIGH（Steps, HeartRate）は
// 3M以上でWEEK bucketに粗くする。実機計測の結果、Stepsはこの閾値でどの期間も高速（3ヶ月221ms〜
// 全期間5.0秒）だったが、HeartRate（バックグラウンド継続記録）は週bucketにしてもなお6ヶ月30秒・
// 1年57秒・全期間96秒かかることが分かった。所要時間の主要因はbucket数ではなく問い合わせ範囲の
// 実データ量であり、この閾値による粗粒度化はStepsには効果があった一方、HeartRateのような
// 継続記録型データの根本的な遅さは解消しない（lessons.md 6.12、既知の制約として受け入れる）。
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
