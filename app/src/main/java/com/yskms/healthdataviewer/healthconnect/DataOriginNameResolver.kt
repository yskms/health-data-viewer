package com.yskms.healthdataviewer.healthconnect

import android.content.Context
import android.content.pm.PackageManager

// QUERY_ALL_PACKAGES等の特別な権限は不要（公式ドキュメント「Data display and attribution」）。
// ただしAndroid 11+のパッケージ可視性制限は別に効いており、AndroidManifest.xmlの<queries>宣言
// （androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALEを持つアプリを可視にする）に依存している。
// この<queries>を削除すると、解決できるアプリの範囲が狭まりフォールバック（packageNameそのまま表示）
// が増える（lessons.md 6.4）。
object DataOriginNameResolver {
    fun resolve(context: Context, packageName: String): String {
        val packageManager = context.packageManager
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}
