
import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
}

val keystorePropsFile = rootProject.file("release.properties")
val keystoreProps = Properties()

if (keystorePropsFile.exists()) {
    keystoreProps.load(FileInputStream(keystorePropsFile))
}

val hasValidSigningProps = keystorePropsFile.exists().also { exists ->
    if (exists) {
        FileInputStream(keystorePropsFile).use { keystoreProps.load(it) }
    }
}.let {
    listOf("storeFile", "storePassword", 
            "keyAlias", "keyPassword").all { key ->
        keystoreProps[key] != null
    }
}


android {
    namespace = "com.newhost.qwertylauncher"
    compileSdk = 36 

    signingConfigs {
        if (hasValidSigningProps) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "com.newhost.qwertylauncher"
        minSdk = 34 
        targetSdk = 36  
        versionCode = 1
        versionName = "1.0"
        
        vectorDrawables { 
            useSupportLibrary = true
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17 
        targetCompatibility = JavaVersion.VERSION_17 
    }

    buildTypes {
        // Debug builds use the release key too, so an APK from the IDE, the terminal or a release
        // build can always be installed over another: Android rejects an update signed differently.
        debug {
            if (hasValidSigningProps) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            if (hasValidSigningProps) {
                signingConfig = signingConfigs.getByName("release")
            }
            // No shrinking: modules load at run time and may use any of core and the
            // libraries, which R8 would strip or rename as unused by the app itself.
            isMinifyEnabled = false
        }
    }

    // F-Droid: no Google-encrypted dependency list in the APK's signing block.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        viewBinding = true
        
    }
    packaging {
        resources {
            resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
            resources.excludes.add("META-INF/kotlinx_coroutines_core.version")

            // The part below is only needed for compose builds.
            // This packaging block is required to solve interdependency conflicts.
            // They arise only when using local maven repo, so I suppose online repos have some way of solving such issues.

            // Caused by: com.android.builder.merge.DuplicateRelativeFileException: 4 files found with path 'commonMain/default/linkdata/module' from inputs:
            // - AndroidIDE\libs_source\gradle\localMvnRepository\androidx\collection\collection\1.4.2\collection-1.4.2.jar
            // - AndroidIDE\libs_source\gradle\localMvnRepository\androidx\lifecycle\lifecycle-common\2.8.7\lifecycle-common-2.8.7.jar
            // - AndroidIDE\libs_source\gradle\localMvnRepository\androidx\annotation\annotation\1.8.1\annotation-1.8.1.jar
            // - AndroidIDE\libs_source\gradle\localMvnRepository\org\jetbrains\kotlinx\kotlinx-coroutines-core\1.7.3\kotlinx-coroutines-core-1.7.3.jar
            // And some others.
            resources.pickFirsts.add("nonJvmMain/default/linkdata/package_androidx/0_androidx.knm")
            resources.pickFirsts.add("nonJvmMain/default/linkdata/root_package/0_.knm")
            resources.pickFirsts.add("nonJvmMain/default/linkdata/module")

            resources.pickFirsts.add("nativeMain/default/linkdata/root_package/0_.knm")
            resources.pickFirsts.add("nativeMain/default/linkdata/module")

            resources.pickFirsts.add("commonMain/default/linkdata/root_package/0_.knm")
            resources.pickFirsts.add("commonMain/default/linkdata/module")
            resources.pickFirsts.add("commonMain/default/linkdata/package_androidx/0_androidx.knm")

            resources.pickFirsts.add("META-INF/kotlin-project-structure-metadata.json")

            resources.merges.add("commonMain/default/manifest")
            resources.merges.add("nonJvmMain/default/manifest")
            resources.merges.add("nativeMain/default/manifest")
        }
    }
}

// Features are modules loaded at run time, not linked in: each is packed into a zip
// (gradle/module.gradle.kts) and bundled under assets/modules, which the shell's
// ModuleLoader installs. Listed in load order; a module left out isn't bundled.
val bundledModules = listOf(
    "apps", "toggles", "favorites", "suggest", "asciiicons", "clock", "battery", "music",
    "notifications", "system", "network", "alarm", "todo", "calendar", "news", "weather",
    "sky", "herald", "sensors", "files", "games", "contacts", "commands", "ai",
)

/** Gathers the modules' zips into an assets folder, with `index` listing their load order. */
abstract class BundleModules : DefaultTask() {
    @get:InputFiles
    abstract val packs: ConfigurableFileCollection

    @get:Input
    abstract val order: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val out = outputDir.get().asFile.resolve("modules")
        out.deleteRecursively()
        out.mkdirs()
        packs.files.forEach { it.copyTo(out.resolve(it.name)) }
        out.resolve("index").writeText(order.get().joinToString("\n", postfix = "\n"))
    }
}

val bundleModules = tasks.register<BundleModules>("bundleModules") {
    order.set(bundledModules)
    for (id in bundledModules) {
        dependsOn(":feature:$id:packModule")
        packs.from(rootProject.file("feature/$id/build/qmod/$id.qmod"))
    }
    outputDir.set(layout.buildDirectory.dir("generated/moduleAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleModules, BundleModules::outputDir)
    }
}

// Reproducible builds: the baseline ART profile's bytes vary between builds of the same source.
tasks.configureEach {
    if (name.contains("ArtProfile")) enabled = false
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}


kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17"))
    }
}
 

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.startup.runtime)
    implementation(libs.androidx.interpolator)
    implementation(libs.android.material)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    testImplementation(libs.junit)
}
