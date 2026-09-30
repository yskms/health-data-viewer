package com.yskms.healthdataviewer.screen.detail

import java.time.Duration
import java.time.LocalDateTime

// 決定事項5: sum系メトリクス（Steps合計、Sleep合計時間）は、bucketが1日より粗い場合（WEEK/MONTH）、
// 実際にそのbucketがカバーする日数で割った「1日あたり平均」として表示する。avg/min/max系メトリクス
// （Weight, HeartRate）には適用しない（平均はbucketの粗さに関係なく比較可能なため）。
// 日数はperiodStart〜periodEndの実時間（Duration）を86400秒で割った小数日数を使う。整数日数
// （ChronoUnit.DAYS.between()）に丸めると、進行中の最新bucket（endTimeが問い合わせ終了時刻nowに
// 切り詰められ、端数の時間が生じる）で端数分が切り捨てられ、実際の値より最大で数十%〜倍近く
// 高く出てしまう（例: 実際のカバー日数が1.99日でも1日として割ってしまう。レビュー指摘）。
// 0日・負の日数にはならない想定だが、bucketの境界が想定外にずれた場合の0除算・符号反転を防ぐため
// 最小1時間（1/24日）でクランプする。
fun daysCoveredBy(periodStart: LocalDateTime, periodEnd: LocalDateTime): Double =
    (Duration.between(periodStart, periodEnd).toMillis() / 86_400_000.0).coerceAtLeast(1.0 / 24)
