plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ashish.atvlauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ashish.atvlauncher"
        minSdk = 28 // ImageDecoder (EXIF rotation, HEIC) for the USB photo viewer
        targetSdk = 30 // Acer ATV R2 runs Android TV 11 (API 30)
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        // Release is minified + shrunk = smallest, fastest APK.
        // Signed with the debug key so you can sideload without a keystore.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // Sideloaded only (never on Play), so Play's target-SDK rule doesn't apply.
    lint { disable += "ExpiredTargetSdkVersion" }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(bom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    // USB media playback (mp4/mkv/mp3...). Only loaded when the USB screen is opened.
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
}
