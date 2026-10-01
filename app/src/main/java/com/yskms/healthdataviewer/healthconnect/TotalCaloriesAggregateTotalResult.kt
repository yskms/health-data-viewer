package com.yskms.healthdataviewer.healthconnect

// WBS 6.10（D-043）: ホーム画面のTotal Caloriesカード用。ActiveCaloriesAggregateTotalResultと同じ形。
sealed interface TotalCaloriesAggregateTotalResult {
    // totalKilocalories: 問い合わせ範囲にレコードが1件もなければnull（AggregationResult.get()の仕様）。
    data class Success(val totalKilocalories: Double?) : TotalCaloriesAggregateTotalResult

    data object Failure : TotalCaloriesAggregateTotalResult
}
