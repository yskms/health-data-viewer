package com.yskms.healthdataviewer.healthconnect

import android.content.Context
import android.os.RemoteException
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import java.io.IOException

// 権限の許可状態はアプリ内にキャッシュせず、呼び出しのたびにHealth Connectへ問い合わせる。
// Health Connectの設定などアプリ外から権限が取り消され得るため（lessons.md 3.1）。
class HealthConnectManager(context: Context) {
    private val appContext = context.applicationContext

    val availability: HealthConnectAvailability
        get() =
            when (HealthConnectClient.getSdkStatus(appContext)) {
                HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.INSTALLED
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                    HealthConnectAvailability.UPDATE_REQUIRED
                else -> HealthConnectAvailability.NOT_INSTALLED
            }

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(appContext) }

    // getGrantedPermissions()はIPC呼び出しのため、Health Connect側の更新中などに
    // RemoteException / IOException / IllegalStateExceptionを投げ得る（KDoc記載）。
    // 呼び出し元を巻き込んで落とさず、失敗はnullとして伝える（lessons.md 4.1）。
    suspend fun getGrantedPermissions(): Set<String>? =
        try {
            client.permissionController.getGrantedPermissions()
        } catch (e: RemoteException) {
            null
        } catch (e: IOException) {
            null
        } catch (e: IllegalStateException) {
            null
        }

    fun createPermissionRequestContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()
}
