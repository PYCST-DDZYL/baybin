import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Each person fills their own key into local.properties (gitignored). Nothing here is a key.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
    // Drop the key before any build field can see it. PC scripts read the file themselves.
    remove("qwen.apiKey")
}
fun local(key: String, default: String = "") =
    localProps.getProperty(key, default).trim().replace("\\", "\\\\").replace("\"", "\\\"")

// The rules the app uses are the repo's rules/*.json + prompt.txt (the files
// tools/verify_rules.py checks against the official pages), copied into the assets.
val rulesAssets = layout.buildDirectory.dir("generated/rulesAssets").get().asFile
val copyRules by tasks.registering(Sync::class) {
    from(rootProject.file("rules")) { include("*.json", "prompt.txt") }
    into(File(rulesAssets, "rules"))
}
tasks.named("preBuild") { dependsOn(copyRules) }

android {
    namespace = "com.baybin.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.baybin.phone"
        minSdk = 29 // CXR-M needs 28; LinkService uses the connectedDevice service type (29)
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"

        // The Qwen key is never a build field. It is typed into the phone and kept in
        // app-private storage, so a decompile of this APK cannot recover it.
        buildConfigField("String", "QWEN_BASE_URL",
            "\"${local("qwen.baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1")}\"")
        buildConfigField("String", "QWEN_MODEL", "\"${local("qwen.model", "qwen3-vl-flash")}\"")
    }

    buildFeatures { buildConfig = true }

    sourceSets["main"].assets.srcDir(rulesAssets)

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation(project(":protocol"))
    // Only used to read the glasses' Bluetooth address on first setup (GlassesLink.pair).
    implementation("com.rokid.cxr:client-m:1.2.2")
    // Same OkHttp the Rokid SDK already pulls in; used for the Qwen call.
    implementation("com.squareup.okhttp3:okhttp:4.9.3")
}
