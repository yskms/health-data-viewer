package com.yskms.healthdataviewer.screen.detail

import com.yskms.healthdataviewer.R

// WBS 6.2: 詳細画面の期間タブ（要件§7.1）。1W/1M/3M/6M/1YはWeight/Steps/HeartRate/Sleep共通で
// トレイリングウィンドウ（暦ベース、DetailGraphRange.kt参照）、ALLは最古レコードから、Customは
// ユーザーが選んだ開始日・終了日を使う。3Y/5Yは要件上も「追加を検討」止まりのため対象外。
enum class GraphPeriod {
    WEEK,
    MONTH,
    THREE_MONTHS,
    SIX_MONTHS,
    YEAR,
    ALL,
    CUSTOM,
}

fun GraphPeriod.labelRes(): Int =
    when (this) {
        GraphPeriod.WEEK -> R.string.detail_period_week
        GraphPeriod.MONTH -> R.string.detail_period_month
        GraphPeriod.THREE_MONTHS -> R.string.detail_period_three_months
        GraphPeriod.SIX_MONTHS -> R.string.detail_period_six_months
        GraphPeriod.YEAR -> R.string.detail_period_year
        GraphPeriod.ALL -> R.string.detail_period_all
        GraphPeriod.CUSTOM -> R.string.detail_period_custom
    }
