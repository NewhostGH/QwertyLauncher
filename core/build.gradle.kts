plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.newhost.qwertylauncher.core"
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
    api(libs.kotlinx.coroutines.android)
    api(libs.androidx.lifecycle.runtime.ktx)
    // What the launcher offers modules besides core: PathParser for icons, FileProvider and the rest of androidx.core.
    api(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}
