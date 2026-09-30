package com.yskms.healthdataviewer.healthconnect

import androidx.health.connect.client.records.SleepSessionRecord

sealed interface SleepSessionRecordsResult {
    // StepsRecordsResult/HeartRateRecordsResultと同じ理由でhistoryLimitedを持たない。範囲（直近30日に
    // するかどうかの判断を含む）は呼び出し元（SleepRawRecordsScreen）が決めてTimeRangeFilterとして渡す。
    // Heart RateのLimitReachedのような安全弁は設けていない。Sleep Sessionは1日1〜数件程度で、
    // Heart Rateのように1レコードに大量サンプルを含まないため、全期間・10年規模でも件数はHeart Rateの
    // 規模には遠く及ばない（lessons.md 6.7のOutOfMemoryErrorが起きた規模とは前提が異なる）。
    data class Success(val records: List<SleepSessionRecord>) : SleepSessionRecordsResult

    data object Failure : SleepSessionRecordsResult
}
