package com.yskms.healthdataviewer.healthconnect

// WBS 6.10: ホーム画面のDistanceカード用。HeartRateAggregateSummaryResult/SleepAggregateSummaryResultと
// 同じ形（bucket分割なしの単発Aggregate、呼び出し元が期間ごとのfilterを安全な範囲に事前クランプする
// 設計のため内部フォールバックを持たない）。
sealed interface DistanceAggregateTotalResult {
    // totalKilometers: 問い合わせ範囲にレコードが1件もなければnull（AggregationResult.get()の仕様）。
    data class Success(val totalKilometers: Double?) : DistanceAggregateTotalResult

    data object Failure : DistanceAggregateTotalResult
}
