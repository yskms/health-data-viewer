package com.yskms.healthdataviewer.healthconnect

import java.time.LocalDateTime

// WBS 6.10（Body Fat追加、D-046）: BodyFatRecordには公式のAggregateMetricが存在しない
// （javap逆コンパイルで確認。CLAUDE.md・lessons.md 6.27参照）。そのためWeightAggregateBucket
// （aggregateGroupByPeriod()のWEIGHT_AVG/MIN/MAX結果をそのまま詰め替えたもの）とは異なり、
// このバケットはHealthConnectManager.readBodyFatAggregates()がRawレコードを自前で集計して作る。
//
// periodStartはbucketの開始時刻（端末のタイムゾーンに沿った暦日・暦月単位、WeightAggregateBucketと
// 同じ考え方）。average/min/maxはこのbucketに1件もレコードがなければnull（0で埋めない）。
// 「値がない期間」をグラフ側で区別できるようにする意図も、公式Aggregateの挙動に合わせて自前で
// 再現したもの。
data class BodyFatAggregateBucket(
    val periodStart: LocalDateTime,
    val average: Double?,
    val min: Double?,
    val max: Double?,
)

sealed interface BodyFatAggregatesResult {
    // historyLimited: WeightAggregatesResultと同じ意味。
    data class Success(val buckets: List<BodyFatAggregateBucket>, val historyLimited: Boolean) : BodyFatAggregatesResult

    data object Failure : BodyFatAggregatesResult
}
