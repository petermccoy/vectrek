plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vectrek.game"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vectrek.game"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Intentionally dependency-free: the game uses only the Android framework
// (SurfaceView/Canvas rendering, NSD + UDP networking, org.json persistence).
