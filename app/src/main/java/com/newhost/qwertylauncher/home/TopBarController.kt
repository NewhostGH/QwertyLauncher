package com.newhost.qwertylauncher.home

import android.content.Context
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import com.newhost.qwertylauncher.R
import com.newhost.qwertylauncher.core.ArtAnimator
import com.newhost.qwertylauncher.core.BarButton
import com.newhost.qwertylauncher.core.BarStrip
import com.newhost.qwertylauncher.core.IconRenderer
import com.newhost.qwertylauncher.core.MenuItem
import com.newhost.qwertylauncher.core.find
import com.newhost.qwertylauncher.shell.ShellSettings
import com.newhost.qwertylauncher.ui.ArtPlayer
import com.newhost.qwertylauncher.ui.ArtView
import com.newhost.qwertylauncher.ui.BarStripView
import com.newhost.qwertylauncher.ui.Speech
import com.newhost.qwertylauncher.ui.TopBar
import com.newhost.qwertylauncher.ui.TtyFont
import com.newhost.qwertylauncher.ui.VimKeys
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.catch

/**
 * The top bar: help and config (the question mark and the wrench), and
 * modules' strips of quick buttons (the toggles). Each group sits on the
 * left, in the middle or on the right, and can be moved along the top.
 */
internal class TopBarController(private val activity: HomeActivity) {
    private val binding get() = activity.binding
    private val viewModel get() = activity.viewModel
    private val registry get() = activity.registry
    private val settings get() = activity.settings

    /** The top bar group being moved (hold enter on one, then "move"), if one is. */
    var movingGroup: String? = null
        private set

    /** The top bar's quick-button strips, by their place's id ("strip:<id>"), with their titles. */
    private val strips = linkedMapOf<String, BarStripView>()
    private val stripTitles = mutableMapOf<View, String>()
    private val stripSources = mutableMapOf<BarStripView, BarStrip>()
    /** Each button's label as shown; its content description is the label as said. */
    private val labels = mutableMapOf<View, String>()

    /** The strip in the place [id], if that's a strip's. */
    fun strip(id: String): BarStripView? = strips[id]

    /**
     * Modules' quick buttons in the top bar (the toggles), beside help and config: each drawn
     * exactly like them, moved as one (hold enter on one), and stepped through by the home
     * cursor. As many show as fit; the strip hears how many that is.
     */
    fun setupStrips() {
        for (strip in registry.barStrips) {
            val id = STRIP + strip.id
            val view = BarStripView(activity)
            strips[id] = view
            stripTitles[view] = strip.title
            stripSources[view] = strip
            activity.whileStarted {
                val renderer = registry.find<IconRenderer>()
                // Players run while started: a fresh set each start.
                val players = mutableMapOf<ArtView, ArtPlayer>()
                val drawn = mutableMapOf<ArtView, String>()
                strip.buttons()
                    .catch { e -> registry.log.e("${strip.id} strip failed", e) }
                    .collect { buttons ->
                        view.resize(buttons.size) { ArtView(activity).apply { isFocusable = true; isClickable = true } }
                        view.buttons().zip(buttons).forEach { (button, bar) ->
                            bindBarButton(strip, button, bar, renderer, players, drawn)
                        }
                        // A button that changed under the cursor says its new state.
                        view.buttons().firstOrNull { it.isFocused }?.let(labels::get)?.let { activity.prompt.flash(it) }
                    }
            }
        }
        // The room left for the strips, worked out again whenever the bar is laid out.
        // Posted: showing or hiding buttons during a layout pass would ask for another mid-way.
        binding.topBar.addOnLayoutChangeListener { bar, _, _, _, _, _, _, _, _ -> bar.post { updateStripRoom() } }
    }

    /** One strip button: its look (like the wrench), label, action and hold menu. */
    private suspend fun CoroutineScope.bindBarButton(
        strip: BarStrip,
        button: ArtView,
        bar: BarButton,
        renderer: IconRenderer?,
        players: MutableMap<ArtView, ArtPlayer>,
        drawn: MutableMap<ArtView, String>,
    ) {
        button.tag = bar.key
        // Read out by accessibility, never shown: no tooltips on the top bar.
        labels[button] = bar.label
        button.contentDescription = Speech.speakable(bar.label)
        button.setOnClickListener { bar.action.run(activity) }
        button.setOnLongClickListener {
            activity.showMenu(
                bar.label.substringBefore(':'),
                bar.menu + listOf(
                    MenuItem("move ${strip.title}") { startMovingGroup(STRIP + strip.id) },
                    MenuItem("reset top bar") { resetTopBar() },
                ),
            )
            true
        }
        styleTopButton(button, art = renderer != null)
        if (renderer == null) {
            button.text = "[${bar.short}]"
            return
        }
        val player = players.getOrPut(button) {
            ArtPlayer(button, this) { registry.find<ArtAnimator>() }.also { player ->
                button.setOnFocusChangeListener { _, focused -> player.select(focused) }
                player.select(button.isFocused)
            }
        }
        // Drawn again only when it changes: the label says its state.
        if (drawn[button] == bar.label) return
        drawn[button] = bar.label
        player.show(renderer.render(bar.icon(), BUTTON_COLS, BUTTON_ROWS))
    }

    /**
     * How many strip buttons fit: the bar's width less the cutouts and the other buttons,
     * in buttons as wide as the wrench, a column apart. Told to the strip when it changes.
     */
    private fun updateStripRoom() {
        val bar = binding.topBar
        if (bar.width == 0 || strips.isEmpty()) return
        val gap = TtyFont.cellWidth(activity.resources).roundToInt()
        val unit = strips.values.flatMap { it.shown() }.firstOrNull { it.measuredWidth > 0 }?.measuredWidth
            ?: binding.configButton.measuredWidth.takeIf { it > 0 }
            ?: return
        for ((_, view) in strips) {
            val others = groups().values.flatten().filter { it !== view && it.isVisible && it.parent === bar }
                .sumOf { it.measuredWidth + gap }
            val free = bar.width - bar.paddingLeft - bar.paddingRight - bar.blockedWidth() - others
            val room = ((free + gap) / (unit + gap)).coerceAtLeast(0)
            if (room != view.room) {
                view.room = room
                stripSources[view]?.onRoom(room)
            }
        }
    }

    fun setupTitleBar() {
        arrange()
        // Holding enter on a button (or a long touch): open it, or move help and config along the top.
        for (button in listOf(binding.helpButton, binding.configButton)) {
            button.setOnLongClickListener {
                showButtonMenu(button)
                true
            }
        }
        binding.configButton.setOnClickListener {
            if (viewModel.state.value.configOpen) activity.panes.close() else viewModel.openPane(Pane.CONFIG)
        }
        binding.helpButton.setOnClickListener {
            if (viewModel.state.value.pane == Pane.HELP) activity.panes.close() else viewModel.openPane(Pane.HELP)
        }
        // Re-rendered on every start: the ascii module's settings may have changed.
        activity.whileStarted {
            val renderer = registry.find<IconRenderer>()
            renderTitleButton(binding.helpButton, renderer, R.drawable.ic_help, R.string.help_fallback)
            // The wrench fills its whole square where the question mark is a slim glyph: shrunk to match.
            renderTitleButton(binding.configButton, renderer, R.drawable.ic_wrench, R.string.config_fallback, inset = WRENCH_INSET)
        }
    }

    /**
     * Everything is ASCII: the question mark and the wrench go through the same renderer as
     * app icons, draw themselves in, and flicker while selected.
     */
    private suspend fun CoroutineScope.renderTitleButton(
        button: TextView,
        renderer: IconRenderer?,
        icon: Int,
        fallback: Int,
        inset: Float = 0f,
    ) {
        val drawable = AppCompatResources.getDrawable(activity, icon)?.let { if (inset > 0f) InsetDrawable(it, inset) else it }
        styleTopButton(button, art = renderer != null && drawable != null)
        if (renderer == null || drawable == null) {
            button.onFocusChangeListener = null
            button.setText(fallback)
            return
        }
        val player = ArtPlayer(button, this) { registry.find<ArtAnimator>() }
        button.setOnFocusChangeListener { _, focused -> player.select(focused) }
        player.select(button.isFocused)
        player.show(renderer.render(drawable, BUTTON_COLS, BUTTON_ROWS))
    }

    /**
     * Sizes a top bar button, the same for all of them (help, config, the toggles): with [art],
     * the art fills the button size setting (in text lines) less a little inner padding;
     * without, its text is the terminal's. Framed buttons get a cell of room each side and a
     * line above and below for the box.
     */
    private fun styleTopButton(button: TextView, art: Boolean) {
        val resources = activity.resources
        button.tooltipText = null
        val framed = settings[ShellSettings.buttonFrames]
        (button as? ArtView)?.framed = framed
        val frameX = if (framed) ceil(TtyFont.cellWidth(resources)).toInt() else 0
        val frameY = if (framed) TtyFont.lineHeight(resources) else 0
        if (!art) {
            button.setPadding(frameX, frameY, frameX, frameY)
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, TtyFont.sizeSp)
            return
        }
        val lines = settings[ShellSettings.buttonSize]
        val padX = ceil(TtyFont.cellWidth(resources) / 2).toInt()
        val padY = TtyFont.lineHeight(resources) / BUTTON_PAD_PARTS
        button.setPadding(padX + frameX, padY + frameY, padX + frameX, padY + frameY)
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, TtyFont.sizeSp * (lines - 2f / BUTTON_PAD_PARTS) / BUTTON_ROWS)
    }

    // --- Where things sit in the top bar: help and config as one, and the toggles ---

    /**
     * The top bar's groups, each moved as one: help and config together, and each strip of
     * toggles. A group sits on the left, in the middle or on the right; two in the same place
     * sit side by side, in the order they're saved.
     */
    private fun groups(): Map<String, List<View>> =
        linkedMapOf<String, List<View>>(BUTTONS to listOf(binding.helpButton, binding.configButton)) + strips.mapValues { listOf(it.value) }

    /** The groups left to right, each with where it sits. Saved as "id:slot,id:slot". */
    fun order(): List<Pair<String, TopBar.Slot>> {
        val groups = groups()
        val saved = prefs().getString(TOP_BAR_GROUPS, null)
            ?.split(',')
            ?.mapNotNull { entry ->
                val id = entry.substringBeforeLast(':')
                val slot = TopBar.Slot.entries.firstOrNull { it.name == entry.substringAfterLast(':') } ?: return@mapNotNull null
                (id to slot).takeIf { id in groups }
            }
            .orEmpty()
        // New ones: the toggles on the left, help and config on the right.
        val missing = groups.keys.filter { id -> saved.none { it.first == id } }
            .map { it to if (it == BUTTONS) TopBar.Slot.END else TopBar.Slot.START }
        return (saved + missing).sortedBy { it.second.ordinal }
    }

    private fun prefs() = activity.getSharedPreferences(TOP_BAR_PREFS, Context.MODE_PRIVATE)

    private fun saveOrder(order: List<Pair<String, TopBar.Slot>>) {
        prefs().edit()
            .putString(TOP_BAR_GROUPS, order.joinToString(",") { (id, slot) -> "$id:${slot.name}" })
            .apply()
    }

    /** Puts every group in the top bar in its saved place. */
    private fun arrange(order: List<Pair<String, TopBar.Slot>> = order()) {
        val bar = binding.topBar
        val groups = groups()
        val focused = groups.values.flatten().firstOrNull { it.hasFocus() }
        bar.removeAllViews()
        for ((id, slot) in order) groups.getValue(id).forEach { bar.addView(it, TopBar.LayoutParams(slot)) }
        focused?.requestFocusFromTouch()
    }

    private fun resetTopBar() {
        prefs().edit().remove(TOP_BAR_GROUPS).apply()
        arrange()
    }

    private fun groupOf(view: View): String? = groups().entries.firstOrNull { view in it.value }?.key

    private fun groupName(id: String) = if (id == BUTTONS) "help and config" else strips[id]?.let { stripTitles[it] } ?: "toggles"

    /** Holding enter on help or config (or a long touch). */
    private fun showButtonMenu(button: View) {
        val id = groupOf(button) ?: return
        activity.showMenu(
            if (button === binding.helpButton) "help" else "config",
            listOf(
                MenuItem("open") { button.performClick() },
                MenuItem("move ${groupName(id)}") { startMovingGroup(id) },
                MenuItem("reset top bar") { resetTopBar() },
            ),
        )
    }

    // --- Moving a group: hold enter on it, then "move" ---

    private fun startMovingGroup(id: String) {
        movingGroup = id
        // Stays marked while it moves, wherever Android's focus goes.
        highlightGroup(id, true)
        groups()[id]?.firstOrNull()?.requestFocusFromTouch()
        activity.prompt.renderStatusLine()
        activity.prompt.flash("move ${groupName(id)}: ←→ move  ⏎ drop")
    }

    private fun highlightGroup(id: String, on: Boolean) {
        groups()[id]?.forEach { view ->
            (view as? ArtView)?.forceHighlight = on
            (view as? BarStripView)?.highlight = on
        }
    }

    /**
     * Moves the group one place along the top: past the other group when they share a place,
     * else on to the next place (left, middle, right). Saved right away.
     */
    private fun moveGroup(delta: Int) {
        val id = movingGroup ?: return
        val order = order().toMutableList()
        val i = order.indexOfFirst { it.first == id }
        val slot = order[i].second
        val neighbour = order.getOrNull(i + delta)
        when {
            neighbour != null && neighbour.second == slot -> {
                order[i] = neighbour
                order[i + delta] = id to slot
            }
            slot.ordinal + delta in TopBar.Slot.entries.indices -> {
                val next = TopBar.Slot.entries[slot.ordinal + delta]
                order.removeAt(i)
                // Arriving beside another group: on the side it came from.
                val at = if (delta > 0) order.indexOfFirst { it.second.ordinal >= next.ordinal }.let { if (it < 0) order.size else it }
                else order.indexOfLast { it.second.ordinal <= next.ordinal } + 1
                order.add(at, id to next)
            }
            else -> return
        }
        saveOrder(order)
        arrange(order)
        groups()[id]?.firstOrNull()?.requestFocusFromTouch()
        activity.prompt.renderStatusLine()
    }

    fun stopMovingGroup() {
        movingGroup?.let { highlightGroup(it, false) }
        movingGroup = null
        activity.prompt.renderStatusLine()
    }

    /** While moving a group, arrows (h, l) and enter/esc belong to the move. */
    fun onMoveKey(event: KeyEvent): Boolean {
        when {
            VimKeys.isLeft(event) || VimKeys.isUp(event) -> moveGroup(-1)
            VimKeys.isRight(event) || VimKeys.isDown(event) -> moveGroup(+1)
            event.repeatCount > 0 && isEnter(event) -> Unit
            isEnter(event) || event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_SPACE ||
                event.keyCode == KeyEvent.KEYCODE_Q || event.keyCode == KeyEvent.KEYCODE_BACK -> stopMovingGroup()
        }
        return true
    }

    /** Scrolling while moving a group moves it one place, either way. */
    fun onMoveScroll(down: Int, right: Int) = moveGroup(if (down + right > 0) 1 else -1)

    /** The status line while a group is moving: where it is now. Null when none is. */
    fun statusText(): String? {
        val id = movingGroup ?: return null
        val order = order()
        val slot = order.firstOrNull { it.first == id }?.second
        val where = when (slot) {
            TopBar.Slot.START -> "left"
            TopBar.Slot.CENTER -> "middle"
            else -> "right"
        }
        // Sharing its place: which side of the other group it's on.
        val same = order.filter { it.second == slot }.map { it.first }
        val side = if (same.size > 1) (if (same.first() == id) ", first" else ", second") else ""
        return "move ${groupName(id)}: $where$side"
    }

    companion object {
        /** The prefix of a strip's place id. */
        const val STRIP = "strip:"

        private const val TOP_BAR_PREFS = "top_bar"
        private const val TOP_BAR_GROUPS = "groups"
        private const val BUTTONS = "buttons"

        // The ASCII question mark and wrench: 16 × 8 characters, sized to the button size setting.
        /** The wrench's margin on each side, as a share of its size, so it looks as big as the question mark. */
        private const val WRENCH_INSET = 0.14f
        private const val BUTTON_COLS = 16
        private const val BUTTON_ROWS = 8
        /** The buttons' inner padding above and below: this part of a line, so the art doesn't touch the status line. */
        private const val BUTTON_PAD_PARTS = 4
    }
}
