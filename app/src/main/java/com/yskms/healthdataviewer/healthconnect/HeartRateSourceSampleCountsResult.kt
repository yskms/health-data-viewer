package com.yskms.healthdataviewer.healthconnect

import androidx.health.connect.client.records.metadata.DataOrigin

// WBS 6.4（D-036）: Heart Rateのデータソース画面用。正確なレコード件数は全件走査が必要で
// （継続記録のためOutOfMemoryErrorの実例があり、Paging化と両立しない。lessons.md 6.7）、
// SourceRecordCountsResult（Weight/Steps/Sleep）とは別に、Aggregateのソース別MEASUREMENTS_COUNT
// （サンプル数。レコード数ではない）で代替する。sampleCountがnull・failedがtrueなのは、
// そのソース単体を指定したAggregate呼び出し1件だけが失敗した場合（poc/StepsScreen.StepsSourceRowの
// officialTotal/officialTotalFailedと同じ考え方。他のソースの取得は失敗させない）。
data class HeartRateSourceSampleCount(val dataOrigin: DataOrigin, val sampleCount: Long?, val failed: Boolean)

sealed interface HeartRateSourceSampleCountsResult {
    data class Success(val counts: List<HeartRateSourceSampleCount>, val historyLimited: Boolean) : HeartRateSourceSampleCountsResult

    data object Failure : HeartRateSourceSampleCountsResult
}
