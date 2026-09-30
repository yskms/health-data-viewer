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

    // Customで開始日・終了日から導いた範囲が、問い合わせ不可能な形になった状態。呼び出し元は
    // Aggregate APIを呼ばず、空の結果を直接返す（TimeRangeFilter.between()にstart >= endを渡すと
    // IllegalArgumentExceptionでクラッシュするため、lessons.md 7.4と同じ理由でAPI呼び出し自体を
    // 避ける）。2つの原因を区別する（レビュー指摘）:
    // - historyLimited = false: 開始日に未来の日付（endより後）を選んだ場合。権限とは無関係。
    //   DatePickerDialog側で今日より後を選べなくするガードも入れているが、念のためここでも検出する。
    // - historyLimited = true: 履歴読み取り権限がない状態で、選んだ範囲が直近30日より完全に古く
    //   （下記CUSTOM分岐のクランプ後もstart >= endのまま）、表示できる範囲が残らなかった場合。
    //   呼び出し元は「履歴読み取り権限がないため直近30日分のみ表示している」通知を出す。
    data class Empty(val historyLimited: Boolean) : DetailGraphRange

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
// 同じ式）。ALL・Customの開始日をここで直近30日にクランプすることで、HealthConnectManager内部の
// SecurityExceptionフォールバックに任せるより前に、正しいgranularityを計算できるようにする
// （下記ALL・CUSTOM分岐のコメント参照、レビュー指摘）。
private fun recentFloorLocal(now: LocalDateTime): LocalDateTime =
    now.minus(HealthConnectManager.HISTORY_FALLBACK_DAYS, ChronoUnit.DAYS).toLocalDate().plusDays(1).atStartOfDay()

// oldestStart: ALL専用。最古レコードの時刻（見つかった場合）。nullは「レコードが1件もない」
// （呼び出し元がAggregate API自体を呼ばず空の結果を直接返すべきケース、既存3画面と同じ。
// このケースはresolveDetailGraphRangeを呼ぶ前に呼び出し元で判定する）ではなく、「開始日不明のまま
// 開始無制限にフォールバックする」ケース（findOldestXxxRecordTime()自体の失敗）のみを表す。
// ALL以外のperiodではこの引数は使われない。
// historyPermissionGranted: ALL・Custom専用。falseの場合、素直な開始日（ALLはoldestStartの月初
// 切り捨て、Customはユーザーが選んだ開始日）は直近30日より古くなり得る。その状態のまま
// readXxxAggregates()に渡すと、内部でSecurityException→直近30日へのフォールバックが起きるが、
// フォールバック先でも同じbucket粒度（呼び出し時に固定した粒度）が使われ続け、暦月に整列しない
// 範囲を月bucketとして問い合わせてしまう（lessons.md 7.5の前提が崩れる。レビュー指摘）。
// historyPermissionGrantedは呼び出し前から分かっているため、ここで直近30日側に事前クランプして
// granularityを計算し直すことで、この不整合を避ける（権限が画面表示中に取り消される稀なケースは、
// 既存のマネージャー側フォールバックに引き続き委ねる。既知の残課題としてlessons.mdに記録する）。
// oldestStartがnull（findOldestXxxRecordTime()自体が失敗）かつ履歴権限もない場合は、開始無制限では
// なく直近30日を開始日として使う（レビュー指摘。権限がある場合は従来通り開始無制限のまま）。
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
                if (!historyPermissionGranted) {
                    val recentFloor = recentFloorLocal(now)
                    if (monthAlignedStart != null) maxOf(monthAlignedStart, recentFloor) else recentFloor
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
                // 選べなくするガードを入れているが、念のためここでも検出する（レビュー指摘）。
                return DetailGraphRange.Empty(historyLimited = false)
            }
            // 履歴読み取り権限がない場合、選んだ開始日を直近30日側にクランプする（ALLと同じ理由。
            // レビュー指摘。権限がないまま素直な開始日で問い合わせると、HealthConnectManager内部の
            // SecurityExceptionフォールバックに入り、選んだ範囲とは無関係な直近30日が表示されて
            // しまう上、粒度も選んだ範囲のまま固定されbucket境界がずれる）。
            val clampedStart = if (!historyPermissionGranted) maxOf(dayAlignedStart, recentFloorLocal(now)) else dayAlignedStart
            if (!clampedStart.isBefore(end)) {
                // クランプの結果、選んだ範囲が直近30日より完全に古く、表示できる範囲が残らなかった。
                return DetailGraphRange.Empty(historyLimited = true)
            }
            // 粒度は範囲の日数から決まるが、粒度がMONTHになる場合はbucket境界を暦月に整列させる
            // ため開始日を月初へ切り捨てる必要がある（lessons.md 7.5）。切り捨てるとその月の
            // 実際にデータがあり得る開始日（clampedStart）より前を含んでしまうため、
            // denominatorFloorとしてclampedStartを残す（レビュー指摘。ALLのoldestStartと同じ理由）。
            val preliminarySpanDays = ChronoUnit.DAYS.between(clampedStart.toLocalDate(), end.toLocalDate())
            val granularity = density.bucketGranularityFor(preliminarySpanDays)
            val start = if (granularity == BucketGranularity.MONTH) startOfMonth(clampedStart) else clampedStart
            return DetailGraphRange.Resolved(
                timeRangeFilter = TimeRangeFilter.between(start, end),
                granularity = granularity,
                denominatorFloor = if (granularity == BucketGranularity.MONTH) clampedStart else null,
            )
        }
    }
}
