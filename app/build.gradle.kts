plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aashish.s25cameraprobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aashish.s25cameraprobe"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"
        testInstrumentationRunner = "com.aashish.s25cameraprobe.CameraSmoke"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
