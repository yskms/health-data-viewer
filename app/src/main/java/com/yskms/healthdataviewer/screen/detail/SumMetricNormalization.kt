package com.yskms.healthdataviewer.screen.detail

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

// 決定事項5: sum系メトリクス（Steps合計、Sleep合計時間）は、bucketが1日より粗い場合（WEEK/MONTH）、
// 実際にそのbucketがカバーする日数で割った「1日あたり平均」として表示する。avg/min/max系メトリクス
// （Weight, HeartRate）には適用しない（平均はbucketの粗さに関係なく比較可能なため）。
// 日数はperiodStart〜periodEndの実日数を使う（問い合わせ範囲の端で切り詰められた部分bucket、例:
// ALLの最初のbucketが月の途中から始まる場合も正しく按分できる。AggregationResultGroupedByPeriodに
// endTimeが存在することをjavapで確認済み）。0日にはならない想定だがcoerceAtLeast(1)で念のため防ぐ。
fun daysCoveredBy(periodStart: LocalDateTime, periodEnd: LocalDateTime): Long =
    ChronoUnit.DAYS.between(periodStart, periodEnd).coerceAtLeast(1)
