plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.scenemate.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.scenemate.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-p0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    /**
     * 签名配置：从 local.properties 或环境变量读取，**密码不进仓库**。
     * 本地构建：在 05-app/local.properties 里加
     *   STORE_FILE=../keystore/scenemate-test.jks
     *   STORE_PASSWORD=xxx
     *   KEY_ALIAS=xxx
     *   KEY_PASSWORD=xxx
     * CI 构建：用环境变量 SCENEMATE_STORE_PASSWORD 等。
     * 没配置时 release 包不签名（debug key 兜底），不影响开发。
     */
    val storeFileStr = (project.findProperty("STORE_FILE") as String?)
        ?: System.getenv("SCENEMATE_STORE_FILE")
    val storePass = (project.findProperty("STORE_PASSWORD") as String?)
        ?: System.getenv("SCENEMATE_STORE_PASSWORD")
    val keyAliasVal = (project.findProperty("KEY_ALIAS") as String?)
        ?: System.getenv("SCENEMATE_KEY_ALIAS")
    val keyPass = (project.findProperty("KEY_PASSWORD") as String?)
        ?: System.getenv("SCENEMATE_KEY_PASSWORD")

    signingConfigs {
        if (storeFileStr != null && storePass != null && keyAliasVal != null && keyPass != null) {
            create("release") {
                storeFile = file(storeFileStr)
                storePassword = storePass
                keyAlias = keyAliasVal
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (storeFileStr != null) signingConfigs.getByName("release") else null
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    // 国内安卓渠道：arm64 为主，armeabi-v7a 兜底；x86 只给模拟器用
    splits {
        abi {
            isEnable = true
            reset()
            // 模拟器调试时把 "x86_64" 加进来再重编即可
            include("arm64-v8a", "armeabi-v7a")
            // 额外产出一个双 ABI 合集包，方便不确定机型的人直接装
            isUniversalApk = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 端侧感知（bundled，不依赖 GMS）— ADR-011
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    implementation("com.google.mlkit:face-detection:16.1.7")

    // BYOK 直连厂商 — ADR-015
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
