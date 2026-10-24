package com.newhost.qwertylauncher.shell

import java.io.File
import java.io.IOException

/**
 * Keeps a crashing module from locking the user out of home: the system
 * restarts home after every crash, straight into the same crash.
 *
 * Every crash is noted with the module whose code was running (worked out
 * from the stack, see [blame]). A module that crashes the launcher twice
 * within [WINDOW_MS] is held back: it isn't loaded until the user lets it
 * load again with the `module` command. Crashes no module can be blamed for
 * step down instead: after two close together the next start loads no
 * imported modules, after three none at all. Those last only for that start.
 */
class CrashGuard(dir: File, private val now: () -> Long = System::currentTimeMillis) {

    /** How much of the launcher a start loads. */
    enum class Mode { ALL, NO_IMPORTED, NO_MODULES }

    private val crashFile = File(dir, "crashes")
    private val heldFile = File(dir, "held")

    init {
        dir.mkdirs()
    }

    /** Modules held back after crashing, by id. */
    val held: Set<String> get() = readLines(heldFile).toSet()

    /** Notes a crash; [moduleId] is the module to blame, if any. Called as the process dies, so it writes at once. */
    fun record(moduleId: String?) {
        try {
            crashFile.appendText("${now()} ${moduleId ?: NOBODY}\n")
        } catch (e: IOException) {
            // Nothing to do while crashing; the next start just won't know.
        }
    }

    /**
     * Decides how much this start loads, from the crashes just before it, and
     * holds back any module that crashed twice. Call once, before loading modules.
     * Returns the mode and the modules held back just now.
     */
    fun start(): Pair<Mode, List<String>> {
        val since = now() - WINDOW_MS
        val recent = readLines(crashFile).mapNotNull { line ->
            val time = line.substringBefore(' ').toLongOrNull() ?: return@mapNotNull null
            (time to line.substringAfter(' ')).takeIf { time >= since }
        }
        val newlyHeld = recent.filter { it.second != NOBODY }.groupingBy { it.second }.eachCount()
            .filter { it.value >= 2 }.keys.filter { it !in held }
        if (newlyHeld.isNotEmpty()) writeLines(heldFile, (held + newlyHeld).toList())
        // Crashes of a module now held back are dealt with; the rest still count.
        val left = recent.filter { it.second !in newlyHeld }
        writeLines(crashFile, left.map { "${it.first} ${it.second}" })
        // A module was just held back: try everything else before stepping down.
        val unblamed = if (newlyHeld.isNotEmpty()) 0 else left.size
        val mode = when {
            unblamed >= 3 -> Mode.NO_MODULES
            unblamed >= 2 -> Mode.NO_IMPORTED
            else -> Mode.ALL
        }
        return mode to newlyHeld
    }

    /** Lets a held-back module load again (from the next start). */
    fun release(id: String): Boolean {
        val current = held
        if (id !in current) return false
        writeLines(heldFile, (current - id).toList())
        return true
    }

    private fun readLines(file: File): List<String> =
        try {
            if (file.exists()) file.readLines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
        } catch (e: IOException) {
            emptyList()
        }

    private fun writeLines(file: File, lines: List<String>) {
        try {
            file.writeText(lines.joinToString("") { "$it\n" })
        } catch (e: IOException) {
            // Kept as it was; the guard is best effort.
        }
    }

    companion object {
        const val WINDOW_MS = 2 * 60 * 1000L
        private const val NOBODY = "-"

        /**
         * The module whose code was running when [error] was thrown: the one
         * owning the innermost frame, looking through the causes too. [packages]
         * maps each module's code package (its entry class's package) to its id.
         * A package that holds the launcher's own code ([ownPackage]) blames nobody.
         */
        fun blame(error: Throwable, packages: Map<String, String>, ownPackage: String): String? {
            val usable = packages.filterKeys { p -> p.isNotEmpty() && ownPackage != p && !ownPackage.startsWith("$p.") }
            var e: Throwable? = error
            val seen = mutableSetOf<Throwable>()
            while (e != null && seen.add(e)) {
                for (frame in e.stackTrace) {
                    // The most specific package wins, should one module's sit inside another's.
                    usable.entries.filter { frame.className.startsWith("${it.key}.") }
                        .maxByOrNull { it.key.length }?.let { return it.value }
                }
                e = e.cause
            }
            return null
        }
    }
}
