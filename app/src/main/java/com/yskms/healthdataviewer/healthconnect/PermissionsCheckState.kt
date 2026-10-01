package com.yskms.healthdataviewer.healthconnect

// WBS 6.9: HomeScreen/SettingsScreenの権限確認で共通して使う状態。HealthConnectManager
// .getGrantedPermissions()はIPC呼び出し失敗時にnullを返す（KDoc参照）が、画面側では「まだ確認して
// いない（読み込み中）」と「確認を試みて失敗した」を別の案内として出す必要があり、Boolean?の
// null一値では表現しきれない。
sealed interface PermissionsCheckState {
    data object Loading : PermissionsCheckState

    data object Failure : PermissionsCheckState

    data class Success(val granted: Set<String>) : PermissionsCheckState
}

fun PermissionsCheckState.isGranted(permission: String): Boolean? = (this as? PermissionsCheckState.Success)?.granted?.contains(permission)
