// Packs a feature into a module zip, `build/qmod/<name>.qmod`: its module.json beside a
// classes.dex of only its own code. The launcher loads it at run time against its own core
// and libraries, so none of those go in. Applied by every feature's build.gradle.kts:
//
//     apply(from = rootProject.file("gradle/module.gradle.kts"))
//
// The app bundles the zips (see bundleModules in app/build.gradle.kts); `packModule` builds
// one on its own, to share or import by hand.

val d8Tool = configurations.create("d8Tool")
// D8, Android's dexer, the same one AGP runs for the app.
dependencies.add(d8Tool.name, "com.android.tools:r8:8.5.35")

val qmod = layout.buildDirectory.dir("qmod")
val aar = layout.buildDirectory.file("outputs/aar/${project.name}-release.aar")

/** The Android platform's classes, which D8 compiles against; found where build.sh finds aapt2. */
val androidJar: File? = run {
    val props = rootProject.file("local.properties").takeIf { it.exists() }?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter('=')
    listOfNotNull(props, System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"), System.getProperty("user.home") + "/android-sdk")
        .map { File(it, "platforms/android-36/android.jar") }
        .firstOrNull { it.exists() }
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
    val lib = androidJar
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
                (lib?.let { listOf("--lib", it.path) } ?: emptyList()) +
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
