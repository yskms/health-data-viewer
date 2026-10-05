package com.yskms.healthdataviewer.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// WBS 6.1: ホーム画面の指標カードの視認性向上のためのアクセントカラー（データ型ごとに1色）。
// Material3のColorSchemeのロールには当てはめず、HomeScreenから直接参照する。
// テーマ全体のダーク/ライト設計（要件§12）はWBS 6.7で扱うため、ここではカード用の1色ずつに留める。
val WeightAccent = Color(0xFF6750A4)
val StepsAccent = Color(0xFF386A20)
val HeartRateAccent = Color(0xFFB3261E)
val RestingHeartRateAccent = Color(0xFFAD1457)
val SleepAccent = Color(0xFF1B5E7A)
val DistanceAccent = Color(0xFF8B5000)
val ActiveCaloriesAccent = Color(0xFFE65100)
val TotalCaloriesAccent = Color(0xFF00695C)
val BloodPressureAccent = Color(0xFF3949AB)
val BodyFatAccent = Color(0xFF7B5800)
// コードレビュー指摘: 当初0xFF1565C0（青）はBloodPressureAccent（インディゴ）・SleepAccent
// （青緑）と同系統で見分けにくいという指摘を受け、既存のどの色とも異なる紫系に変更した。
val HrvAccent = Color(0xFF8E24AA)
// WBS 6.10（SpO2追加）: 当初0xFF0277BD（Light Blue 800）にしたが、コードレビューで、HRVの
// コメント（上記）が「同系統で見分けにくい」として却下した0xFF1565C0とほぼ同じ色相・明度（RGB差が
// 20前後）であり、却下理由がそのまま当てはまるという指摘を受けた。はっきりシアン寄りで、
// SleepAccent（青緑）・TotalCaloriesAccent（緑青）のどちらとも離れた0xFF00ACC1（Cyan 600。
// Cyan 700は0xFF0097A7、コードレビューで誤記を指摘された）に変更した。
val OxygenSaturationAccent = Color(0xFF00ACC1)
// WBS 6.10（Blood Glucose追加）: 既存のHeartRateAccent（赤）・RestingHeartRateAccent（ピンク）・
// Distance/BodyFatAccent（オレンジ〜黄土）・ActiveCaloriesAccent（オレンジ）のいずれとも異なる
// オリーブ系（黄緑寄り）にし、既存アクセントカラーとの見分けやすさを確保した。
val BloodGlucoseAccent = Color(0xFF827717)
// WBS 6.10（Exercise追加）: 当初0xFF0D47A1（Material Blue 900、色相約216°）にしたが、コードレビューで
// 実際にHSL色相を計算した結果、Sleep（約198°）〜BloodPressure（約232°）という、HRV/SpO2のレビューで
// 「同系統で見分けにくい」として却下された0xFF1565C0（約212°）とほぼ同じ帯のただ中にあり、
// 「その帯を避けた」というコメントの説明と実際の値が矛盾していた指摘を受けた（「明度が高い」という
// 説明も、L=34%という暗い部類の値と逆だった）。既存12色すべての色相を計算し直し、Steps（約101°、
// 緑）とTotalCaloriesAccent（約173°、緑青）の間に72度ある未使用の帯（黄緑〜若草色）を見つけ、
// その中央付近の若草色0xFF1B8A3D（色相約138°、両隣とおよそ37度ずつ離れる）に変更した。
val ExerciseAccent = Color(0xFF1B8A3D)
