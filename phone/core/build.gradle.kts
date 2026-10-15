import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: Hebrew NLU, contact matching, dialog logic. No Android dependencies → fast unit tests.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
