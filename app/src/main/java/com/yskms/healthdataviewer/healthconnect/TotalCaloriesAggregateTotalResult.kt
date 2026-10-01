package com.yskms.healthdataviewer.healthconnect

// WBS 6.10（D-043）: ホーム画面のTotal Caloriesカード用。ActiveCaloriesAggregateTotalResultと同じ形。
sealed interface TotalCaloriesAggregateTotalResult {
    // totalKilocalories: 他のAggregateMetricは問い合わせ範囲にレコードが1件もなければnullになる
    // （AggregationResult.get()の仕様）が、ENERGY_TOTALはこの前提が成り立たない（レコードが存在しない
    // 期間でも非null値、約1,565kcalが返ることを実機で確認。Android 14+のプラットフォーム側でActive
    // Calories・BMR等から推計値を補っていると見られるが未確認）。そのため
    // HealthConnectManager.readTotalCaloriesAggregateTotal()は、Aggregateを呼ぶ前にhasAnyRecord()で
    // 範囲内に実レコードが1件でもあるかを確認し、無ければここをnullにする。実レコードが1件もない期間で
    // このtotalKilocaloriesが非nullになることはない（hasAnyRecord()のガードで防いでいる）。ただし、
    // 一部だけレコードがある期間（ソースが途中で止まった日等）のAggregate自体に推計による水増しが
    // 含まれていないかは未確認のまま残る（要検証、lessons.md 6.26）。詳細はrequirements.md §27参照
    data class Success(val totalKilocalories: Double?) : TotalCaloriesAggregateTotalResult

    data object Failure : TotalCaloriesAggregateTotalResult
}
