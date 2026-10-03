import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Glasses side: capture, send to phone, show two lines. Keep it this small:
// no Compose, no AppCompat, no JSON library, no coroutines. 2GB device.
// CameraX is the one exception: hand-built Camera2 sessions hang this HAL (see OneShotCamera).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.baybin.glass"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.baybin.glass"
        minSdk = 28 // the glasses run Android 12 (API 32)
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal device build; sign with the debug key so it installs over adb.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Talks to the phone over its own RFCOMM socket (PhoneLink), so no Rokid SDK here.
    implementation(project(":protocol"))

    // Same CameraX version 镜译 ships on these glasses.
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
}
