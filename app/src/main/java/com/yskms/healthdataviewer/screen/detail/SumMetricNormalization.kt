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
// 【レビューで2回訂正した経緯】
// 1回目（初版）: `ChronoUnit.DAYS.between(periodStart, periodEnd)`で整数に「floor」していた。
// 進行中の最新bucket（endTimeが問い合わせ終了時刻nowに切り詰められ、端数の時間が生じる）で
// 端数分が切り捨てられて分母が実際より小さくなり、値が最大で数十%〜倍近く高く出る不具合があった
// （lessons.md 6.14）。
// 2回目: その場しのぎで小数日数（Duration基準、`Duration.between().toMillis() / 86400000.0`）に
// 変更したところ、今度は逆に、進行中bucketのごく短い経過時間（例: 今日の8時間分）で割ることに
// なり、1日分に外挿した値（例: 7時間の睡眠が21時間/日と表示される）が出る不具合を生んだ
// （レビュー再指摘）。
// 現在の「暦日数（両端含む、繰り上げ）」方式は、両方の不具合を避ける。進行中bucketは経過時間に
// 関わらず必ず1日以上として数えるため外挿にならず（2回目の不具合を回避）、端数を切り捨てず
// 常に繰り上げるため分母が小さくなりすぎることもない（1回目の不具合を回避）。
fun daysCoveredBy(periodStart: LocalDateTime, periodEnd: LocalDateTime): Long {
    val endDate = if (periodEnd.toLocalTime() == LocalTime.MIDNIGHT) periodEnd.toLocalDate() else periodEnd.toLocalDate().plusDays(1)
    return ChronoUnit.DAYS.between(periodStart.toLocalDate(), endDate).coerceAtLeast(1)
}
