package com.yskms.healthdataviewer.healthconnect

// WBS 6.10（D-043）: ホーム画面のTotal Caloriesカード用。ActiveCaloriesAggregateTotalResultと同じ形。
sealed interface TotalCaloriesAggregateTotalResult {
    // totalKilocalories: 他のAggregateMetricは問い合わせ範囲にレコードが1件もなければnullになる
    // （AggregationResult.get()の仕様）が、ENERGY_TOTALはこの前提が成り立たない可能性がある。
    // レコードが存在しない期間（2010年の1日）でAggregateを試したところnullではなく非null値（約
    // 1,565kcal）が返ることを実機で確認した（レビュー指摘を受けた簡易確認、2026-10-01）。Android
    // 14+のプラットフォーム側でActive Calories・BMR等から推計値を補っている可能性があるが未確認。
    // そのため「totalKilocalories == null」を「この期間にレコードがない」の判定に使っている
    // isTotalCaloriesCardHidden()（HomeScreen.kt）が意図通り機能しない可能性がある。詳細・要検証事項は
    // lessons.md 6.25、requirements.md §27参照
    data class Success(val totalKilocalories: Double?) : TotalCaloriesAggregateTotalResult

    data object Failure : TotalCaloriesAggregateTotalResult
}
