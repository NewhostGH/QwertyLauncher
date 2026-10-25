package com.newhost.qwertylauncher.home

import android.app.ActivityOptions
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.RoundedCorner
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.newhost.qwertylauncher.LauncherApplication
import com.newhost.qwertylauncher.R
import com.newhost.qwertylauncher.core.ArtAnimator
import com.newhost.qwertylauncher.core.Console
import com.newhost.qwertylauncher.core.HomeScreen
import com.newhost.qwertylauncher.core.IconRenderer
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.ListPane
import com.newhost.qwertylauncher.core.MenuItem
import com.newhost.qwertylauncher.core.find
import com.newhost.qwertylauncher.databinding.ActivityHomeBinding
import com.newhost.qwertylauncher.shell.ShellHost
import com.newhost.qwertylauncher.shell.ShellSettings
import com.newhost.qwertylauncher.ui.AsciiMenu
import com.newhost.qwertylauncher.ui.FrameChars
import com.newhost.qwertylauncher.ui.FrameLook
import com.newhost.qwertylauncher.ui.TileFrame
import com.newhost.qwertylauncher.ui.TtyFont
import com.newhost.qwertylauncher.ui.grid.GridEnv
import com.newhost.qwertylauncher.ui.hideStatusBar
import com.newhost.qwertylauncher.ui.showStatusBar
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.newhost.qwertylauncher.core.R as CoreR

/**
 * The home screen, and the [LauncherHost] that modules talk to.
 *
 * Layout, top to bottom: widgets, panels (or search results while typing),
 * the console, and the prompt. The prompt stays closed, leaving arrow keys
 * and scrolling to the home screen, until a key is pressed (or the idle line
 * is tapped). It sits right above the keyboard, and results stack upwards
 * from it with the best match nearest.
 *
 * The activity wires the screen together and answers modules; each part of
 * the screen has a controller of its own: [tiles] (the tiles and their
 * arranging), [topBar] (help, config and the quick buttons), [prompt] (the
 * prompt, results, console and status line), [panes] (config, help, module
 * panes and screens), [cursor] (the home cursor) and [input] (keys, touch and
 * scrolling, routed to whichever of them has them).
 */
class HomeActivity : AppCompatActivity(), LauncherHost, ShellHost {

    internal lateinit var binding: ActivityHomeBinding
        private set
    internal lateinit var menu: AsciiMenu
        private set

    /** What grids (tiles, pane blocks) draw with; its scope follows home's started state. */
    internal val gridEnv by lazy {
        GridEnv(lifecycleScope, renderer = { registry.find<IconRenderer>() }, animator = { registry.find<ArtAnimator>() }, host = this)
    }
    internal val registry get() = (application as LauncherApplication).registry
    internal val settings get() = (application as LauncherApplication).settings
    internal val log get() = (application as LauncherApplication).log

    internal val viewModel: HomeViewModel by viewModels {
        HomeViewModel.Factory((application as LauncherApplication).registry)
    }

    internal val tiles = TileController(this)
    internal val topBar = TopBarController(this)
    internal val prompt = PromptController(this)
    internal val panes = PaneController(this)
    internal val cursor = HomeCursor(this)
    internal val input = HomeInput(this)

    private var pendingPick: ((Uri) -> Unit)? = null
    private val filePick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val onPicked = pendingPick
        pendingPick = null
        if (uri != null) onPicked?.invoke(uri)
    }

    private var pendingCreate: ((Uri) -> Unit)? = null
    private val jsonCreate = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val onPicked = pendingCreate
        pendingCreate = null
        if (uri != null) onPicked?.invoke(uri)
    }

    private var pendingPermissionCallback: ((Boolean) -> Unit)? = null
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingPermissionCallback?.invoke(granted)
        pendingPermissionCallback = null
    }

    override val context: Context get() = this
    override val lifecycleOwner: LifecycleOwner get() = this
    override val scope: CoroutineScope get() = viewModel.viewModelScope
    override val console: Console get() = viewModel.console

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before any view exists: every text view takes the terminal font from the theme.
        loadTerminalFont()
        // Apps open, and home comes back, instantly: a terminal has no transitions.
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        menu = AsciiMenu(binding.menuOverlay, binding.menuBox, this)
        applyWindowInsets()
        applyTerminalFont()
        input.start()
        tiles.setup()
        prompt.setup()
        topBar.setupStrips()
        topBar.setupTitleBar()
        panes.setup()
        cursor.track()
        panes.rebuildWhenLayoutChanges()
        prompt.blinkCursor()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // Home never finishes on Back; Back just steps out of search.
            override fun handleOnBackPressed() = input.escape()
        })

        whileStarted {
            viewModel.state.collect { render(it) }
        }

        if (savedInstanceState == null && settings[ShellSettings.bootBanner]) {
            val version = packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
            prompt.printBootBanner(version ?: "")
        }
        // Once per process: why some modules are off after the launcher kept crashing.
        (application as LauncherApplication).modules.takeSafeNotice()?.let { console.println(it) }
        if (savedInstanceState == null && !isDefaultHome()) {
            console.println("To use this as your home screen, run: settings home")
        }
        if (savedInstanceState == null && intent?.action == Intent.ACTION_APPLICATION_PREFERENCES) {
            viewModel.openPane(Pane.CONFIG)
        }
    }

    override fun onResume() {
        super.onResume()
        if (settings[ShellSettings.statusBar]) showStatusBar() else hideStatusBar()
        cursor.restore()
    }

    override fun onPause() {
        cursor.save()
        super.onPause()
    }

    override fun onDestroy() {
        input.stop()
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        // Coming back to home should show a fresh home screen.
        if (!isChangingConfigurations) {
            menu.close()
            panes.closeScreen()
            tiles.stopMoving()
            topBar.stopMovingGroup()
            tiles.stopResizing(keep = true)
            prompt.cancelEnter()
            clearQuery()
            viewModel.closePane()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when {
            // Home pressed while already home.
            intent.hasCategory(Intent.CATEGORY_HOME) -> {
                clearQuery()
                panes.close()
            }
            intent.action == Intent.ACTION_APPLICATION_PREFERENCES -> viewModel.openPane(Pane.CONFIG)
        }
    }

    /** Runs [block] each time home starts, cancelling it when home stops. */
    internal fun whileStarted(block: suspend CoroutineScope.() -> Unit): Job =
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED, block) }

    // --- Setup ---

    private fun applyWindowInsets() {
        // targetSdk 36 is always edge-to-edge; pad for the bars that show and the keyboard ourselves.
        // With the status bar hidden, home fills its place: only the top bar steps around a notch
        // there (and the screen's rounded corners), rather than the whole screen moving down.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.setPadding(max(bars.left, cutout.left), bars.top, max(bars.right, cutout.right), max(bars.bottom, cutout.bottom))
            binding.topBar.setCutouts(insets.displayCutout?.boundingRects.orEmpty() + roundedCorners(insets))
            WindowInsetsCompat.CONSUMED
        }
    }

    /**
     * The screen's rounded top corners, as boxes the top bar keeps out of: as wide as the curve
     * cuts in at the top of the screen, and as tall as the corner.
     */
    private fun roundedCorners(insets: WindowInsetsCompat): List<Rect> {
        val platform = insets.toWindowInsets() ?: return emptyList()
        val width = window.decorView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        return listOf(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT).mapNotNull { position ->
            val corner = platform.getRoundedCorner(position) ?: return@mapNotNull null
            val r = corner.radius
            if (r <= 0) return@mapNotNull null
            if (position == RoundedCorner.POSITION_TOP_LEFT) Rect(0, 0, r, r) else Rect(width - r, 0, width, r)
        }
    }

    /** The font setting: the theme's terminal font for text views, and [TtyFont] for views that draw text. */
    private fun loadTerminalFont() {
        val (overlay, font) = when (settings[ShellSettings.font]) {
            ShellSettings.FONT_DEJAVU -> R.style.TtyFontOverlay_DejaVu to R.font.dejavu_sans_mono
            ShellSettings.FONT_PLEX -> R.style.TtyFontOverlay_Plex to R.font.ibm_plex_mono
            ShellSettings.FONT_SYSTEM -> R.style.TtyFontOverlay_System to null
            else -> R.style.TtyFontOverlay_JetBrains to R.font.jetbrains_mono
        }
        theme.applyStyle(overlay, true)
        TtyFont.typeface = font?.let { ResourcesCompat.getFont(this, it) } ?: Typeface.MONOSPACE
        TtyFont.sizeSp = settings[ShellSettings.fontSize].toFloat()
        loadFrameLook()
    }

    /** The frame settings: the characters, and the colours of idle and selected frames. */
    private fun loadFrameLook() {
        FrameLook.chars = when (settings[ShellSettings.frameLines]) {
            ShellSettings.LINES_DOUBLE -> FrameChars.DOUBLE
            ShellSettings.LINES_HEAVY -> FrameChars.HEAVY
            ShellSettings.LINES_ROUNDED -> FrameChars.ROUNDED
            ShellSettings.LINES_ASCII -> FrameChars.ASCII
            else -> FrameChars.SINGLE
        }
        val (idle, selected) = when (settings[ShellSettings.frameColor]) {
            // The selected frame stands out in whichever colour idle ones aren't.
            ShellSettings.COLOR_BRIGHT -> CoreR.color.tty_fg to CoreR.color.tty_accent
            ShellSettings.COLOR_ACCENT -> CoreR.color.tty_accent to CoreR.color.tty_fg
            else -> CoreR.color.tty_dim to CoreR.color.tty_fg
        }
        FrameLook.idle = getColor(idle)
        FrameLook.selected = getColor(selected)
    }

    /** One text size for everything but ASCII art, like a console font. */
    private fun applyTerminalFont() {
        binding.query.blink = settings[ShellSettings.cursorBlink]
        listOf(binding.query, binding.promptLabel, binding.idleHint, binding.console, binding.hostLabel).forEach {
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, TtyFont.sizeSp)
        }
    }

    // --- Rendering ---

    private fun render(state: HomeUiState) {
        val paneOpen = state.pane != null
        prompt.renderStatusLine(state.pane)
        panes.render(state.pane)
        // The console belongs to the open prompt and takes home's place, like search results do:
        // the two never share the screen.
        val consoleVisible = state.console.isNotEmpty() && !paneOpen && state.promptOpen
        cursor.showHome(!state.searching && !paneOpen && !consoleVisible)
        prompt.render(state, consoleVisible)
    }

    internal fun keyboard() = WindowCompat.getInsetsController(window, binding.query)

    private fun isDefaultHome(): Boolean =
        getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)

    // --- Keys, scrolling and touch: see HomeInput ---

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        input.onKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        input.onGenericMotion(event) || super.dispatchGenericMotionEvent(event)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        input.onTouch(event) || super.dispatchTouchEvent(event)

    // --- LauncherHost ---

    override fun setQuery(text: String) = viewModel.onQueryChanged(text)

    override fun clearQuery() = viewModel.closePrompt()

    override fun openPrompt() = viewModel.openPrompt()

    override fun rerunSearch() = viewModel.rerunSearch()

    override fun showMenu(title: String, items: List<MenuItem>) = menu.show(title, items)

    override fun showScreen(screen: HomeScreen) = panes.showScreen(screen)

    override fun showPane(pane: ListPane) = panes.showPane(pane)

    // --- ShellHost ---

    override fun openHelp() = viewModel.openPane(Pane.HELP)

    override fun pickFile(types: Array<String>, onPicked: (Uri) -> Unit) {
        pendingPick = onPicked
        try {
            filePick.launch(types)
        } catch (e: ActivityNotFoundException) {
            pendingPick = null
            console.println("no file picker on this phone")
        }
    }

    override fun openConfig() = viewModel.openPane(Pane.CONFIG)

    override fun createJsonFile(name: String, onPicked: (Uri) -> Unit) {
        pendingCreate = onPicked
        try {
            jsonCreate.launch(name)
        } catch (e: ActivityNotFoundException) {
            pendingCreate = null
            console.println("no file picker on this phone")
        }
    }

    /** Opens home again, then ends this process: the new one loads modules afresh. */
    override fun restart() {
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finishAffinity()
        Process.killProcess(Process.myPid())
    }

    override fun requestPermission(permission: String, onResult: (granted: Boolean) -> Unit) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
            return
        }
        pendingPermissionCallback = onResult
        permissionRequest.launch(permission)
    }

    /** Apps open instantly, without the system's opening animation, like running a command. */
    override val launchOptions: Bundle?
        get() = ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()

    override val textSizeSp: Float get() = TtyFont.sizeSp

    override fun startActivitySafely(intent: Intent): Boolean =
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), launchOptions)
            true
        } catch (e: ActivityNotFoundException) {
            console.println("error: no app can handle that")
            false
        } catch (e: SecurityException) {
            console.println("error: not allowed (${e.message})")
            false
        }
}

/** A frame style setting's value as a [TileFrame.Style]. */
internal fun frameStyle(value: String) = when (value) {
    ShellSettings.FRAME_FULL -> TileFrame.Style.FULL
    ShellSettings.FRAME_NONE -> TileFrame.Style.NONE
    else -> TileFrame.Style.COMPACT
}
