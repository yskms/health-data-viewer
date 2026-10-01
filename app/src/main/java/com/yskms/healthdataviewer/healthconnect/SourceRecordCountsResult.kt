package com.yskms.healthdataviewer.healthconnect

import androidx.health.connect.client.records.metadata.DataOrigin

// WBS 6.4: 詳細画面のSourcesタブ用。`HealthConnectManager.readSourceRecordCounts()`が使う。
// Weight/Steps/Sleepはソース別の正確なレコード件数を全件走査して求める。3データ型に共通する基準は
// 件数の多寡ではなく「1レコードが大きなサンプル配列を持たず、Health Connect SDK内部の変換コストが
// 低いか」で、Heart Rateだけがこれに該当しない（1レコードに多数のサンプルを含み、継続記録ソースでは
// 全件走査がOutOfMemoryErrorを起こす実例がある。lessons.md 6.7。Stepsは全期間で数十万件規模になり
// 得るが、件数ではなくこの基準により全件走査を採用している。詳細は`readSourceRecordCounts()`参照）。
// OldestRecordResult（D-034決定事項8）と同じ考え方で、3データ型とも構造が同一のため1つに統合する。
data class SourceRecordCount(val dataOrigin: DataOrigin, val count: Long)

sealed interface SourceRecordCountsResult {
    // historyLimited: 履歴読み取り権限がない（または取得中に失われた）ため、
    // 直近30日分にフォールバックして走査した結果であることを示す。
    data class Success(val counts: List<SourceRecordCount>, val historyLimited: Boolean) : SourceRecordCountsResult

    data object Failure : SourceRecordCountsResult
}
