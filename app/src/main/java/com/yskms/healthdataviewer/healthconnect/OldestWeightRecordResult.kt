package com.yskms.healthdataviewer.healthconnect

import java.time.Instant

sealed interface OldestWeightRecordResult {
    // time: 読み取れた範囲内で最古のレコード時刻。1件もなければnull。
    // historyLimited: 履歴読み取り権限がない（または取得中に失われた）ため、
    // 直近30日分の範囲内で探した結果であることを示す（readAllWeightRecords()のhistoryLimitedと同じ意味）。
    data class Success(val time: Instant?, val historyLimited: Boolean) : OldestWeightRecordResult

    data object Failure : OldestWeightRecordResult
}
