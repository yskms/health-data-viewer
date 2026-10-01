package com.yskms.healthdataviewer.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// 要件§21: 設定の保存はDataStore（テーマ・言語・表示指標・購入状態のキャッシュ）。
// ここではテーマ・表示指標のみを扱う。言語はAppCompatDelegateが自前で永続化する
// （AndroidManifest.xmlのAppLocalesMetadataHolderService、D-022、WBS 6.8メモ）ため対象外。
// 購入状態（WBS 7.4）はこの時点では未実装。
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

fun ThemeMode.toAppCompatNightMode(): Int =
    when (this) {
        ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
    }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    // 要件§6「データがない項目」の表示設定。デフォルトtrue（すべて表示）はWBS 6.1〜6.5時点の
    // 既存の既定動作（設定自体がまだ存在せず常にすべて表示していた）を変えないための選択。
    val showMetricsWithoutData: Boolean = true,
)

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class UserSettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val SHOW_METRICS_WITHOUT_DATA = booleanPreferencesKey("show_metrics_without_data")
    }

    val settingsFlow: Flow<AppSettings> =
        dataStore.data.map { preferences ->
            val storedThemeMode = preferences[THEME_MODE]
            AppSettings(
                themeMode = storedThemeMode?.let { stored -> runCatching { ThemeMode.valueOf(stored) }.getOrNull() } ?: ThemeMode.SYSTEM,
                showMetricsWithoutData = preferences[SHOW_METRICS_WITHOUT_DATA] ?: true,
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    suspend fun setShowMetricsWithoutData(show: Boolean) {
        dataStore.edit { it[SHOW_METRICS_WITHOUT_DATA] = show }
    }
}
