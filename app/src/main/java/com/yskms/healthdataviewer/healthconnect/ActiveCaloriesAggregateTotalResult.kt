package com.yskms.healthdataviewer.healthconnect

// WBS 6.10（D-043）: ホーム画面のActive Caloriesカード用。DistanceAggregateTotalResultと同じ形
// （bucket分割なしの単発Aggregate、呼び出し元が期間ごとのfilterを安全な範囲に事前クランプする
// 設計のため内部フォールバックを持たない）。
sealed interface ActiveCaloriesAggregateTotalResult {
    // totalKilocalories: 問い合わせ範囲にレコードが1件もなければnull（AggregationResult.get()の仕様）。
    data class Success(val totalKilocalories: Double?) : ActiveCaloriesAggregateTotalResult

    data object Failure : ActiveCaloriesAggregateTotalResult
}
