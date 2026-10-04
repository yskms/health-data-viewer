package com.yskms.healthdataviewer.healthconnect

import java.time.LocalDateTime

// WBS 6.10（HRV追加、D-047(4)）: HeartRateVariabilityRmssdRecordにもBodyFatRecordと同じく公式の
// AggregateMetricが存在しない（javap逆コンパイルで確認。CLAUDE.md・lessons.md 6.28参照）。そのため
// BodyFatAggregateBucket（HealthConnectManager.readBodyFatAggregates()が自前集計したもの）と同じ形で、
// readHrvAggregates()がRawレコードを自前で集計して作る。
//
// periodStartはbucketの開始時刻（端末のタイムゾーンに沿った暦日・暦月単位、BodyFatAggregateBucketと
// 同じ考え方）。average/min/maxはこのbucketに1件もレコードがなければnull（0で埋めない）。
//
// countはWBS 6.11（ホームカード方式見直し）で追加した、bucketに含まれるレコード件数。
// BodyFatAggregateBucketには同じフィールドを追加していない（Body Fatは低頻度・日次型と確認済みで
// カード方式を見直す対象ではないため、D-048参照）。Chart用の既存の呼び出し元（HrvDetailScreen）は
// 使わないフィールドが増えるだけで影響しない。
data class HrvAggregateBucket(
    val periodStart: LocalDateTime,
    val average: Double?,
    val min: Double?,
    val max: Double?,
    val count: Int,
)

sealed interface HrvAggregatesResult {
    // historyLimited: BodyFatAggregatesResultと同じ意味。
    data class Success(val buckets: List<HrvAggregateBucket>, val historyLimited: Boolean) : HrvAggregatesResult

    data object Failure : HrvAggregatesResult
}
