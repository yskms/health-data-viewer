package com.yskms.healthdataviewer.healthconnect

sealed interface StepsAggregateTotalResult {
    // total: 問い合わせ範囲にレコードが1件もなければnull（AggregationResult.get()の仕様。
    // WeightAggregateBucketのaverage/min/maxと同じ扱い）。
    data class Success(val total: Long?) : StepsAggregateTotalResult

    data object Failure : StepsAggregateTotalResult
}
