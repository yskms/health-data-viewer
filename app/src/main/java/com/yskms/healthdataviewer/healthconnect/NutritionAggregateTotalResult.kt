package com.yskms.healthdataviewer.healthconnect

// WBS 6.13（D-054）: ホーム画面の摂取カロリーカード用。TotalCaloriesAggregateTotalResultと似た形だが、
// hasAnyRecord()ガードは**入れていない**。TotalCaloriesBurnedRecord.ENERGY_TOTALのnull非返却バグ
// （実レコードが1件もない期間でも非null値を返す、D-043所見）はそのRecord型固有の確認済み事実で、
// NutritionRecord.ENERGY_TOTALに同じ前提を機械的に当てはめてはいけない（CLAUDE.md「SDK調査で
// 誤解しやすい点」）。Pixel 11実機でレコード0件の期間を指定してnullが返ることを確認してから、
// 必要ならガードを追加する（要検証）。
sealed interface NutritionAggregateTotalResult {
    data class Success(val totalKilocalories: Double?) : NutritionAggregateTotalResult

    data object Failure : NutritionAggregateTotalResult
}
