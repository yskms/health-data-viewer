package com.yskms.healthdataviewer.screen.detail

import androidx.health.connect.client.time.TimeRangeFilter
import com.yskms.healthdataviewer.healthconnect.HealthConnectManager
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

    // Customで開始日・終了日から導いた範囲が空（開始 >= 終了）になった状態。未来の日付を開始日に
    // 選んだ場合に起こり得る（DatePickerDialog側で今日より後を選べなくするガードも入れているが、
    // それとは別にここでも検出する。レビュー指摘）。呼び出し元はAggregate APIを呼ばず、
    // 空の結果を直接返す（TimeRangeFilter.between()にstart >= endを渡すとIllegalArgumentExceptionに
    // なるため、lessons.md 7.4と同じ理由でAPI呼び出し自体を避ける）。
    data object Empty : DetailGraphRange

    // denominatorFloor: sum系メトリクス（Steps/Sleep）の「1日あたり平均」正規化で、bucketの開始時刻を
    // 暦月境界へ切り捨てたことで実際にデータが存在し得る範囲より前を含んでしまう場合に、日数計算の
    // 下限としてクランプする時刻（lessons.md 6.13）。ALLではoldestStart、Customで月bucketに
    // 切り捨てた場合は切り捨て前の開始日、それ以外はnull（クランプ不要）。
    data class Resolved(
        val timeRangeFilter: TimeRangeFilter,
        val granularity: BucketGranularity,
        val denominatorFloor: LocalDateTime?,
    ) : DetailGraphRange
}

// 履歴読み取り権限がない場合に読める範囲の近似（HealthConnectManager.recentRangeFilterLocal()と
// 同じ式）。ALLの開始日をここで直近30日にクランプすることで、HealthConnectManager内部の
// SecurityExceptionフォールバックに任せるより前に、正しいgranularityを計算できるようにする
// （下記ALL分岐のコメント参照、レビュー指摘）。
private fun recentFloorLocal(now: LocalDateTime): LocalDateTime =
    now.minus(HealthConnectManager.HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS).toLocalDate().plusDays(1).atStartOfDay()

// oldestStart: ALL専用。最古レコードの時刻（見つかった場合）。nullは「レコードが1件もない」
// （呼び出し元がAggregate API自体を呼ばず空の結果を直接返すべきケース、既存3画面と同じ。
// このケースはresolveDetailGraphRangeを呼ぶ前に呼び出し元で判定する）ではなく、「開始日不明のまま
// 開始無制限にフォールバックする」ケース（findOldestXxxRecordTime()自体の失敗）のみを表す。
// ALL以外のperiodではこの引数は使われない。
// historyPermissionGranted: ALL専用。falseの場合、oldestStartから素直に月初へ切り捨てると
// 直近30日より古い開始日になり得る。その状態のままreadXxxAggregates()に渡すと、内部で
// SecurityException→直近30日へのフォールバックが起きるが、フォールバック先でも同じbucket粒度
// （このケースではMONTH）が使われ続け、暦月に整列しない範囲を月bucketとして問い合わせてしまう
// （lessons.md 7.5の前提が崩れる。レビュー指摘）。historyPermissionGrantedは呼び出し前から
// 分かっているため、ここで直近30日側に事前クランプしてgranularityを計算し直すことで、
// この不整合を避ける（権限が画面表示中に取り消される稀なケースは、既存のマネージャー側
// フォールバックに引き続き委ねる。既知の残課題としてlessons.mdに記録する）。
// customRange: Custom専用。ユーザーが選んだ開始日・終了日（両方揃うまでPending）。
fun resolveDetailGraphRange(
    period: GraphPeriod,
    density: MetricDensity,
    now: LocalDateTime,
    oldestStart: LocalDateTime?,
    historyPermissionGranted: Boolean,
    customRange: Pair<LocalDate, LocalDate>?,
): DetailGraphRange {
    when (period) {
        GraphPeriod.WEEK, GraphPeriod.MONTH, GraphPeriod.THREE_MONTHS, GraphPeriod.SIX_MONTHS, GraphPeriod.YEAR -> {
            val trailingStart =
                when (period) {
                    GraphPeriod.WEEK -> now.minusWeeks(1)
                    GraphPeriod.MONTH -> now.minusMonths(1)
                    GraphPeriod.THREE_MONTHS -> now.minusMonths(3)
                    GraphPeriod.SIX_MONTHS -> now.minusMonths(6)
                    else -> now.minusYears(1)
                }
            val start = startOfDay(trailingStart)
            val spanDays = ChronoUnit.DAYS.between(start.toLocalDate(), now.toLocalDate())
            return DetailGraphRange.Resolved(
                timeRangeFilter = TimeRangeFilter.between(start, now),
                granularity = density.bucketGranularityFor(spanDays),
                denominatorFloor = null,
            )
        }

        GraphPeriod.ALL -> {
            val monthAlignedStart = oldestStart?.let(::startOfMonth)
            val start =
                if (!historyPermissionGranted && monthAlignedStart != null) {
                    maxOf(monthAlignedStart, recentFloorLocal(now))
                } else {
                    monthAlignedStart
                }
            val filter = if (start != null) TimeRangeFilter.between(start, now) else TimeRangeFilter.before(now)
            val spanDays = if (start != null) ChronoUnit.DAYS.between(start.toLocalDate(), now.toLocalDate()) else Long.MAX_VALUE
            return DetailGraphRange.Resolved(
                timeRangeFilter = filter,
                granularity = density.bucketGranularityFor(spanDays),
                denominatorFloor = oldestStart,
            )
        }

        GraphPeriod.CUSTOM -> {
            val (from, to) = customRange ?: return DetailGraphRange.Pending
            // 終了日は選択日の翌日0時とnowの早い方を上限にする（当日を選べばnowまで含む）。
            val end = minOf(to.plusDays(1).atStartOfDay(), now)
            val dayAlignedStart = startOfDay(from.atStartOfDay())
            if (!dayAlignedStart.isBefore(end)) {
                // 開始日に未来の日付（endより後）を選んだ場合。DatePickerDialog側で今日より後を
                // 選べなくするガードを入れているが、念のためここでも検出し、TimeRangeFilterを
                // 組み立てずに空の結果を返す（レビュー指摘。between()にstart >= endを渡すと
                // IllegalArgumentExceptionでクラッシュする）。
                return DetailGraphRange.Empty
            }
            // 粒度は範囲の日数から決まるが、粒度がMONTHになる場合はbucket境界を暦月に整列させる
            // ため開始日を月初へ切り捨てる必要がある（lessons.md 7.5）。切り捨てるとその月の
            // 実際にデータがあり得る開始日（dayAlignedStart）より前を含んでしまうため、
            // denominatorFloorとしてdayAlignedStartを残す（レビュー指摘。ALLのoldestStartと同じ理由）。
            val preliminarySpanDays = ChronoUnit.DAYS.between(dayAlignedStart.toLocalDate(), end.toLocalDate())
            val granularity = density.bucketGranularityFor(preliminarySpanDays)
            val start = if (granularity == BucketGranularity.MONTH) startOfMonth(dayAlignedStart) else dayAlignedStart
            return DetailGraphRange.Resolved(
                timeRangeFilter = TimeRangeFilter.between(start, end),
                granularity = granularity,
                denominatorFloor = if (granularity == BucketGranularity.MONTH) dayAlignedStart else null,
            )
        }
    }
}
