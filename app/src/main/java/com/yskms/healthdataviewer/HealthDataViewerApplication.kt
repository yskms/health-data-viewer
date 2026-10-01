package com.yskms.healthdataviewer

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.yskms.healthdataviewer.settings.UserSettingsRepository
import com.yskms.healthdataviewer.settings.toAppCompatNightMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

// WBS 6.6: 保存済みテーマ設定（System/Light/Dark）をMainActivity起動より前に反映する。
// AppCompatDelegateの夜間モードは、言語設定（AppLocalesMetadataHolderServiceによる自動永続化）とは
// 違いプロセス再起動をまたいで永続化されない（実装はsDefaultNightModeというインメモリのstatic intの
// みで、SharedPreferences等への保存コードは持たない。javapでの逆コンパイルで確認済み）。そのため、
// 保存先であるDataStoreをここで一度だけ読み、Application.onCreate()（必ずどのActivityのonCreate()より
// 先に完了する）の中で明示的に適用する。DataStoreの読み取りはサスペンド関数だが、ここでのrunBlocking
// （ローカルファイル1つの読み取りのみで、実機で通常数ms以内に完了する）は、MainActivityの最初の
// フレームより前にテーマを確定させるための意図的な措置。
class HealthDataViewerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val themeMode = runBlocking { UserSettingsRepository(this@HealthDataViewerApplication).settingsFlow.first().themeMode }
        AppCompatDelegate.setDefaultNightMode(themeMode.toAppCompatNightMode())
    }
}
