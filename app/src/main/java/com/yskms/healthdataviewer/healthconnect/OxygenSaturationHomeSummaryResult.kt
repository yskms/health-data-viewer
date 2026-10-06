package com.yskms.healthdataviewer.healthconnect

import java.time.LocalDate

// D-052（SpO2ホームカード方式見直し）: OxygenSaturationCardが使っていた「最新値＋前回比」
// （findLatestOxygenSaturationRecords()）は、Health Sync経由の実データ（Pixel 11実機）で記録頻度が
// 1分間隔の連続サンプル（約200件/日）と判明したため、前回比が「直近1分の値とその1分前の値の差」という
// 意味の薄い値になっていた（lessons.md 6.30）。HrvHomeSummaryResult（D-048）と同じ考え方で、
// 「最新レコードがある日」とその前日の2日分を集計し、「その日の平均＋前日比」に置き換える。
//
// latestDay/previousDayがnullなのは、その日に読み取れる範囲のレコードが1件もない場合。
data class OxygenSaturationDailySummary(
    val date: LocalDate,
    val average: Double,
    val min: Double,
    val max: Double,
    val count: Int,
)

sealed interface OxygenSaturationHomeSummaryResult {
    // historyLimited: OxygenSaturationAggregatesResultと同じ意味（履歴読み取り権限がなく直近30日に
    // フォールバックした）。
    data class Success(
        val latestDay: OxygenSaturationDailySummary?,
        val previousDay: OxygenSaturationDailySummary?,
        val historyLimited: Boolean,
    ) : OxygenSaturationHomeSummaryResult

    data object Failure : OxygenSaturationHomeSummaryResult
}
