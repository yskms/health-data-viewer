package com.yskms.healthdataviewer

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.yskms.healthdataviewer.settings.UserSettingsRepository
import com.yskms.healthdataviewer.settings.toAppCompatNightMode

// WBS 6.6: `userSettingsRepository`をプロセス生存期間中ただ1つのインスタンスとして保持する
// （`MainActivity.onCreate()`のたびに作り直さない。UserSettingsRepository.ktのコメント参照。
// コードレビュー指摘: Activityごとに作り直す設計では、テーマ変更・言語変更によるActivity再生成の
// たびに新しいrepositoryインスタンスが`collectAsState`の既定値から再スタートし、実際の保存値に
// 切り替わるまで一瞬ちらつく問題があった）。
//
// 保存済みテーマ設定（System/Light/Dark）をMainActivity起動より前に反映する。AppCompatDelegateの
// 夜間モードは、言語設定（AppLocalesMetadataHolderServiceによる自動永続化）とは違いプロセス再起動を
// またいで永続化されない（実装はsDefaultNightModeというインメモリのstatic intのみで、
// SharedPreferences等への保存コードは持たない。javapでの逆コンパイルで確認済み）。そのため、
// repositoryの初期化（内部でDataStoreを一度だけブロッキング読み取りする）をApplication.onCreate()
// （必ずどのActivityのonCreate()より先に完了する）の中で行い、MainActivityの最初のフレームより前に
// テーマを確定させる。
class HealthDataViewerApplication : Application() {
    lateinit var userSettingsRepository: UserSettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        userSettingsRepository = UserSettingsRepository(this)
        AppCompatDelegate.setDefaultNightMode(userSettingsRepository.settingsFlow.value.themeMode.toAppCompatNightMode())
    }
}
