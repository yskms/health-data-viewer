package com.yskms.healthdataviewer.screen.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yskms.healthdataviewer.R
import com.yskms.healthdataviewer.healthconnect.HealthConnectAvailability
import com.yskms.healthdataviewer.healthconnect.HealthConnectManager
import com.yskms.healthdataviewer.healthconnect.HealthConnectPermissions
import com.yskms.healthdataviewer.settings.AppSettings
import com.yskms.healthdataviewer.settings.ThemeMode
import com.yskms.healthdataviewer.settings.UserSettingsRepository
import com.yskms.healthdataviewer.settings.toAppCompatNightMode
import kotlinx.coroutines.launch

private const val GITHUB_REPOSITORY_URL = "https://github.com/yskms/health-data-viewer"

// WBS 6.6: 設定画面（要件§18）。広告削除・プライバシー設定（広告の同意変更）はAdMob/Billing導入
// （WBS 7章）が前提のため、現時点ではこの画面に含めない。
@Composable
fun SettingsScreen(
    healthConnectManager: HealthConnectManager,
    userSettingsRepository: UserSettingsRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val settings by userSettingsRepository.settingsFlow.collectAsState(initial = AppSettings())

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        TextButton(onClick = onBack) {
            Text(text = stringResource(id = R.string.settings_back))
        }
        Text(text = stringResource(id = R.string.settings_title), style = MaterialTheme.typography.titleLarge)

        PermissionsSection(healthConnectManager = healthConnectManager)
        HorizontalDivider()

        VisibleMetricsSection(
            showMetricsWithoutData = settings.showMetricsWithoutData,
            onShowMetricsWithoutDataChange = { show ->
                coroutineScope.launch { userSettingsRepository.setShowMetricsWithoutData(show) }
            },
        )
        HorizontalDivider()

        ThemeSection(
            themeMode = settings.themeMode,
            onThemeModeChange = { mode ->
                coroutineScope.launch {
                    // DataStoreへの書き込みを必ずAppCompatDelegate.setDefaultNightMode()より先に
                    // 完了させる（await）。setDefaultNightMode()はActivityの再生成を引き起こし、
                    // このComposition（とrememberCoroutineScope()）が再生成の過程でキャンセルされる
                    // ため、先にvisual反映だけ行って書き込みをlaunch内の後続処理に任せると、再生成が
                    // 書き込み完了より先に走った場合にDataStoreへの保存がキャンセルされてしまう
                    // （見た目だけ切り替わり、プロセス再起動後は保存されず元に戻る実機確認済みの不具合。
                    // HealthDataViewerApplication.onCreate()が次回起動時に読み直す値と、実際に
                    // 表示されているテーマが食い違う）。
                    userSettingsRepository.setThemeMode(mode)
                    AppCompatDelegate.setDefaultNightMode(mode.toAppCompatNightMode())
                }
            },
        )
        HorizontalDivider()

        LanguageSection()
        HorizontalDivider()

        AboutSection()
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun PermissionsSection(healthConnectManager: HealthConnectManager) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var availability by remember { mutableStateOf(healthConnectManager.availability) }
    var grantedPermissions by remember { mutableStateOf<Set<String>?>(null) }
    var historyFeatureAvailable by remember { mutableStateOf<Boolean?>(null) }

    val requestPermissions =
        rememberLauncherForActivityResult(contract = healthConnectManager.createPermissionRequestContract()) {}

    // HomeScreenと同じ理由（lessons.md 3.1）: 権限状態はこの画面を離れている間にHealth Connect側
    // （本セクションが案内する「Health Connectで管理」からの遷移を含む）で変わり得るため、画面復帰の
    // たびに再確認する。
    LifecycleResumeEffect(Unit) {
        availability = healthConnectManager.availability
        historyFeatureAvailable =
            if (availability == HealthConnectAvailability.INSTALLED) healthConnectManager.isHistoryReadFeatureAvailable else null
        val job =
            if (availability == HealthConnectAvailability.INSTALLED) {
                coroutineScope.launch { grantedPermissions = healthConnectManager.getGrantedPermissions() }
            } else {
                null
            }
        onPauseOrDispose { job?.cancel() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(text = stringResource(id = R.string.settings_section_permissions))
        when (availability) {
            HealthConnectAvailability.NOT_INSTALLED -> Text(text = stringResource(id = R.string.health_connect_not_installed))
            HealthConnectAvailability.UPDATE_REQUIRED -> Text(text = stringResource(id = R.string.health_connect_update_required))
            HealthConnectAvailability.INSTALLED -> {
                PermissionStatusRow(
                    title = stringResource(id = R.string.home_weight_title),
                    granted = grantedPermissions?.contains(HealthConnectPermissions.WEIGHT_READ),
                )
                PermissionStatusRow(
                    title = stringResource(id = R.string.home_steps_title),
                    granted = grantedPermissions?.contains(HealthConnectPermissions.STEPS_READ),
                )
                PermissionStatusRow(
                    title = stringResource(id = R.string.home_heart_rate_title),
                    granted = grantedPermissions?.contains(HealthConnectPermissions.HEART_RATE_READ),
                )
                PermissionStatusRow(
                    title = stringResource(id = R.string.home_sleep_title),
                    granted = grantedPermissions?.contains(HealthConnectPermissions.SLEEP_READ),
                )
                if (historyFeatureAvailable == false) {
                    Text(text = stringResource(id = R.string.health_connect_history_not_supported), style = MaterialTheme.typography.bodySmall)
                } else {
                    PermissionStatusRow(
                        title = stringResource(id = R.string.settings_permission_history),
                        granted = grantedPermissions?.contains(HealthConnectPermissions.HISTORY_READ),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val permissions =
                            buildSet {
                                add(HealthConnectPermissions.WEIGHT_READ)
                                add(HealthConnectPermissions.STEPS_READ)
                                add(HealthConnectPermissions.HEART_RATE_READ)
                                add(HealthConnectPermissions.SLEEP_READ)
                                if (historyFeatureAvailable == true) add(HealthConnectPermissions.HISTORY_READ)
                            }
                        requestPermissions.launch(permissions)
                    }) {
                        Text(text = stringResource(id = R.string.health_connect_request_permission))
                    }
                    TextButton(onClick = {
                        // Health Connectアプリ自体でこのアプリの権限を取り消す/確認する導線
                        // （公式API、androidx.health.connect.client.HealthConnectClient.
                        // getHealthConnectManageDataIntent()）。Health Connectが利用可能な場合のみ
                        // 表示するセクション内のボタンのため、起動に失敗するケースは想定していないが、
                        // IPC越しの操作のため念のためtry/catchする。
                        runCatching { context.startActivity(HealthConnectClient.getHealthConnectManageDataIntent(context)) }
                    }) {
                        Text(text = stringResource(id = R.string.settings_open_health_connect))
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionStatusRow(title: String, granted: Boolean?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = title)
        Text(
            text =
                when (granted) {
                    null -> stringResource(id = R.string.health_connect_checking)
                    true -> stringResource(id = R.string.settings_permission_granted)
                    false -> stringResource(id = R.string.home_permission_not_granted)
                },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun VisibleMetricsSection(showMetricsWithoutData: Boolean, onShowMetricsWithoutDataChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(text = stringResource(id = R.string.settings_section_visible_metrics))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = stringResource(id = R.string.settings_show_metrics_without_data), modifier = Modifier.fillMaxWidth(0.8f))
            Switch(checked = showMetricsWithoutData, onCheckedChange = onShowMetricsWithoutDataChange)
        }
    }
}

private fun ThemeMode.labelRes(): Int =
    when (this) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }

@Composable
private fun ThemeSection(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(text = stringResource(id = R.string.settings_section_theme))
        SingleChoiceSegmentedButtonRow {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = themeMode == mode,
                    onClick = { onThemeModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                ) {
                    Text(text = stringResource(id = mode.labelRes()))
                }
            }
        }
    }
}

// WBS 6.8メモ通り、言語選択はAppCompatDelegateが持つ状態（getApplicationLocales()/
// setApplicationLocales()）を直接読み書きする。AppCompat自身がSharedPreferences
// （Android 12以前、AndroidManifest.xmlのAppLocalesMetadataHolderService）または
// プラットフォームのper-app language機能（Android 13以降、android:localeConfig）で
// 永続化するため、このアプリ独自のDataStoreに重複して保存する必要はない。
private enum class LanguageOption(val localeTag: String?) {
    SYSTEM(null),
    JAPANESE("ja"),
    ENGLISH("en"),
}

private fun LanguageOption.labelRes(): Int =
    when (this) {
        LanguageOption.SYSTEM -> R.string.settings_language_system
        LanguageOption.JAPANESE -> R.string.settings_language_japanese
        LanguageOption.ENGLISH -> R.string.settings_language_english
    }

private fun currentLanguageOption(): LanguageOption {
    val locales = AppCompatDelegate.getApplicationLocales()
    if (locales.isEmpty) return LanguageOption.SYSTEM
    return when (locales[0]?.language) {
        "ja" -> LanguageOption.JAPANESE
        "en" -> LanguageOption.ENGLISH
        else -> LanguageOption.SYSTEM
    }
}

@Composable
private fun LanguageSection() {
    // setApplicationLocales()はこのActivityを再生成するため、再生成後の最初のコンポーズで
    // currentLanguageOption()から選択状態を読み直す（remember、rememberSaveableは不要）。
    var selected by remember { mutableStateOf(currentLanguageOption()) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(text = stringResource(id = R.string.settings_section_language))
        SingleChoiceSegmentedButtonRow {
            LanguageOption.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = selected == option,
                    onClick = {
                        selected = option
                        val localeList =
                            option.localeTag?.let { tag -> LocaleListCompat.forLanguageTags(tag) } ?: LocaleListCompat.getEmptyLocaleList()
                        AppCompatDelegate.setApplicationLocales(localeList)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = LanguageOption.entries.size),
                ) {
                    Text(text = stringResource(id = option.labelRes()))
                }
            }
        }
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val versionName =
        remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(text = stringResource(id = R.string.settings_section_about))
        Text(text = stringResource(id = R.string.app_name), style = MaterialTheme.typography.bodyLarge)
        if (versionName != null) {
            Text(text = stringResource(id = R.string.settings_about_version, versionName), style = MaterialTheme.typography.bodyMedium)
        }
        Text(text = stringResource(id = R.string.settings_about_rights), style = MaterialTheme.typography.bodySmall)
        // プライバシーポリシーへのリンクはWBS 8.1で文言が確定してから追加する（現時点では未作成）。
        TextButton(onClick = { uriHandler.openUri(GITHUB_REPOSITORY_URL) }) {
            Text(text = stringResource(id = R.string.settings_about_github))
        }
    }
}
