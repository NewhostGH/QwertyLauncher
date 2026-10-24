package com.newhost.qwertylauncher.shell

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import com.newhost.qwertylauncher.core.DebugLog
import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.ModuleApi
import dalvik.system.DexClassLoader
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import org.json.JSONException
import org.json.JSONObject

/** What a module zip says about itself, in its `module.json`. */
class ModuleManifest(
    val id: String,
    val name: String,
    val version: Int,
    /** The [ModuleApi.VERSION] it was built against. */
    val api: Int,
    /** Its [LauncherModule] class, made with no arguments. */
    val entry: String,
    /** The permissions it uses, which the launcher must declare. */
    val permissions: List<String>,
) {
    companion object {
        const val FILE = "module.json"
        private val ID = Regex("[a-z0-9_]{1,40}")

        /** Reads the manifest in [zip]; throws [IOException] saying what's wrong. */
        fun read(zip: File): ModuleManifest {
            val text = try {
                ZipFile(zip).use { z ->
                    val entry = z.getEntry(FILE) ?: throw IOException("no $FILE in it")
                    if (z.getEntry("classes.dex") == null) throw IOException("no classes.dex in it")
                    z.getInputStream(entry).bufferedReader().readText()
                }
            } catch (e: java.util.zip.ZipException) {
                throw IOException("not a zip", e)
            }
            return try {
                val json = JSONObject(text)
                val permissions = json.optJSONArray("permissions")
                ModuleManifest(
                    id = json.getString("id"),
                    name = json.optString("name").ifEmpty { json.getString("id") },
                    version = json.optInt("version", 1),
                    api = json.getInt("api"),
                    entry = json.getString("entry"),
                    permissions = List(permissions?.length() ?: 0) { permissions!!.getString(it) },
                ).also { if (!ID.matches(it.id)) throw IOException("bad id '${it.id}': a-z, 0-9 and _ only") }
            } catch (e: JSONException) {
                throw IOException("$FILE: ${e.message}", e)
            }
        }
    }
}

/** A module installed on the phone, and how loading it went: [module] when it loaded, else [problem]. */
class ModulePackage(
    val manifest: ModuleManifest,
    val file: File,
    /** Imported by the user, rather than bundled with the launcher. */
    val imported: Boolean,
    /** The bundled one this imported one stands in for, if any. */
    val replacesBundled: Boolean,
    val module: LauncherModule?,
    val problem: String?,
    /** Permissions it uses that the launcher doesn't declare: what needs them won't work. */
    val missingPermissions: List<String>,
    /** Held back by [CrashGuard] after crashing the launcher: the user can let it load again. */
    val held: Boolean = false,
    /** Not loaded on purpose (held back, or safe mode), rather than failing to: [problem] says why. */
    val skipped: Boolean = false,
)

/**
 * Finds, loads and installs modules. Every module is a zip ([ModuleManifest]
 * and `classes.dex`) loaded with a class loader of its own over the
 * launcher's, so it shares core and the libraries with the shell and every
 * other module. The bundled ones ship in the APK's assets (`modules/`,
 * listed in load order by `modules/index`) and are copied out when the APK
 * is new or updated; imported ones are kept apart, and one with a bundled
 * one's id takes its place. Loading happens once, at process start: a
 * change takes a restart.
 */
class ModuleLoader(private val context: Context, private val log: DebugLog, private val guard: CrashGuard) {
    private val root = File(context.filesDir, "modules")
    private val bundledDir = File(root, "bundled")
    private val importedDir = File(root, "imported")

    /** What [load] found, bundled first in their order, then the imported ones. */
    var packages: List<ModulePackage> = emptyList()
        private set

    /** What [CrashGuard] said this start, for home to tell the user once; null when all went normally. */
    private var safeNotice: String? = null

    /** The safe mode notice, the first time it's asked for. */
    fun takeSafeNotice(): String? = safeNotice.also { safeNotice = null }

    /** Each opened module's code package to its id, for blaming a crash; filled in as modules open. */
    private val codePackages = ConcurrentHashMap<String, String>()

    /** The module to blame for [error], if its code was running; see [CrashGuard.blame]. */
    fun blame(error: Throwable): String? = CrashGuard.blame(error, codePackages, context.packageName)

    /** Loads every installed module; one that can't load is kept in [packages] with its problem, and skipped. */
    fun load(): List<LauncherModule> {
        val bundled = try {
            unpackBundled()
        } catch (e: IOException) {
            log.e("couldn't unpack the bundled modules", e)
            emptyList()
        }
        val imported = importedDir.listFiles { f -> f.name.endsWith(EXTENSION) }.orEmpty().sortedBy { it.name }
        val importedIds = imported.associateBy { it.name.removeSuffix(EXTENSION) }
        val bundledIds = bundled.map { it.name.removeSuffix(EXTENSION) }.toSet()
        // An imported module takes its bundled namesake's place; the rest come after, by name.
        val files = bundled.map { b -> importedIds[b.name.removeSuffix(EXTENSION)]?.let { it to true } ?: (b to false) } +
            imported.filter { it.name.removeSuffix(EXTENSION) !in bundledIds }.map { it to true }
        val declared = declaredPermissions()
        val (mode, newlyHeld) = guard.start()
        val held = guard.held
        packages = files.mapNotNull { (file, isImported) ->
            val replaces = isImported && file.name.removeSuffix(EXTENSION) in bundledIds
            val skip = when {
                mode == CrashGuard.Mode.NO_MODULES -> "off for this start: the launcher kept crashing"
                mode == CrashGuard.Mode.NO_IMPORTED && isImported -> "off for this start: the launcher kept crashing"
                else -> null
            }
            open(file, isImported, replaces, declared, skip, held)
        }
        safeNotice = when {
            newlyHeld.isNotEmpty() ->
                "Safe mode: ${newlyHeld.joinToString(" and ")} kept crashing the launcher, so it is held back. " +
                    "Type module and hold enter on it to let it load again."
            mode == CrashGuard.Mode.NO_MODULES ->
                "Safe mode: the launcher kept crashing, so this start loads no modules. Type module restart to try again."
            mode == CrashGuard.Mode.NO_IMPORTED ->
                "Safe mode: the launcher kept crashing, so this start leaves out imported modules. Type module restart to try again."
            else -> null
        }
        safeNotice?.let { log.d(it) }
        return packages.mapNotNull { it.module }
    }

    /** Lets a module held back after crashing load again; takes a restart. */
    fun release(id: String): Boolean = guard.release(id).also { if (it) log.d("let $id load again") }

    private fun open(
        file: File,
        imported: Boolean,
        replaces: Boolean,
        declared: Set<String>,
        skip: String?,
        held: Set<String>,
    ): ModulePackage? {
        val manifest = try {
            ModuleManifest.read(file)
        } catch (e: IOException) {
            log.e("module ${file.name} is broken; skipping it", e)
            return null
        }
        val missing = manifest.permissions.filter { it !in declared }
        val isHeld = manifest.id in held
        fun result(module: LauncherModule?, problem: String?, skipped: Boolean = false) =
            ModulePackage(manifest, file, imported, replaces, module, problem, missing, isHeld, skipped)
        if (isHeld) return result(null, "held back: it kept crashing the launcher", skipped = true)
        if (skip != null) return result(null, skip, skipped = true)
        if (manifest.api > ModuleApi.VERSION) {
            return result(null, "needs a newer launcher (module api ${manifest.api}, this is ${ModuleApi.VERSION})")
        }
        return try {
            // Android refuses to load code from a file the app could still change.
            file.setReadOnly()
            codePackages[manifest.entry.substringBeforeLast('.', "")] = manifest.id
            val loader = DexClassLoader(file.path, null, null, ModuleLoader::class.java.classLoader)
            val module = loader.loadClass(manifest.entry).getDeclaredConstructor().newInstance() as LauncherModule
            if (module.id != manifest.id) log.d("module ${manifest.id}: its class says id '${module.id}'")
            if (missing.isNotEmpty()) log.d("module ${manifest.id} uses ${missing.joinToString()}, which the launcher doesn't declare")
            result(module, null)
        } catch (e: ReflectiveOperationException) {
            log.e("module ${manifest.id} failed to load", e)
            result(null, "can't start: ${e.javaClass.simpleName}")
        } catch (e: LinkageError) {
            log.e("module ${manifest.id} failed to load", e)
            result(null, "built for another launcher: ${e.message}")
        } catch (e: ClassCastException) {
            result(null, "${manifest.entry} isn't a LauncherModule")
        } catch (e: RuntimeException) {
            log.e("module ${manifest.id} failed to load", e)
            result(null, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Copies the bundled modules out of the APK when it was installed or
     * updated since the last time, and returns them in load order.
     */
    private fun unpackBundled(): List<File> {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val stamp = "${info.longVersionCode}:${info.lastUpdateTime}"
        val stampFile = File(bundledDir, STAMP)
        val order = context.assets.open("$ASSETS/index").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        if (!stampFile.exists() || stampFile.readText() != stamp) {
            bundledDir.deleteRecursively()
            bundledDir.mkdirs()
            for (id in order) {
                val target = File(bundledDir, id + EXTENSION)
                context.assets.open("$ASSETS/$id$EXTENSION").use { input -> target.outputStream().use { input.copyTo(it) } }
                target.setReadOnly()
            }
            stampFile.writeText(stamp)
            log.d("unpacked ${order.size} bundled modules")
        }
        return order.map { File(bundledDir, it + EXTENSION) }.filter { it.exists() }
    }

    private fun declaredPermissions(): Set<String> =
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()

    /** A zip picked to import, checked but not installed yet. */
    class Incoming(val manifest: ModuleManifest, internal val file: File, val missingPermissions: List<String>)

    /** Copies the zip at [uri] in and checks it; throws [IOException] saying what's wrong with it. Not on the main thread. */
    fun inspect(uri: Uri): Incoming {
        root.mkdirs()
        val file = File(root, INCOMING)
        file.delete()
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("can't open it")
        input.use { i -> file.outputStream().use { i.copyTo(it) } }
        val manifest = try {
            ModuleManifest.read(file)
        } catch (e: IOException) {
            file.delete()
            throw e
        }
        if (manifest.api > ModuleApi.VERSION) {
            file.delete()
            throw IOException("it needs a newer launcher (module api ${manifest.api}, this is ${ModuleApi.VERSION})")
        }
        return Incoming(manifest, file, manifest.permissions.filter { it !in declaredPermissions() })
    }

    /** Installs [incoming], replacing an imported one with its id. Takes a restart to load. */
    fun install(incoming: Incoming) {
        importedDir.mkdirs()
        val target = File(importedDir, incoming.manifest.id + EXTENSION)
        target.delete()
        if (!incoming.file.renameTo(target)) throw IOException("couldn't keep it")
        target.setReadOnly()
        log.d("imported module ${incoming.manifest.id} ${incoming.manifest.version}")
    }

    /** Removes the imported module [id]; a bundled one it stood in for comes back. Takes a restart. */
    fun remove(id: String): Boolean = File(importedDir, id + EXTENSION).delete().also { if (it) log.d("removed module $id") }

    private companion object {
        const val ASSETS = "modules"
        const val EXTENSION = ".qmod"
        const val STAMP = ".stamp"
        const val INCOMING = "incoming.qmod"
    }
}
