// AGP 9 compiles Kotlin itself and refuses the `org.jetbrains.kotlin.android` plugin.
// It takes the compiler from the kotlin-gradle-plugin jar on this classpath, so this
// is where the Kotlin version is pinned. Keep it in sync with `kotlin` in libs.versions.toml.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

// Lint gives hints and never fails a build. Release builds skip it (it's slow on the phone):
// `sh build.sh lint` checks every module and writes one report each to build/lint/.
subprojects {
    pluginManager.withPlugin("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> { lint { asHints(project) } }
    }
    pluginManager.withPlugin("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            lint {
                asHints(project)
                // Modules' permissions are declared in the app's manifest (theirs are never merged), out of lint's sight.
                disable += "MissingPermission"
            }
        }
    }
}

fun com.android.build.api.dsl.Lint.asHints(project: Project) {
    abortOnError = false
    checkReleaseBuilds = false
    textReport = true
    textOutput = rootProject.file("build/lint/" + project.path.removePrefix(":").replace(':', '-') + ".txt")
}
