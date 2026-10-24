plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.newhost.qwertylauncher.feature.files"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // FileProvider comes with core (androidx.core); the provider itself is declared in the app manifest.
    implementation(project(":core"))
}

// Built into a module zip the launcher loads at run time.
apply(from = rootProject.file("gradle/module.gradle.kts"))
