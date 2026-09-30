package com.yskms.healthdataviewer.healthconnect

// readHeartRateAggregateSummary()の結果。readHeartRateAggregates()（グラフ用、bucket分割）とは異なり、
// 指定した範囲全体を1つの区間として集計した値を返す（WBS 6.1、ホーム画面の指標カード用）。
sealed interface HeartRateAggregateSummaryResult {
    data class Success(
        val averageBpm: Long?,
        val minBpm: Long?,
        val maxBpm: Long?,
    ) : HeartRateAggregateSummaryResult

    data object Failure : HeartRateAggregateSummaryResult
}
