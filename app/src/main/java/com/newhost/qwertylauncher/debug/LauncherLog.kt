package com.newhost.qwertylauncher.debug

import android.content.Context
import android.util.Log
import com.newhost.qwertylauncher.core.DebugLog
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/**
 * The launcher's one debug log. Every module writes to it through a tagged
 * [DebugLog]; so do the shell (install and search failures), uncaught
 * crashes, and input events while that's switched on. Lines are mirrored to
 * logcat and kept in a file, so they survive restarts and crashes.
 */
class LauncherLog(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val writer = Executors.newSingleThreadExecutor()

    private val _lines = MutableStateFlow(load())

    /** Oldest first, at most [MAX_LINES]. */
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    /** A [DebugLog] whose lines are tagged with [tag], e.g. a module id. */
    fun tagged(tag: String): DebugLog = object : DebugLog {
        override fun d(message: String) = write(tag, Log.DEBUG, message, null)
        override fun e(message: String, error: Throwable?) = write(tag, Log.ERROR, message, error)
    }

    fun clear() {
        _lines.value = emptyList()
        writer.execute { persist(emptyList()) }
    }

    /**
     * Records uncaught crashes (with the top of the stack) before the process dies. [onCrash] hears
     * of each first and names the module to blame, if any.
     */
    fun recordCrashes(onCrash: (Throwable) -> String? = { null }) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val module = try {
                onCrash(error)
            } catch (e: Throwable) {
                null
            }
            val where = module?.let { " in module $it" }.orEmpty()
            write(CRASH_TAG, Log.ERROR, "uncaught on ${thread.name}$where", error, sync = true)
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(tag: String, priority: Int, message: String, error: Throwable?, sync: Boolean = false) {
        Log.println(priority, "QwertyLauncher/$tag", message + (error?.let { "\n" + Log.getStackTraceString(it) } ?: ""))
        val time = LocalDateTime.now().format(TIME_FORMAT)
        val level = if (priority >= Log.ERROR) 'E' else 'D'
        val entries = buildList {
            add("$time $level/$tag: $message" + (error?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))
            // One line per frame keeps the file line-based and the pane readable.
            error?.stackTrace?.take(STACK_FRAMES)?.forEach { add("$time $level/$tag:   at $it") }
        }
        val all = _lines.updateAndGet { (it + entries).takeLast(MAX_LINES) }
        // A crash is about to kill the process: write now, not on the background thread.
        if (sync) persist(all) else writer.execute { persist(_lines.value) }
    }

    private fun load(): List<String> =
        try {
            if (file.exists()) file.readLines().filter { it.isNotEmpty() }.takeLast(MAX_LINES) else emptyList()
        } catch (e: IOException) {
            emptyList()
        }

    private fun persist(lines: List<String>) {
        try {
            file.writeText(lines.joinToString("\n"))
        } catch (e: IOException) {
            Log.w("QwertyLauncher/log", "Couldn't save the debug log", e)
        }
    }

    private companion object {
        const val FILE_NAME = "debug.log"
        const val MAX_LINES = 500
        const val STACK_FRAMES = 8
        const val CRASH_TAG = "crash"
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    }
}
