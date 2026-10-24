package com.newhost.qwertylauncher.shell

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
 * restarts the launcher, which is when modules load.
 */
internal class ModuleCommand(private val loader: ModuleLoader) : Command {
    override val name = "module"
    override val aliases = listOf("modules")
    override val usage = "module [import|remove <id>]"
    override val summary = "installed modules; import one from a zip"

    override suspend fun suggest(args: String): List<SearchResult> {
        val words = args.trim().split(' ').filter { it.isNotEmpty() }
        val verb = words.firstOrNull().orEmpty()
        if (verb == "remove" || verb == "rm") return removals(words.getOrNull(1).orEmpty())
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
        return if ("import".startsWith(typed)) listOf(import) + installed else installed
    }

    private fun listing(p: ModulePackage, score: Int): SearchResult {
        val m = p.manifest
        val from = when {
            p.replacesBundled -> "imported, over the bundled one"
            p.imported -> "imported"
            else -> "bundled"
        }
        val state = p.problem?.let { "failed: $it" }
            ?: p.missingPermissions.takeIf { it.isNotEmpty() }?.let { "lacks ${it.joinToString { perm -> perm.substringAfterLast('.') }}" }
        return SearchResult(
            key = "module:${m.id}",
            tag = "module",
            title = "${m.name} ${m.version}",
            detail = listOfNotNull(m.id, from, state).joinToString("  "),
            score = score,
            action = { host -> details(host, p) },
            menu = if (p.imported) listOf(MenuItem("remove ${m.name} and restart") { h -> remove(h, m.id) }) else emptyList(),
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
        p.problem?.let { host.console.println("  failed to load: $it") }
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
