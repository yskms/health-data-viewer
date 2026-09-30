package com.yskms.healthdataviewer.screen.detail

// WBS 6.2: 既存poc/*GraphScreenのAggregatesLoad/HeartRateAggregatesLoad/SleepAggregatesLoadを
// 汎用化したもの（lessons.md 7.6）。resultがnullはそのperiodを読み込み中であることを示す。period
// 切り替え直後、LaunchedEffectが更新するまでの間は前のperiodのタグが残るため、表示側は
// `currentLoad.period == 表示中period`の一致を確認してから使う（既存3画面と同じ理由）。
data class PeriodTaggedResult<R>(val period: GraphPeriod, val result: R?)
