// Packs a feature into a module zip, `build/qmod/<name>.qmod`: its module.json beside a
// classes.dex of only its own code. The launcher loads it at run time against its own core
// and libraries, so none of those go in. Applied by every feature's build.gradle.kts:
//
//     apply(from = rootProject.file("gradle/module.gradle.kts"))
//
// The app bundles the zips (see bundleModules in app/build.gradle.kts); `packModule` builds
// one on its own, and `signModule` a signed copy of it, to share or import.

val d8Tool = configurations.create("d8Tool")
// D8, Android's dexer, from the same release line AGP runs for the app (version in libs.versions.toml).
// An applied script can't see the `libs` accessors, so it reads the catalog by name.
dependencies.add(
    d8Tool.name,
    extensions.getByType(VersionCatalogsExtension::class.java).named("libs").findLibrary("r8").get().get(),
)

val qmod = layout.buildDirectory.dir("qmod")
val aar = layout.buildDirectory.file("outputs/aar/${project.name}-release.aar")

/**
 * The Android platform's classes, which D8 compiles against; found where build.sh finds aapt2.
 * Looked up when D8 runs, not while Gradle configures: AGP installs a missing platform only after
 * that (as on F-Droid's build server), and D8 without it makes different dex, so the APK wouldn't
 * match the release one.
 */
fun androidJar(): File {
    val props = rootProject.file("local.properties").takeIf { it.exists() }?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter('=')
    return listOfNotNull(props, System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"), System.getProperty("user.home") + "/android-sdk")
        .map { File(it, "platforms/android-36/android.jar") }
        .firstOrNull { it.exists() }
        ?: throw GradleException("D8 needs the Android 36 platform (platforms/android-36/android.jar) in the SDK")
}

val unpackModuleClasses = tasks.register("unpackModuleClasses", Copy::class.java) {
    dependsOn("bundleReleaseAar")
    from(zipTree(aar)) { include("classes.jar") }
    into(qmod.map { it.dir("jar") })
}

val dexModule = tasks.register("dexModule", JavaExec::class.java) {
    dependsOn(unpackModuleClasses)
    val jar = qmod.map { it.file("jar/classes.jar") }
    val out = qmod.map { it.dir("dex") }
    inputs.file(jar)
    outputs.dir(out)
    classpath = d8Tool
    mainClass.set("com.android.tools.r8.D8")
    doFirst {
        out.get().asFile.deleteRecursively()
        out.get().asFile.mkdirs()
    }
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf("--min-api", "34", "--output", out.get().asFile.path) +
                listOf("--lib", androidJar().path) +
                jar.get().asFile.path
        },
    )
}

tasks.register("packModule", Zip::class.java) {
    from(dexModule)
    from(file("module.json"))
    archiveFileName.set("${project.name}.qmod")
    // Reproducible builds (F-Droid checks them): the same bytes from the same source.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    destinationDirectory.set(qmod)
}

// A signed copy, `build/qmod/signed/<name>.qmod`, for sharing and `module import`: the launcher
// checks who signed a module it imports. The bundled ones aren't signed: the APK's signature
// covers them, and F-Droid's reproducible build has no key to sign them with. Signed with the
// release key in release.properties, by the JDK's jarsigner (the passwords go by environment,
// not on its command line).
tasks.register("signModule", Exec::class.java) {
    dependsOn("packModule")
    val unsigned = qmod.map { it.file("${project.name}.qmod") }
    val signed = qmod.map { it.file("signed/${project.name}.qmod") }
    val props = rootProject.file("release.properties")
    inputs.file(unsigned)
    outputs.file(signed)
    executable = File(System.getProperty("java.home"), "bin/jarsigner").path
    doFirst {
        if (!props.exists()) {
            throw GradleException("signModule needs release.properties (storeFile, storePassword, keyAlias, keyPassword)")
        }
        val key = java.util.Properties().apply { props.inputStream().use { load(it) } }
        signed.get().asFile.parentFile.mkdirs()
        environment("QWERTY_STORE_PASSWORD", key.getProperty("storePassword"))
        environment("QWERTY_KEY_PASSWORD", key.getProperty("keyPassword"))
        args(
            "-keystore", rootProject.file(key.getProperty("storeFile")).path,
            "-storepass:env", "QWERTY_STORE_PASSWORD",
            "-keypass:env", "QWERTY_KEY_PASSWORD",
            "-digestalg", "SHA-256",
            "-signedjar", signed.get().asFile.path,
            unsigned.get().asFile.path,
            key.getProperty("keyAlias"),
        )
    }
}
