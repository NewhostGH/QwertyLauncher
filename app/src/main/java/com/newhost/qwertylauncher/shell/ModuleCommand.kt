package com.newhost.qwertylauncher.shell

import android.os.Handler
import android.os.Looper
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.MenuItem
import com.newhost.qwertylauncher.core.Score
import com.newhost.qwertylauncher.core.SearchResult
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `module`: the installed modules (bundled or imported, and any that
 * failed to load), `module import` to add one from a zip, and
 * `module remove <id>` to take an imported one out. Installing or removing
 * restarts the launcher, which is when modules load. `module crash <id>`
 * (unlisted) crashes the launcher as if that module had, to try the safe mode.
 */
internal class ModuleCommand(private val loader: ModuleLoader) : Command {
    override val name = "module"
    override val aliases = listOf("modules")
    override val usage = "module [import|remove <id>|restart]"
    override val summary = "installed modules; import one from a zip"

    override suspend fun suggest(args: String): List<SearchResult> {
        val words = args.trim().split(' ').filter { it.isNotEmpty() }
        val verb = words.firstOrNull().orEmpty()
        if (verb == "remove" || verb == "rm") return removals(words.getOrNull(1).orEmpty())
        if (verb == "crash") return crashes(words.getOrNull(1).orEmpty())
        val typed = verb.lowercase()
        val import = SearchResult(
            key = "module:import",
            tag = "module",
            title = "module import",
            detail = "add a module from a zip (.qmod)",
            score = Score.EXACT,
            completion = "module import",
            action = { host -> pick(host) },
        )
        val installed = loader.packages
            .filter { typed.isEmpty() || it.manifest.id.startsWith(typed) || it.manifest.name.startsWith(typed) }
            .mapIndexed { i, p -> listing(p, Score.EXACT - 1 - i) }
        val restart = SearchResult(
            key = "module:restart",
            tag = "module",
            title = "module restart",
            detail = "restart the launcher, loading the modules afresh",
            score = Score.EXACT - 1,
            completion = "module restart",
            action = { host -> (host as? ShellHost)?.restart() },
        )
        return listOfNotNull(
            import.takeIf { "import".startsWith(typed) },
            restart.takeIf { typed.isNotEmpty() && "restart".startsWith(typed) },
        ) + installed
    }

    private fun listing(p: ModulePackage, score: Int): SearchResult {
        val m = p.manifest
        val from = when {
            p.replacesBundled -> "imported, over the bundled one"
            p.imported -> "imported"
            else -> "bundled"
        }
        val state = p.problem?.let { if (p.skipped) it else "failed: $it" }
            ?: p.missingPermissions.takeIf { it.isNotEmpty() }?.let { "lacks ${it.joinToString { perm -> perm.substringAfterLast('.') }}" }
        return SearchResult(
            key = "module:${m.id}",
            tag = "module",
            title = "${m.name} ${m.version}",
            detail = listOfNotNull(m.id, from, state).joinToString("  "),
            score = score,
            action = { host -> details(host, p) },
            menu = listOfNotNull(
                MenuItem("let ${m.name} load again and restart") { h -> release(h, m.id) }.takeIf { p.held },
                MenuItem("remove ${m.name} and restart") { h -> remove(h, m.id) }.takeIf { p.imported },
            ),
        )
    }

    private fun removals(typed: String) = loader.packages.filter { it.imported && it.manifest.id.startsWith(typed) }.map { p ->
        SearchResult(
            key = "module:remove:${p.manifest.id}",
            tag = "module",
            title = "module remove ${p.manifest.id}",
            detail = if (p.replacesBundled) "back to the bundled one; restarts" else "restarts the launcher",
            score = Score.EXACT,
            completion = "module remove ${p.manifest.id}",
            action = { host -> remove(host, p.manifest.id) },
        )
    }

    private fun crashes(typed: String) = loader.packages.filter { it.module != null && it.manifest.id.startsWith(typed) }.map { p ->
        SearchResult(
            key = "module:crash:${p.manifest.id}",
            tag = "module",
            title = "module crash ${p.manifest.id}",
            detail = "crash the launcher as if ${p.manifest.name} had; twice holds it back",
            score = Score.EXACT,
            completion = "module crash ${p.manifest.id}",
            // Outside whatever runs the action, so nothing on the way catches it.
            action = { Handler(Looper.getMainLooper()).post { throw TestCrash(p.manifest.entry) } },
        )
    }

    /** A crash that looks as if it came from [entry]'s code, which is how [CrashGuard] blames a module. */
    private class TestCrash(entry: String) : RuntimeException("test crash (module crash), as if from $entry") {
        init {
            stackTrace = arrayOf(StackTraceElement(entry, "testCrash", null, 0))
        }
    }

    /** Prints what the module is and says about itself. */
    private fun details(host: LauncherHost, p: ModulePackage) {
        host.clearQuery()
        val m = p.manifest
        host.console.println("${m.name} ${m.version}  (${m.id}, api ${m.api}${if (p.imported) ", imported" else ""})")
        host.console.println("  ${m.entry}")
        if (m.permissions.isNotEmpty()) host.console.println("  uses ${m.permissions.joinToString { it.substringAfterLast('.') }}")
        if (p.missingPermissions.isNotEmpty()) {
            host.console.println("  the launcher lacks ${p.missingPermissions.joinToString { it.substringAfterLast('.') }}: that part won't work")
        }
        p.problem?.let { host.console.println(if (p.skipped) "  $it" else "  failed to load: $it") }
        if (p.held) host.console.println("  Hold enter on it here to let it load again. The debug log shows its crashes.")
    }

    private fun release(host: LauncherHost, id: String) {
        host.clearQuery()
        if (loader.release(id)) (host as? ShellHost)?.restart()
    }

    private fun pick(host: LauncherHost) {
        host.clearQuery()
        val shell = host as? ShellHost ?: return
        shell.pickFile(ZIP_TYPES) { uri ->
            host.scope.launch {
                val incoming = try {
                    withContext(Dispatchers.IO) { loader.inspect(uri) }
                } catch (e: IOException) {
                    host.console.println("module import: can't use it: ${e.message}")
                    return@launch
                }
                val m = incoming.manifest
                val lacks = incoming.missingPermissions.takeIf { it.isNotEmpty() }
                    ?.let { " It uses ${it.joinToString { perm -> perm.substringAfterLast('.') }}, which the launcher lacks." }
                    .orEmpty()
                val replacing = loader.packages.firstOrNull { it.manifest.id == m.id }?.let { " Replaces ${it.manifest.name} ${it.manifest.version}." }.orEmpty()
                host.console.println("module import: ${m.name} ${m.version} (${m.id}). It runs as the launcher, with all its permissions: only install modules you trust.$replacing$lacks")
                host.showMenu(
                    "install ${m.name}?",
                    listOf(
                        MenuItem("install and restart") { h -> install(h, incoming) },
                        MenuItem("cancel") { h -> h.console.println("module import: cancelled") },
                    ),
                )
            }
        }
    }

    private fun install(host: LauncherHost, incoming: ModuleLoader.Incoming) {
        try {
            loader.install(incoming)
        } catch (e: IOException) {
            host.console.println("module import: ${e.message}")
            return
        }
        (host as? ShellHost)?.restart()
    }

    private fun remove(host: LauncherHost, id: String) {
        host.clearQuery()
        if (!loader.remove(id)) {
            host.console.println("module remove: no imported module '$id'")
            return
        }
        (host as? ShellHost)?.restart()
    }

    private companion object {
        /** Zips come as any of these, depending on where they're picked from. */
        val ZIP_TYPES = arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed", "*/*")
    }
}
