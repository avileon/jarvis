import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// On-device wake word detection (openWakeWord models on ONNX Runtime). No network access.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "il.co.vibit.jarvis.wakeword"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { noCompress += "onnx" }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    api("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}
