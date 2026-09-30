package com.yskms.healthdataviewer.healthconnect

import androidx.health.connect.client.records.HeartRateRecord

sealed interface HeartRateRecordsResult {
    // StepsRecordsResultと同じ理由でhistoryLimitedを持たない。Heart Rateの公式Aggregate
    // （BPM_AVG/BPM_MIN/BPM_MAX/MEASUREMENTS_COUNT）には重複処理がなく（requirements.md §22.2）、
    // Stepsのように範囲を完全一致させて比較する必要がないが、呼び出し元（画面）が期間ごとに
    // TimeRangeFilterを決める設計はStepsと揃え、historyLimitedの判定も呼び出し元に一元化する。
    data class Success(val records: List<HeartRateRecord>) : HeartRateRecordsResult

    // WBS 4.1/4.2: 全期間のRaw全件読み込みが実機でOutOfMemoryErrorを起こした（lessons.md 6.7）ため、
    // HealthConnectManager.HEART_RATE_RAW_SAMPLE_LIMIT（サンプル数）に達した時点でページングを打ち切る。
    // records は打ち切るまでに読めた分（部分的な結果）。上限に達していないSuccessと区別して扱う。
    data class LimitReached(val records: List<HeartRateRecord>) : HeartRateRecordsResult

    data object Failure : HeartRateRecordsResult
}
