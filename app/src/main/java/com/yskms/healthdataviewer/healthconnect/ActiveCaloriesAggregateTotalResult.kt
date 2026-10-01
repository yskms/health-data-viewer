package com.yskms.healthdataviewer.healthconnect

// WBS 6.10（D-043）: ホーム画面のActive Caloriesカード用。DistanceAggregateTotalResultと同じ形
// （bucket分割なしの単発Aggregate、呼び出し元が期間ごとのfilterを安全な範囲に事前クランプする
// 設計のため内部フォールバックを持たない）。
sealed interface ActiveCaloriesAggregateTotalResult {
    // totalKilocalories: 問い合わせ範囲にレコードが1件もなければnull、のはず（AggregationResult.get()の
    // 仕様）。ただしTotalCaloriesBurnedRecord.ENERGY_TOTALでは、レコードが1件もない期間でも非null値
    // （推計値と見られる）が返ることを実機で確認している（TotalCaloriesAggregateTotalResult.kt参照）。
    // ACTIVE_CALORIES_TOTALで同じことが起きるかは、この端末にActiveCaloriesBurnedRecordのデータが
    // なく未確認のまま（要検証、lessons.md 6.26）。確認が取れるまでは、Total Caloriesで入れた
    // hasAnyRecord()による実レコード確認（readTotalCaloriesAggregateTotal()参照）は、根拠のない
    // ガードを増やさないため、こちらにはあえて追加していない。
    data class Success(val totalKilocalories: Double?) : ActiveCaloriesAggregateTotalResult

    data object Failure : ActiveCaloriesAggregateTotalResult
}
