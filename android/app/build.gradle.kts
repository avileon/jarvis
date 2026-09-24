plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val serverUrl = (project.findProperty("jarvisServerUrl") as String?) ?: "https://jarvis.vibit.co.il/"
val minSdkProp = ((project.findProperty("jarvisMinSdk") as String?) ?: "24").toInt()

android {
    namespace = "il.co.vibit.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "il.co.vibit.jarvis"
        minSdk = minSdkProp
        targetSdk = 35
        versionCode = (project.findProperty("jarvisVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("jarvisVersionName") as String?) ?: "0.1.0"
        buildConfigField("String", "DEFAULT_URL", "\"$serverUrl\"")
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("JARVIS_KEYSTORE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("JARVIS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("JARVIS_KEY_ALIAS") ?: "jarvis"
                keyPassword = System.getenv("JARVIS_KEY_PASSWORD") ?: System.getenv("JARVIS_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile != null) rel else signingConfigs.getByName("debug")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
