package com.yskms.healthdataviewer.healthconnect

// WBS 6.9（コードレビュー指摘）: connect-client 1.1.0のHealthConnectClient.getSdkStatus()をバイトコード
// レベルで確認した結果、UPDATE_REQUIRED/UNAVAILABLEの実際の意味はAndroidのバージョンで大きく異なる
// （lessons.md 6.23）。「Playストアへ誘導すれば解決するか」の判断にこの違いが直結するため、
// HealthConnectManager.availability（この判定を行う唯一の場所）以外でこのenumを安易に拡張・変更しないこと。
enum class HealthConnectAvailability {
    // SDK_AVAILABLE(3)。
    INSTALLED,

    // SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED(2)。API 28〜33限定（34以降はこのコードを返さない）。
    // Health Connectアプリが「未インストール」「無効化」「バージョンが古い」のいずれかで、
    // Playストアへ誘導すれば（インストール／更新のどちらであっても）解決する。
    UPDATE_REQUIRED,

    // SDK_UNAVAILABLE(1)。minSdk 28のこのアプリでは、実質的にAndroid 14以降でwork profile内にいるか、
    // システムにHealth Connectのsystem service自体が存在しない場合のみ発生する。Playストアで
    // インストール操作をしても解決しない（システム側の制約のため）。旧名はNOT_INSTALLEDだったが、
    // 「未インストールだからPlayストアへ誘導すればよい」という誤解を招くため改名した。
    UNAVAILABLE,
}
