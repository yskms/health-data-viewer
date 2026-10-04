package com.yskms.healthdataviewer.healthconnect

import java.time.LocalDate

// WBS 6.11（ホームカード方式見直し、D-048）: HrvCardが使っていた「最新値＋前回比」
// （findLatestHrvRecords()）は、実際の記録頻度が1日平均約57件・5〜10分間隔のバーストだったため、
// 前回比が「隣接する2サンプルの差」という意味の薄い値になっていた（D-047(3)、lessons.md 6.29）。
// HeartRateAggregateSummaryResultと同じ「指定範囲全体を1つの区間として集計する」考え方を、
// 「最新レコードがある日」とその前日の2日分に適用し、「その日の平均＋前日比」に置き換える。
//
// latestDay/previousDayがnullなのは、その日に読み取れる範囲のレコードが1件もない場合
// （前日に記録がない日もある、Body Fatの実データで確認済みの低頻度プロファイルほどではないが
// HRVも日によって途切れることがある）。
data class HrvDailySummary(
    val date: LocalDate,
    val average: Double,
    val min: Double,
    val max: Double,
    val count: Int,
)

sealed interface HrvHomeSummaryResult {
    // historyLimited: HrvAggregatesResultと同じ意味（履歴読み取り権限がなく直近30日にフォールバックした）。
    data class Success(
        val latestDay: HrvDailySummary?,
        val previousDay: HrvDailySummary?,
        val historyLimited: Boolean,
    ) : HrvHomeSummaryResult

    data object Failure : HrvHomeSummaryResult
}
