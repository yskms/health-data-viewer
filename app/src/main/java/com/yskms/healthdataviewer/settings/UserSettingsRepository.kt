package com.yskms.healthdataviewer.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException

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

// DataStore公式パターン: ファイル破損時はemptyPreferences()に読み替えて起動不能を避ける
// （コードレビュー指摘。追加のtry/catchなしでは、バックアップ復元の失敗等でファイルが壊れた場合に
// Application.onCreate()の初期読み取りが例外を投げ、起動のたびにクラッシュしうる）。
private val Context.settingsDataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

// WBS 6.6: HealthDataViewerApplicationがプロセス生存期間中ただ1つのインスタンスを保持する
// （MainActivity.onCreate()のたびに作り直さない。HealthDataViewerApplication.kt参照）。
//
// 読み取りはin-memoryの`StateFlow`（settingsFlow）を正とする。DataStore（`dataStore.data`）を
// Composeの`collectAsState()`に直接つなぐ初版の設計には、Activity再生成のたびに`collectAsState`の
// `initial`値（ハードコードした既定値＝System）から始まり、DataStoreからの最初の読み取りが届くまでの
// 一瞬、実際の保存値と異なる表示になる問題があった（コードレビュー指摘。テーマ変更自体がActivity
// 再生成を伴うため、変更直後に選択表示が一瞬「System」に戻って見える、表示指標トグルがOFFでも
// 起動直後は一瞬すべてのカードが表示される、など）。`MutableStateFlow`は常に最新値を持つため、
// このズレが起きない。
//
// 書き込みは`scope`（Dispatchers.Default + SupervisorJob、どのActivity・Compositionにも属さず
// プロセスと生存期間を共にする）上で行う。呼び出し元の`rememberCoroutineScope()`で書き込むと、
// 書き込みが引き起こすActivity再生成（テーマ変更時の`setDefaultNightMode()`）がその書き込み自身を
// 道連れにキャンセルしうる（実機確認済みの不具合、lessons.md 10.2）。repository自身のスコープで
// 書き込むことで、呼び出し元のライフサイクルと無関係に完了する。
class UserSettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val SHOW_METRICS_WITHOUT_DATA = booleanPreferencesKey("show_metrics_without_data")
    }

    // 起動時に一度だけ行うブロッキング読み取り（HealthDataViewerApplication.onCreate()から、
    // MainActivity生成より前に呼ばれる想定。ローカルファイル1つの読み取りのみで通常数ms以内に完了する）。
    private val _settings = MutableStateFlow(runBlocking { dataStore.data.recoverFromIOException().first().toAppSettings() })
    val settingsFlow: StateFlow<AppSettings> = _settings.asStateFlow()

    fun setThemeModeAndApply(mode: ThemeMode) {
        _settings.value = _settings.value.copy(themeMode = mode)
        AppCompatDelegate.setDefaultNightMode(mode.toAppCompatNightMode())
        persist { it[THEME_MODE] = mode.name }
    }

    fun setShowMetricsWithoutData(show: Boolean) {
        _settings.value = _settings.value.copy(showMetricsWithoutData = show)
        persist { it[SHOW_METRICS_WITHOUT_DATA] = show }
    }

    // DataStoreへの書き込み失敗（IOException等）は、見た目にはすでに反映済みの設定値を
    // 次回起動時に復元できなくなるだけで、アプリの続行は妨げない。読み取り側と同様に
    // 例外で落とさず諦める（他のDataStore呼び出し元と同じ「失敗は握りつぶして継続する」方針）。
    private fun persist(edit: (MutablePreferences) -> Unit) {
        scope.launch { runCatching { dataStore.edit(edit) } }
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val storedThemeMode = this[THEME_MODE]
        return AppSettings(
            themeMode = storedThemeMode?.let { stored -> runCatching { ThemeMode.valueOf(stored) }.getOrNull() } ?: ThemeMode.SYSTEM,
            showMetricsWithoutData = this[SHOW_METRICS_WITHOUT_DATA] ?: true,
        )
    }

    private fun Flow<Preferences>.recoverFromIOException(): Flow<Preferences> =
        catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
}
