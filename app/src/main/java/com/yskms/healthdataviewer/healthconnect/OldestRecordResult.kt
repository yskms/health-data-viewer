package com.yskms.healthdataviewer.healthconnect

import java.time.Instant

// WBS 6.2: findOldestWeightRecordTime() / findOldestHeartRateRecordTime() /
// findOldestSleepSessionRecordTime() / findOldestStepsRecordTime()に共通の結果型。
// 4つとも構造が同一（時刻の意味＝各レコード型の開始時刻、historyLimitedの意味＝履歴読み取り権限が
// ないため直近30日分の範囲内で探した結果であること）だったため、データ型ごとの
// Oldest*RecordResultファイルを1つに統合した（WBS 2.1〜5.1で追加した3ファイルは削除済み）。
sealed interface OldestRecordResult {
    // time: 読み取れた範囲内で最古のレコード時刻。1件もなければnull。
    data class Success(val time: Instant?, val historyLimited: Boolean) : OldestRecordResult

    data object Failure : OldestRecordResult
}
