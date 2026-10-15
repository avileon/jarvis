import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "il.co.vibit.jarvis.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "il.co.vibit.jarvis.assistant"
        // Android 12: on-device SpeechRecognizer API, mic FGS rules. The target device runs Android 16.
        minSdk = 31
        targetSdk = 36
        versionCode = (project.findProperty("jarvisVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("jarvisVersionName") as String?) ?: "1.0.0"
    }

    signingConfigs {
        create("release") {
            // Sideload key kept in this private repo so every CI build can update the installed app.
            val ks = System.getenv("JARVIS_ASSISTANT_KEYSTORE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("JARVIS_ASSISTANT_KEYSTORE_PASSWORD")
                keyAlias = "jarvis"
                keyPassword = System.getenv("JARVIS_ASSISTANT_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // x86_64 only for the CI emulator smoke test.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            // Galaxy S23 Ultra (and every current phone) is arm64.
            ndk { abiFilters += listOf("arm64-v8a") }
            isMinifyEnabled = false
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile != null) rel else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        textReport = true
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation(project(":wakeword"))

    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-service:2.9.1")
    implementation("androidx.savedstate:savedstate-ktx:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Android Auto: only used to *detect* a projection connection (no car app / no car microphone access).
    implementation("androidx.car.app:app:1.4.0")

    testImplementation("junit:junit:4.13.2")
}
