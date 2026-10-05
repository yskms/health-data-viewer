package com.yskms.healthdataviewer.healthconnect

import java.time.Duration

// WBS 6.10（Exercise追加、D-051）: ホーム画面のExerciseカード用。DistanceAggregateTotalResultと
// 同じ形（bucket分割なしの単発Aggregate、呼び出し元が期間ごとのfilterを安全な範囲に事前クランプする
// 設計のため内部フォールバックを持たない）。Sleepカード（期間内の1日あたり平均）とは異なり、Exerciseは
// 運動する日としない日が混在するため、平均ではなく期間合計（Distance/Steps/Caloriesと同じ考え方）を
// 採用した（D-051）。
sealed interface ExerciseAggregateTotalResult {
    // totalDuration: 問い合わせ範囲にレコードが1件もなければnull（AggregationResult.get()の仕様）。
    data class Success(val totalDuration: Duration?) : ExerciseAggregateTotalResult

    data object Failure : ExerciseAggregateTotalResult
}
