package com.yskms.healthdataviewer.healthconnect

import android.content.Context
import android.os.RemoteException
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit

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

    // client.features.getFeatureStatus()はIPC不要の同期呼び出し（実装はキャッシュされたバージョン情報を
    // 参照するのみ）。Health Connectのアップデートで対応状況が変わり得るため、呼び出し元で画面復帰のたびに
    // 再取得する（availabilityと同じ方針）。
    val isHistoryReadFeatureAvailable: Boolean
        get() =
            client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

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

    // readRecords()のKDocが明記する例外はRemoteException / SecurityException / IOExceptionの3つ
    // （getGrantedPermissions()のIllegalStateExceptionとは異なるので混同しないこと）。
    // 履歴読み取り権限がない状態で30日より古いレコードを読もうとするとSecurityExceptionになる
    // （0件が返るのではなくエラーになる。lessons.md 6.1）。呼び出し元から渡されたhistoryPermissionGrantedを
    // 信頼して初回の範囲を決めるが、取得中に権限が取り消された場合に備えてSecurityExceptionも
    // フォールバックとして処理する。
    //
    // 公式ドキュメント上、履歴権限なしで読める範囲は「権限を最初に許可した時点から30日前」までで、
    // 「現在から30日前」ではない。アプリからは最初の許可日時を直接取得する手段がないため、
    // 安全側（常に許可される範囲のみを要求し、SecurityExceptionを起こさない）に倒して
    // Instant.now().minus(30日)を使う。時間が経つほど実際に読める範囲より狭く見せる可能性があるが、
    // エラーにはならない（lessons.md 6.1）。
    //
    // recordType = WeightRecord::classの形式でReadRecordsRequestを構築すると、deduplicateStrategyは
    // 指定しなくてもDISABLED（重複排除なし）になる（D-007と整合。CLAUDE.md・lessons.md 6.3参照）。
    suspend fun readAllWeightRecords(historyPermissionGranted: Boolean): WeightRecordsResult {
        val recentRangeFilter = TimeRangeFilter.after(Instant.now().minus(30, ChronoUnit.DAYS))
        val initialFilter = if (historyPermissionGranted) TimeRangeFilter.before(Instant.now()) else recentRangeFilter

        suspend fun readPaged(filter: TimeRangeFilter): List<WeightRecord> {
            val records = mutableListOf<WeightRecord>()
            var pageToken: String? = null
            do {
                val response =
                    client.readRecords(
                        ReadRecordsRequest(
                            recordType = WeightRecord::class,
                            timeRangeFilter = filter,
                            ascendingOrder = false,
                            pageToken = pageToken,
                        ),
                    )
                records += response.records
                pageToken = response.pageToken?.ifEmpty { null }
            } while (pageToken != null)
            return records
        }

        suspend fun readSafely(filter: TimeRangeFilter, historyLimited: Boolean): WeightRecordsResult =
            try {
                WeightRecordsResult.Success(records = readPaged(filter), historyLimited = historyLimited)
            } catch (e: RemoteException) {
                WeightRecordsResult.Failure
            } catch (e: IOException) {
                WeightRecordsResult.Failure
            } catch (e: SecurityException) {
                WeightRecordsResult.Failure
            }

        return try {
            WeightRecordsResult.Success(
                records = readPaged(initialFilter),
                historyLimited = !historyPermissionGranted,
            )
        } catch (e: SecurityException) {
            readSafely(recentRangeFilter, historyLimited = true)
        } catch (e: RemoteException) {
            WeightRecordsResult.Failure
        } catch (e: IOException) {
            WeightRecordsResult.Failure
        }
    }
}
