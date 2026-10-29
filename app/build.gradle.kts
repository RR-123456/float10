plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pragon.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pragon.mobile"
        minSdk = 31
        targetSdk = 34
        versionCode = 40
        versionName = "4.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // v10: on-device function-calling model (FunctionGemma 270M) through Google's LiteRT-LM runtime. 100% local inference.
    // Docs say "latest.release"; once the first build works, replace it with the exact version Gradle resolved.
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")
}
