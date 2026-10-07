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
import com.yskms.healthdataviewer.healthconnect.PermissionsCheckState
import com.yskms.healthdataviewer.healthconnect.isGranted
import com.yskms.healthdataviewer.screen.common.HealthConnectUnavailableNotice
import com.yskms.healthdataviewer.screen.common.PermissionsCheckFailedNotice
import com.yskms.healthdataviewer.settings.ThemeMode
import com.yskms.healthdataviewer.settings.UserSettingsRepository
import kotlinx.coroutines.Job
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
    // userSettingsRepository.settingsFlowはStateFlow（常に最新値を持つ）のため、
    // collectAsState()にinitialを渡す必要がない（UserSettingsRepository.kt参照。
    // 渡していた初版は、Activity再生成のたびにハードコードした既定値から一瞬始まる問題があった）。
    val settings by userSettingsRepository.settingsFlow.collectAsState()

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
            onShowMetricsWithoutDataChange = { show -> userSettingsRepository.setShowMetricsWithoutData(show) },
        )
        HorizontalDivider()

        ThemeSection(
            themeMode = settings.themeMode,
            // 書き込み・AppCompatDelegateへの適用は`UserSettingsRepository`自身のスコープで行う
            // （呼び出し元のComposition/rememberCoroutineScope()に依存しない。lessons.md 10.2・10.3参照）。
            onThemeModeChange = { mode -> userSettingsRepository.setThemeModeAndApply(mode) },
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
    var permissionsCheckState by remember { mutableStateOf<PermissionsCheckState>(PermissionsCheckState.Loading) }
    var historyFeatureAvailable by remember { mutableStateOf<Boolean?>(null) }

    val requestPermissions =
        rememberLauncherForActivityResult(contract = healthConnectManager.createPermissionRequestContract()) {}

    // WBS 6.9: HomeScreenのrefreshPermissions()と同じ理由（lessons.md 4.1）。権限確認のIPC呼び出し
    // 自体の失敗を、まだ確認していない状態と区別してPermissionsCheckStateで保持する。
    // コードレビュー指摘への対応（HomeScreen.ktのrefreshPermissions()と同じ理由）: 直近のJobだけを
    // `refreshJob`に保持し、新しく始める前に前のJobをキャンセルする。`resetToLoading`は手動再試行時のみ。
    var refreshJob by remember { mutableStateOf<Job?>(null) }

    fun refreshPermissions(resetToLoading: Boolean = false) {
        refreshJob?.cancel()
        if (resetToLoading) permissionsCheckState = PermissionsCheckState.Loading
        refreshJob =
            coroutineScope.launch {
                permissionsCheckState =
                    when (val result = healthConnectManager.getGrantedPermissions()) {
                        null -> PermissionsCheckState.Failure
                        else -> PermissionsCheckState.Success(result)
                    }
            }
    }

    // HomeScreenと同じ理由（lessons.md 3.1）: 権限状態はこの画面を離れている間にHealth Connect側
    // （本セクションが案内する「Health Connectで管理」からの遷移を含む）で変わり得るため、画面復帰の
    // たびに再確認する。
    LifecycleResumeEffect(Unit) {
        availability = healthConnectManager.availability
        historyFeatureAvailable =
            if (availability == HealthConnectAvailability.INSTALLED) healthConnectManager.isHistoryReadFeatureAvailable else null
        if (availability == HealthConnectAvailability.INSTALLED) refreshPermissions()
        onPauseOrDispose { refreshJob?.cancel() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(text = stringResource(id = R.string.settings_section_permissions))
        when (availability) {
            HealthConnectAvailability.UNAVAILABLE, HealthConnectAvailability.UPDATE_REQUIRED ->
                HealthConnectUnavailableNotice(availability = availability, healthConnectManager = healthConnectManager)
            HealthConnectAvailability.INSTALLED -> {
                if (permissionsCheckState is PermissionsCheckState.Failure) {
                    PermissionsCheckFailedNotice(onRetryClick = { refreshPermissions(resetToLoading = true) })
                }

                PermissionStatusRow(
                    title = stringResource(id = R.string.home_weight_title),
                    permissionsCheckState = permissionsCheckState,
                    permission = HealthConnectPermissions.WEIGHT_READ,
                )
                PermissionStatusRow(
                    title = stringResource(id = R.string.home_steps_title),
                    permissionsCheckState = permissionsCheckState,
                    permission = HealthConnectPermissions.STEPS_READ,
                )
                PermissionStatusRow(
                    title = stringResource(id = R.string.home_heart_rate_title),
                    permissionsCheckState = permissionsCheckState,
                    permission = HealthConnectPermissions.HEART_RATE_READ,
                )
                PermissionStatusRow(
                    title = stringResource(id = R.string.home_resting_heart_rate_title),
                    permissionsCheckState = permissionsCheckState,
                    permission = HealthConnectPermissions.RESTING_HEART_RATE_READ,
                )
                PermissionStatusRow(
                    title = stringResource(id = R.string.home_sleep_title),
                    permissionsCheckState = permissionsCheckState,
                    permission = HealthConnectPermissions.SLEEP_READ,
                )
                PermissionStatusRow(
                    title = stringResource(id = R.string.home_distance_title),
                    permissionsCheckState = permissionsCheckState,
                    permission = HealthConnectPermissions.DISTANCE_READ,
                )
                PermissionStatusRow(
                    title = stringResource(id = R.string.home_active_calories_title),
                    permissionsCheckState = permissionsCheckState,
                    permission = HealthConnectPermissions.ACTIVE_CALORIES_READ,
                )
                PermissionStatusRow(
                    title = stringResource(id = R.string.home_total_calories_title),
                    permissionsCheckState = permissionsCheckState,
                    permission = HealthConnectPermissions.TOTAL_CALORIES_READ,
                )
                PermissionStatusRow(
                    title = stringResource(id = R.string.home_blood_pressure_title),
                    permissionsCheckState = permissionsCheckState,
                    permission = HealthConnectPermissions.BLOOD_PRESSURE_READ,
                )
                PermissionStatusRow(
                    title = stringResource(id = R.string.home_body_fat_title),
                    permissionsCheckState = permissionsCheckState,
                    permission = HealthConnectPermissions.BODY_FAT_READ,
                )
                PermissionStatusRow(
                    title = stringResource(id = R.string.home_hrv_title),
                    permissionsCheckState = permissionsCheckState,
                    permission = HealthConnectPermissions.HRV_READ,
                )
                PermissionStatusRow(
                    title = stringResource(id = R.string.home_oxygen_saturation_title),
                    permissionsCheckState = permissionsCheckState,
                    permission = HealthConnectPermissions.OXYGEN_SATURATION_READ,
                )
                PermissionStatusRow(
                    title = stringResource(id = R.string.home_blood_glucose_title),
                    permissionsCheckState = permissionsCheckState,
                    permission = HealthConnectPermissions.BLOOD_GLUCOSE_READ,
                )
                PermissionStatusRow(
                    title = stringResource(id = R.string.home_exercise_title),
                    permissionsCheckState = permissionsCheckState,
                    permission = HealthConnectPermissions.EXERCISE_READ,
                )
                PermissionStatusRow(
                    title = stringResource(id = R.string.home_nutrition_title),
                    permissionsCheckState = permissionsCheckState,
                    permission = HealthConnectPermissions.NUTRITION_READ,
                )
                if (historyFeatureAvailable == false) {
                    Text(text = stringResource(id = R.string.health_connect_history_not_supported), style = MaterialTheme.typography.bodySmall)
                } else {
                    PermissionStatusRow(
                        title = stringResource(id = R.string.settings_permission_history),
                        permissionsCheckState = permissionsCheckState,
                        permission = HealthConnectPermissions.HISTORY_READ,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val permissions =
                            buildSet {
                                add(HealthConnectPermissions.WEIGHT_READ)
                                add(HealthConnectPermissions.STEPS_READ)
                                add(HealthConnectPermissions.HEART_RATE_READ)
                                add(HealthConnectPermissions.RESTING_HEART_RATE_READ)
                                add(HealthConnectPermissions.SLEEP_READ)
                                add(HealthConnectPermissions.DISTANCE_READ)
                                add(HealthConnectPermissions.ACTIVE_CALORIES_READ)
                                add(HealthConnectPermissions.TOTAL_CALORIES_READ)
                                add(HealthConnectPermissions.BLOOD_PRESSURE_READ)
                                add(HealthConnectPermissions.BODY_FAT_READ)
                                add(HealthConnectPermissions.HRV_READ)
                                add(HealthConnectPermissions.OXYGEN_SATURATION_READ)
                                add(HealthConnectPermissions.BLOOD_GLUCOSE_READ)
                                add(HealthConnectPermissions.EXERCISE_READ)
                                add(HealthConnectPermissions.NUTRITION_READ)
                                if (historyFeatureAvailable == true) add(HealthConnectPermissions.HISTORY_READ)
                            }
                        requestPermissions.launch(permissions)
                    }) {
                        Text(text = stringResource(id = R.string.health_connect_request_permission))
                    }
                    TextButton(onClick = {
                        // 公式API（androidx.health.connect.client.HealthConnectClient.
                        // getHealthConnectManageDataIntent()）でHealth Connect本体の「データと
                        // アクセス」画面（全アプリ共通のトップ画面）を開く。このアプリ個別の権限行へ
                        // 直接遷移する意図ではない（コードレビュー指摘：そうしたAPIはconnect-client
                        // 1.1.0にはなく、プラットフォーム側のAPI 34+限定intentに頼ると対応範囲が
                        // minSdk 28と食い違う）。ユーザーはこの画面から対象アプリを自分で探す必要がある。
                        // Health Connectが利用可能な場合のみ表示するセクション内のボタンのため、
                        // 起動に失敗するケースは想定していないが、IPC越しの操作のため念のためtry/catchする。
                        runCatching { context.startActivity(HealthConnectClient.getHealthConnectManageDataIntent(context)) }
                    }) {
                        Text(text = stringResource(id = R.string.settings_open_health_connect))
                    }
                }
            }
        }
    }
}

// granted(permission)がnullなのは「読み込み中」だけでなく、権限確認自体の失敗（WBS 6.9、
// lessons.md 4.1）もあり得るため、permissionsCheckStateを見てテキストを出し分ける。
@Composable
private fun PermissionStatusRow(title: String, permissionsCheckState: PermissionsCheckState, permission: String) {
    val granted = permissionsCheckState.isGranted(permission)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = title)
        Text(
            text =
                stringResource(
                    id =
                        when {
                            granted == true -> R.string.settings_permission_granted
                            granted == false -> R.string.home_permission_not_granted
                            permissionsCheckState is PermissionsCheckState.Failure -> R.string.health_connect_check_failed
                            else -> R.string.health_connect_checking
                        },
                ),
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
