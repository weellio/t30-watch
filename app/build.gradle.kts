plugins {
    alias(libs.plugins.android.application)
    // No org.jetbrains.kotlin.android here: AGP 9 compiles Kotlin itself.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "com.t30watch"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.t30watch"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"
    }

    buildTypes {
        release {
            // Personal app, sideloaded. Keep R8 off until we've written keep rules for the
            // Veepoo/JieLi SDKs (they rely on reflection + JNI by class name).
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "DEMO", "false")
        }
        debug {
            buildConfigField("boolean", "DEMO", "false")
        }
        // Screenshot / try-it build: installs beside the real app (own database), fills itself with made-up data
        // and never talks to a watch. Build: gradlew :app:installDemo
        create("demo") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".demo"
            matchingFallbacks += "debug"
            buildConfigField("boolean", "DEMO", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Native libs from the Veepoo SDK (ECG/HRV processing + Opus) are in src/main/jniLibs,
    // which AGP packages automatically. The JieLi/Bluetrum AARs bring their own .so files.
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // ---- Veepoo SDK (local AARs copied from vendor/Android_Ble_SDK/android_sdk_source/jar_core) ----
    implementation(files("libs/vpprotocol-2.3.95.15.aar"))   // protocol + VPOperateManager
    implementation(files("libs/vpbluetooth-1.20.aar"))       // BLE transport (com.inuker.bluetooth.library)
    // JieLi chip support. vpbluetooth references these classes directly, so they are not optional.
    implementation(files("libs/JL_Watch_V1.13.1_11214-release.aar"))
    implementation(files("libs/jl_rcsp_V0.7.2_527-release.aar"))
    implementation(files("libs/jl_bt_ota_V1.10.0_10931-release.aar"))
    implementation(files("libs/BmpConvert_V1.6.0_10604-release.aar"))
    // Bluetrum chip tool. README marks it required; nothing in vpprotocol/vpbluetooth references it.
    implementation(files("libs/abpartool-release.aar"))
    // Goodix OTA libs (libble, libcomx, libdfu, libfastdfu) deliberately left out — see docs/sdk-notes.md.

    // ---- SDK's Maven dependencies ----
    implementation(libs.gson)
    implementation(libs.nordic.mcumgr.core)
    implementation(libs.nordic.mcumgr.ble)
    implementation(libs.nordic.scanner)
    implementation(libs.androidx.localbroadcastmanager)

    // ---- App stack ----
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.health.connect)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Plain-JVM unit tests for the maths (insights, game rules). Run: gradlew :app:testDebugUnitTest
    testImplementation(libs.junit)
}
