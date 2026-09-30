package com.yskms.healthdataviewer.screen.detail

import androidx.health.connect.client.time.TimeRangeFilter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

// WBS 6.2: 選択中のGraphPeriod・データ型のMetricDensity・（ALLなら）最古レコード時刻・（Customなら）
// ユーザーが選んだ開始日・終了日から、実際に問い合わせるTimeRangeFilter（LocalDateTimeベース、
// aggregateGroupByPeriod()の要求どおり日初/月初切り捨て込み。lessons.md 6.5/7.5）とBucketGranularityを
// 解決する。既存poc/*GraphScreenで複製されていたGraphPeriod.timeRangeFilter()をここに一本化した。
sealed interface DetailGraphRange {
    // Customで日付が未選択のため、まだ問い合わせを開始できない状態。呼び出し元は読み込み中として扱う。
    data object Pending : DetailGraphRange

    data class Resolved(val timeRangeFilter: TimeRangeFilter, val granularity: BucketGranularity) : DetailGraphRange
}

// oldestStart: ALL専用。最古レコードの時刻（見つかった場合）。nullは「レコードが1件もない」
// （呼び出し元がAggregate API自体を呼ばず空の結果を直接返すべきケース、既存3画面と同じ。
// このケースはresolveDetailGraphRangeを呼ぶ前に呼び出し元で判定する）ではなく、「開始日不明のまま
// 開始無制限にフォールバックする」ケース（findOldestXxxRecordTime()自体の失敗）のみを表す。
// ALL以外のperiodではこの引数は使われない。
// customRange: Custom専用。ユーザーが選んだ開始日・終了日（両方揃うまでPending）。
fun resolveDetailGraphRange(
    period: GraphPeriod,
    density: MetricDensity,
    now: LocalDateTime,
    oldestStart: LocalDateTime?,
    customRange: Pair<LocalDate, LocalDate>?,
): DetailGraphRange {
    val (start, end) =
        when (period) {
            GraphPeriod.WEEK -> startOfDay(now.minusWeeks(1)) to now
            GraphPeriod.MONTH -> startOfDay(now.minusMonths(1)) to now
            GraphPeriod.THREE_MONTHS -> startOfDay(now.minusMonths(3)) to now
            GraphPeriod.SIX_MONTHS -> startOfDay(now.minusMonths(6)) to now
            GraphPeriod.YEAR -> startOfDay(now.minusYears(1)) to now
            GraphPeriod.ALL -> oldestStart?.let(::startOfMonth) to now
            GraphPeriod.CUSTOM -> {
                val (from, to) = customRange ?: return DetailGraphRange.Pending
                // 終了日は選択日の翌日0時とnowの早い方を上限にする（当日を選べばnowまで含む）。
                startOfDay(from.atStartOfDay()) to minOf(to.plusDays(1).atStartOfDay(), now)
            }
        }

    val filter = if (start != null) TimeRangeFilter.between(start, end) else TimeRangeFilter.before(end)
    val spanDays = if (start != null) ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()) else Long.MAX_VALUE
    return DetailGraphRange.Resolved(timeRangeFilter = filter, granularity = density.bucketGranularityFor(spanDays))
}
