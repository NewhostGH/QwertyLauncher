package com.newhost.qwertylauncher.shell

import android.content.Context
import com.newhost.qwertylauncher.core.ChoiceSetting
import com.newhost.qwertylauncher.core.ChoiceSetting.Choice
import com.newhost.qwertylauncher.core.NumberSetting
import com.newhost.qwertylauncher.core.ToggleSetting

/** The terminal itself: the "general" section at the top of config. */
internal object ShellSettings {
    val font = ChoiceSetting(
        "font",
        "terminal font",
        listOf(
            Choice(FONT_JETBRAINS, "JetBrains Mono"),
            Choice(FONT_DEJAVU, "DejaVu Sans Mono"),
            Choice(FONT_PLEX, "IBM Plex Mono"),
            Choice(FONT_SYSTEM, "system monospace"),
        ),
        default = FONT_JETBRAINS,
        summary = "JetBrains Mono and DejaVu have every box and block character at exactly one cell. " +
            "Plex and the system font borrow a few from other fonts, which can put borders out of line.",
    )
    val fontSize = NumberSetting(
        "font_size",
        "terminal text size",
        default = 12,
        min = 9,
        max = 20,
        unit = "sp",
        summary = "Size of all text except ASCII art.",
    )
    val prompt = ChoiceSetting(
        "prompt",
        "prompt style",
        listOf(Choice("full", "user@host:~$"), Choice("path", "~$"), Choice("bare", "$")),
        default = "full",
    )
    val buttonSize = NumberSetting(
        "top_button_lines",
        "top bar button size",
        default = 2,
        min = 1,
        max = 6,
        unit = "lines",
        summary = "How tall the help and config buttons at the top are, in text lines.",
    )
    val cursorBlink = ToggleSetting("cursor_blink", "blinking prompt cursor", default = true)
    val tileFrame = ChoiceSetting(
        "tile_frame",
        "tile frame style",
        listOf(Choice(FRAME_COMPACT, "title line"), Choice(FRAME_FULL, "full box"), Choice(FRAME_NONE, "none")),
        default = FRAME_COMPACT,
        summary = "A title line above each tile takes the least room, like tmux pane borders. A full box frames it all around.",
    )
    val frameLines = ChoiceSetting(
        "frame_lines",
        "frame line style",
        listOf(
            Choice(LINES_SINGLE, "single"),
            Choice(LINES_DOUBLE, "double"),
            Choice(LINES_HEAVY, "heavy"),
            Choice(LINES_ROUNDED, "rounded"),
            Choice(LINES_ASCII, "plain ascii"),
        ),
        default = LINES_SINGLE,
        summary = "The characters frames are drawn with, on tiles, panes and buttons.",
    )
    val frameColor = ChoiceSetting(
        "frame_color",
        "tile and pane frame colour",
        listOf(Choice(COLOR_DIM, "dim"), Choice(COLOR_BRIGHT, "bright"), Choice(COLOR_ACCENT, "accent")),
        default = COLOR_DIM,
        summary = "Frames that aren't selected. The selected one stands out in another colour.",
    )
    val paneFrames = ChoiceSetting(
        "pane_frames",
        "pane frame style",
        listOf(Choice(FRAME_COMPACT, "title line"), Choice(FRAME_FULL, "full box"), Choice(FRAME_NONE, "none")),
        default = FRAME_COMPACT,
        summary = "Around config, help and the panes modules open, their title in the frame.",
    )
    val buttonFrames = ToggleSetting(
        "button_frames",
        "frames around top bar buttons",
        default = false,
        summary = "A box around the help and config buttons and the toggles at the top.",
    )
    val bootBanner = ToggleSetting(
        "boot_banner",
        "boot banner at start",
        default = true,
        summary = "Prints a login banner when the launcher starts.",
    )
    val statusBar = ToggleSetting(
        "android_status_bar",
        "show android's status bar",
        default = false,
        summary = "Shows the phone's own status bar above the terminal.",
    )

    val unsignedModules = ToggleSetting(
        "unsigned_modules",
        "allow unsigned modules",
        default = false,
        summary = "Lets module import install a module nobody signed. Nothing then shows who made it or that it " +
            "wasn't changed, and it runs with all the launcher's permissions. Only for modules you built yourself.",
    )

    // In config, sections of their own (they're all stored as general's).
    val textAndPrompt = listOf(font, fontSize, prompt, cursorBlink)
    val frames = listOf(tileFrame, frameLines, frameColor, paneFrames, buttonFrames)
    val barAndStart = listOf(buttonSize, statusBar, bootBanner)
    val modules = listOf(unsignedModules)

    val all = (textAndPrompt + frames + barAndStart + modules).toTypedArray()

    /** Settings that change how home is built, so it's rebuilt when they change. */
    val layoutAffecting = listOf(font, fontSize, buttonSize, tileFrame, frameLines, frameColor, paneFrames, buttonFrames)

    const val FONT_JETBRAINS = "jetbrains"
    const val FONT_DEJAVU = "dejavu"
    const val FONT_PLEX = "plex"
    const val FONT_SYSTEM = "system"

    const val LINES_SINGLE = "single"
    const val LINES_DOUBLE = "double"
    const val LINES_HEAVY = "heavy"
    const val LINES_ROUNDED = "rounded"
    const val LINES_ASCII = "ascii"

    const val COLOR_DIM = "dim"
    const val COLOR_BRIGHT = "bright"
    const val COLOR_ACCENT = "accent"

    const val FRAME_COMPACT = "compact"
    const val FRAME_FULL = "full"
    const val FRAME_NONE = "none"
}

/**
 * The manual override for one tile: its size in terminal cells, like tmux's
 * resize-pane. 0 means automatic. Set by holding enter on the tile, then
 * resize; where it goes is set the same way (see [TileOrder]). A tile shows
 * while its module is on.
 */
internal class TileSettings(id: String, title: String) {
    /** Whether the tile is on home; hiding it leaves its module on (its commands, search, other tiles). */
    val shown = ToggleSetting(
        "${id}_shown",
        "show the $title tile on home",
        default = true,
        summary = "Off hides just this tile; the module stays on, with its commands and search. Its switch above turns all of it off.",
    )
    val width = NumberSetting(
        "${id}_width",
        "$title tile width",
        default = AUTO,
        min = AUTO,
        max = MAX_COLUMNS,
        unit = "columns",
        summary = "0 is automatic: the whole row for wide tiles, half of it for the rest. Anything wider than the screen fills it.",
    )
    val height = NumberSetting(
        "${id}_height",
        "$title tile height",
        default = AUTO,
        min = AUTO,
        max = MAX_LINES,
        unit = "lines",
        summary = "0 is automatic: as tall as its content. Tiles side by side share the tallest height.",
    )
    val all = listOf(shown, width, height)

    companion object {
        const val AUTO = 0
        const val MAX_COLUMNS = 300
        const val MAX_LINES = 200
    }
}

/**
 * The order of the tiles on home, as arranged by the user (hold enter on a
 * tile, then move it). Tiles not yet arranged follow their widget's order.
 */
internal class TileOrder(context: Context) {
    private val prefs = context.getSharedPreferences("tiles", Context.MODE_PRIVATE)
    private var ids: List<String> = prefs.getString(KEY, null)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()

    /** Arranged tiles first, in their saved order; the rest after, by their own default order. */
    fun <T> sort(items: List<T>, id: (T) -> String, order: (T) -> Int): List<T> =
        items.sortedWith(compareBy<T>({ ids.indexOf(id(it)).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { order(it) }))

    fun save(order: List<String>) {
        ids = order
        prefs.edit().putString(KEY, order.joinToString(",")).apply()
    }

    fun reset() = save(emptyList())

    private companion object {
        const val KEY = "order"
    }
}
