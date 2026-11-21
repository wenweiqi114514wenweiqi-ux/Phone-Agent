plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.phoneagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.phoneagent"
        minSdk = 26
        // 目标机型 OPPO Find X9 / Android 17(API 37)：
        // 骨架阶段先对齐 API 36 保证构建稳定，API 37 正式版发布后升 compileSdk/targetSdk。
        targetSdk = 36
        versionCode = 64
        versionName = "1.0.14"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        aidl = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.webkit)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Serialization / Coroutines
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // LLM 网关
    implementation(libs.okhttp)

    // 执行层：Shizuku
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // OCR 文字识别（ML Kit，离线本地识别，支持中文+拉丁）
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
