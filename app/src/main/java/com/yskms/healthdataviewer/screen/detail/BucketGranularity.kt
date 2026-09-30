package com.yskms.healthdataviewer.screen.detail

import com.yskms.healthdataviewer.R
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

// WBS 6.2: 既存poc/WeightGraphScreen・HeartRateGraphScreen・SleepGraphScreenで複製されていた
// BucketGranularity/xValue/dateFromXValueを共通化したもの（lessons.md 7.6）。WEEKはHIGH密度データ型
// （Steps/HeartRate）の3M/6M/1Yで使う（MetricDensity.kt参照）。
enum class BucketGranularity {
    DAY,
    WEEK,
    MONTH,
}

fun BucketGranularity.bucketPeriod(): Period =
    when (this) {
        BucketGranularity.DAY -> Period.ofDays(1)
        BucketGranularity.WEEK -> Period.ofDays(7)
        BucketGranularity.MONTH -> Period.ofMonths(1)
    }

// bucketの実際の並び・欠落有無に依存しないよう、x軸の値は各bucketのperiodStartから計算した絶対値に
// する（既存3画面のコメント参照、lessons.md 7.2）。DAY/WEEKはどちらもbucket開始日が特定の暦日のため
// epoch dayをそのまま使う（週bucketも7日おきの値として単調増加し、x軸としてそのまま使える。
// ISO週番号への変換は不要）。MONTHはepoch monthに相当する値。
fun BucketGranularity.xValue(dateTime: LocalDateTime): Long =
    when (this) {
        BucketGranularity.DAY, BucketGranularity.WEEK -> dateTime.toLocalDate().toEpochDay()
        BucketGranularity.MONTH -> YearMonth.from(dateTime).let { it.year * 12L + it.monthValue - 1 }
    }

fun BucketGranularity.dateFromXValue(x: Long): LocalDate =
    when (this) {
        BucketGranularity.DAY, BucketGranularity.WEEK -> LocalDate.ofEpochDay(x)
        BucketGranularity.MONTH -> YearMonth.of((x / 12).toInt(), (x % 12).toInt() + 1).atDay(1)
    }

fun BucketGranularity.axisLabelFormatter(locale: Locale): DateTimeFormatter =
    when (this) {
        BucketGranularity.DAY, BucketGranularity.WEEK -> DateTimeFormatter.ofPattern("M/d", locale)
        BucketGranularity.MONTH -> DateTimeFormatter.ofPattern("yyyy/M", locale)
    }

fun BucketGranularity.granularityLabelRes(): Int =
    when (this) {
        BucketGranularity.DAY -> R.string.detail_granularity_daily
        BucketGranularity.WEEK -> R.string.detail_granularity_weekly
        BucketGranularity.MONTH -> R.string.detail_granularity_monthly
    }

fun startOfDay(dateTime: LocalDateTime): LocalDateTime = dateTime.toLocalDate().atStartOfDay()

fun startOfMonth(dateTime: LocalDateTime): LocalDateTime = dateTime.toLocalDate().withDayOfMonth(1).atStartOfDay()
