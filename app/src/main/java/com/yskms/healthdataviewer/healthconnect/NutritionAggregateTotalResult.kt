package com.yskms.healthdataviewer.healthconnect

// WBS 6.13（D-054）: ホーム画面の摂取カロリーカード用。TotalCaloriesAggregateTotalResultと似た形だが、
// hasAnyRecord()ガードは入れていない。TotalCaloriesBurnedRecord.ENERGY_TOTALのnull非返却バグ
// （実レコードが1件もない期間でも非null値を返す、D-043所見）と同じ現象がNutritionRecord.ENERGY_TOTAL
// にもあるかは、Pixel 11実機（2010年のレコード0件の期間、一時的な診断コードで確認）でnullが返ることを
// 確認済みで、再現しないと分かっている（D-054、totalKilocalories = nullが「記録が無い」に対応し、
// NutritionCardは正しく「データなし」を表示する）。一方、「レコード自体はあるがenergyが未設定の日」
// （あすけんが実際に書き込む例がある）は、このガードの対象外のまま0kcalとして扱われる限界が残る
// （要検証、requirements.md §27。詳細はHealthConnectManager.readNutritionAggregates()のコメント参照）。
sealed interface NutritionAggregateTotalResult {
    data class Success(val totalKilocalories: Double?) : NutritionAggregateTotalResult

    data object Failure : NutritionAggregateTotalResult
}
