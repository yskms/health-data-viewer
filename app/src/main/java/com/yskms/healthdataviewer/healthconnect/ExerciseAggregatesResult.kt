package com.yskms.healthdataviewer.healthconnect

import java.time.Duration
import java.time.LocalDateTime

// WBS 6.10（Exercise追加、D-051）: DistanceAggregateBucket/SleepAggregateBucketと同じ形。
// ExerciseSessionRecord.EXERCISE_DURATION_TOTALはSleepのSLEEP_DURATION_TOTALと同じ
// AggregateMetric<Duration>（javap逆コンパイルで確認）で、合計1系列のみ。requirements.md §22.2の
// 重複処理欄は「—」のまま（Steps/Distance/CaloriesのようなActivity分類、SleepのようなSleep分類の
// いずれも公式ドキュメント上の記載が無いため。要検証）。
data class ExerciseAggregateBucket(
    val periodStart: LocalDateTime,
    val periodEnd: LocalDateTime,
    val totalExerciseDuration: Duration?,
)

sealed interface ExerciseAggregatesResult {
    data class Success(val buckets: List<ExerciseAggregateBucket>, val historyLimited: Boolean) : ExerciseAggregatesResult

    data object Failure : ExerciseAggregatesResult
}
