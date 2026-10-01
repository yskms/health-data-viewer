plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.yskms.healthdataviewer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yskms.healthdataviewer"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.health.connect.client)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // vico-compose/-m3はKotlin Multiplatform向けに公開されており、Android targetでも
    // org.jetbrains.compose.*（JetBrains Compose Multiplatformの別配布）を推移的依存に持つ。
    // これがBOM管理下のandroidx.compose.*と同じパッケージ名でバージョンの異なるクラスを
    // 持ち込み、Modifier.weight()等の解決がおかしくなる（WBS 6.3でPaging3のRecordsタブ実装中に
    // 初めて.weight()を使って発覚）。org.jetbrains.compose.foundation側はAndroid targetでは
    // androidx.compose.foundation（BOM管理下、1.12.1）へのエイリアスにすぎないため、除外しても
    // 実体は残る。
    implementation(libs.vico.compose) {
        exclude(group = "org.jetbrains.compose.animation")
        exclude(group = "org.jetbrains.compose.annotation-internal")
        exclude(group = "org.jetbrains.compose.collection-internal")
        exclude(group = "org.jetbrains.compose.foundation")
        exclude(group = "org.jetbrains.compose.material")
        exclude(group = "org.jetbrains.compose.runtime")
        exclude(group = "org.jetbrains.compose.ui")
    }
    implementation(libs.vico.compose.m3) {
        exclude(group = "org.jetbrains.compose.animation")
        exclude(group = "org.jetbrains.compose.annotation-internal")
        exclude(group = "org.jetbrains.compose.collection-internal")
        exclude(group = "org.jetbrains.compose.foundation")
        exclude(group = "org.jetbrains.compose.material")
        exclude(group = "org.jetbrains.compose.runtime")
        exclude(group = "org.jetbrains.compose.ui")
    }
    debugImplementation(libs.androidx.ui.tooling)
}
