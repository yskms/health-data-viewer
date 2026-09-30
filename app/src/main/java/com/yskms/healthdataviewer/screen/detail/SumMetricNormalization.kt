package com.yskms.healthdataviewer.screen.detail

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

// 決定事項5: sum系メトリクス（Steps合計、Sleep合計時間）は、bucketが1日より粗い場合（WEEK/MONTH）、
// 実際にそのbucketがカバーする日数で割った「1日あたり平均」として表示する。avg/min/max系メトリクス
// （Weight, HeartRate）には適用しない（平均はbucketの粗さに関係なく比較可能なため）。
//
// 日数は「bucketが少しでもかかっている暦日」を両端含めて数える（ホーム画面のD-033・lessons.md 6.10
// と同じ「暦日数（両端含む）」の考え方に統一）。開始時刻はそのまま日付に切り捨て、終了時刻は
// ちょうど0時でない限り翌日に繰り上げてから、2つの日付の差を数える。
//
// 経過時間の割合で日数を数めると、進行中の最新bucket（endTimeが問い合わせ終了時刻nowに切り詰められ、
// 端数の時間が生じる）で、整数floorは分母が小さくなりすぎて値が高く出過ぎ、小数はごく短い経過時間
// （例: 今日の数時間分）で割って1日分に外挿してしまう。暦日数（両端含む、繰り上げ）方式は
// 進行中bucketを経過時間に関わらず必ず1日以上として数えるため、どちらの不具合も起きない
// （経緯はlessons.md 6.14参照）。
fun daysCoveredBy(periodStart: LocalDateTime, periodEnd: LocalDateTime): Long {
    val endDate = if (periodEnd.toLocalTime() == LocalTime.MIDNIGHT) periodEnd.toLocalDate() else periodEnd.toLocalDate().plusDays(1)
    return ChronoUnit.DAYS.between(periodStart.toLocalDate(), endDate).coerceAtLeast(1)
}
