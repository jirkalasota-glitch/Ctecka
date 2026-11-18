plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "cz.jirka.ctecka"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.jirka.ctecka"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.2.$buildNumber"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("debug") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    // cteni textu z PDF
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // ovladani na zamcene obrazovce / sluchatkach
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    // vlastni hlas (Pocket TTS) primo v telefonu
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
