plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.reeltranscribe"
    // Android 17 SDK (API 37, minor release 2). Newer Compose libraries need at least 37.1.
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.reeltranscribe"
        minSdk = 29          // Android 10: first version with AudioPlaybackCapture
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
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

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Compose UI (versions come from the "bill of materials").
    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Lifecycle helpers so Compose can run inside the floating overlay windows.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.1")
    implementation("androidx.savedstate:savedstate-ktx:1.5.0")
    implementation("androidx.core:core-ktx:1.19.1")

    // Encrypted storage for the API key.
    implementation("androidx.security:security-crypto:1.1.0")

    // HTTP client for the OpenAI upload, and coroutines for background work.
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Unit tests (run on your computer, no phone needed).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
