package com.yskms.healthdataviewer.healthconnect

import androidx.health.connect.client.records.metadata.DataOrigin

// WBS 6.4: 詳細画面のSourcesタブ用。Weight/Steps/Sleepは件数が少なく、ソース別の正確なレコード件数を
// 全件走査して求められる（Health Rateとは異なりAggregateのMEASUREMENTS_COUNT相当の代替指標を持たない。
// OldestRecordResult（D-034決定事項8）と同じ考え方で、3データ型とも構造が同一のため1つに統合する）。
data class SourceRecordCount(val dataOrigin: DataOrigin, val count: Long)

sealed interface SourceRecordCountsResult {
    // historyLimited: 履歴読み取り権限がない（または取得中に失われた）ため、
    // 直近30日分にフォールバックして走査した結果であることを示す。
    data class Success(val counts: List<SourceRecordCount>, val historyLimited: Boolean) : SourceRecordCountsResult

    data object Failure : SourceRecordCountsResult
}
