package com.yskms.healthdataviewer.healthconnect

import java.time.Duration

// readSleepAggregateSummary()の結果。readSleepAggregates()（グラフ用、bucket分割）とは異なり、
// 指定した範囲全体を1つの区間として集計した値を返す（WBS 6.1、ホーム画面の指標カード用）。
sealed interface SleepAggregateSummaryResult {
    data class Success(val totalSleepDuration: Duration?) : SleepAggregateSummaryResult

    data object Failure : SleepAggregateSummaryResult
}
