package com.newhost.qwertylauncher.feature.herald

import android.content.Context
import android.text.format.DateFormat
import com.newhost.qwertylauncher.core.Actor
import com.newhost.qwertylauncher.core.Happenings
import com.newhost.qwertylauncher.core.IconRenderer
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.Pose
import com.newhost.qwertylauncher.core.Prop
import com.newhost.qwertylauncher.core.Services
import com.newhost.qwertylauncher.core.SettingsStore
import com.newhost.qwertylauncher.core.Stage
import com.newhost.qwertylauncher.core.StageTile
import com.newhost.qwertylauncher.core.find
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sir Alex of Quertia, hedge knight and herald. He lives in his tree (the
 * realm tile) and comes out now and then to walk and climb about the
 * tiles and get up to things: nudging the clock, dancing to the music,
 * shielding the weather, blowing his trumpet to proclaim a saying.
 *
 * He sees where he can stand ([World]): tile tops and bottoms and the
 * ground, the ceiling and the walls; he walks along, hops down, and sets up
 * his ladder to climb. Tap him to poke him; catch him at mischief and he
 * bolts for home. Drag him to carry him off, kicking. With the AI set up he
 * thinks up his own lines and answers what you say to him ([Brain]), and
 * he remembers ([Memory]).
 */
internal class Alex(
    private val context: Context,
    private val settings: SettingsStore,
    private val services: Services,
    private val sayings: Sayings,
    private val memory: Memory,
    private val brain: Brain,
    private val life: Life,
    private val chronicle: Chronicle,
    private val senses: PhoneSenses,
    private val realm: () -> Int,
) : Actor {
    override val id = "alex"

    private val _out = MutableStateFlow(false)

    /** Whether he's out on the home screen now (else he's home in his tree). */
    val out: StateFlow<Boolean> = _out.asStateFlow()

    val present: Boolean get() = _out.value

    internal val pokes = Channel<Unit>(Channel.CONFLATED)
    private val summons = Channel<Unit>(Channel.CONFLATED)
    private val knocks = Channel<Unit>(Channel.CONFLATED)
    private val dismissals = Channel<Unit>(Channel.CONFLATED)
    private val proclamations = Channel<String>(Channel.CONFLATED)
    private val words = Channel<String>(Channel.CONFLATED)
    private val drags = Channel<Drag>(Channel.CONFLATED)

    private val prefs = context.getSharedPreferences("herald", Context.MODE_PRIVATE)

    /**
     * When he next comes out: kept here, so leaving home and coming back
     * doesn't bring him sooner. Null until his first visit is planned: a
     * while after the launcher starts, like any other (straight away only
     * when he stays out).
     */
    @Volatile private var nextVisit: Long? = null

    /** His picture, drawn once per pose; kept while the same renderer is around. */
    private var pictures: Pair<IconRenderer, PictureLook>? = null

    /** How he's drawn now: his picture, or text without the ascii art module. */
    private fun currentLook(): Look {
        val renderer = services.find<IconRenderer>() ?: return TextLook
        pictures?.takeIf { it.first === renderer }?.let { return it.second }
        return PictureLook(renderer, outfit()).also { pictures = renderer to it }
    }

    /** What he wears now: one being tried on, else what [Wardrobe] picks. */
    private fun outfit(napping: Boolean = false): Outfit = trying ?: Wardrobe.pick(chronicle, life, senses, napping)

    // --- For `alex debug` ---

    /** An outfit tried on from `alex debug`, locked or not, worn over his own choice until cleared; never saved. */
    @Volatile var trying: Outfit? = null
        private set

    /** A scene asked for by name from `alex debug`, played as soon as he's out, whatever the hour or weather. */
    @Volatile private var requested: String? = null

    /** The visit in progress or coming, while he's on a home screen: where his scenes are. */
    @Volatile private var show: Show? = null

    /** When he next comes out, in epoch milliseconds; null before the first visit is planned. */
    val nextVisitAt: Long? get() = nextVisit

    /** Has him do the scene (or moment) called [name], coming out to do it if he's home. */
    fun play(name: String) {
        requested = name
        summons.trySend(Unit)
    }

    /** Dresses him in [outfit] (null: back to his own choice), and calls him out to show it. */
    fun tryOn(outfit: Outfit?) {
        trying = outfit
        (pictures?.second)?.outfit = outfit()
        summons.trySend(Unit)
    }

    /** Every scene and moment, and whether each could happen now: empty until home has been shown. */
    fun scenes(): List<SceneInfo> = show?.report().orEmpty()

    override fun onPoke(host: LauncherHost) {
        pokes.trySend(Unit)
    }

    override fun onDrag(column: Int, line: Int, dropped: Boolean) {
        drags.trySend(Drag(column, line, dropped))
    }

    /** Calls him out now, if he's home. */
    fun summon() {
        summons.trySend(Unit)
    }

    /** A knock on his door (a tap on his realm): out he comes; at night, not without grumbling. */
    fun knock() {
        knocks.trySend(Unit)
    }

    /** The countdown whose end he last trumpeted, so each is trumpeted once. */
    @Volatile private var timerTrumpeted = 0L

    /** Sends him home. */
    fun dismiss() {
        dismissals.trySend(Unit)
    }

    /** Has him proclaim [text], coming out to do it if he's home. */
    fun proclaim(text: String) {
        proclamations.trySend(text)
        summons.trySend(Unit)
    }

    /** The user says [text] to him: he comes out and answers. */
    fun hear(text: String) {
        words.trySend(text)
        summons.trySend(Unit)
    }

    override fun perform(stage: StateFlow<Stage>): Flow<Pose?> = flow {
        Show(stage, this).run()
    }

    /** Where the user's finger has him: his top left, in cells. */
    private class Drag(val column: Int, val line: Int, val dropped: Boolean)

    /** Thrown out of a scene when he's poked. */
    private class Poked : Exception()

    /** Thrown out of a scene when he's picked up. */
    private class Grabbed(val drag: Drag) : Exception()

    /** Thrown when he leaves the screen mid-visit: bolting home, or sent home. */
    private class Gone : Exception()

    /** One visit after another, with time at home between. */
    private inner class Show(private val stage: StateFlow<Stage>, private val out: FlowCollector<Pose?>) {
        private var look: Look = currentLook()
        private val wide get() = look.columns
        private val tall get() = look.lines

        /** His box's left edge, and the line his feet are on. */
        private var x = 0
        private var feet = 0
        private var right = true

        /** His ladder, while it's up. */
        private var ladder: Prop? = null

        /** Mid-air or mid-climb (or carried): not standing anywhere, and that's fine for now. */
        private var airborne = 0

        /** When he was poked lately, to tell a poke from a pestering. */
        private val poked = ArrayDeque<Long>()

        /** Company for a scene (a cat, a goose, a raven), drawn behind him. */
        private var company: List<Prop> = emptyList()

        /** What he says first next time he's out, instead of a greeting (welcome back, and the like). */
        private var welcome: String? = null

        /** How many notifications there were, to see a raven arrive with a new one. */
        private var letters: Int? = null

        /** Dressed for the moment: what [Wardrobe] picks; pyjamas when he's [napping] at night. */
        private fun dress(napping: Boolean = false) {
            (look as? PictureLook)?.outfit = outfit(napping)
        }

        private fun world() = World(stage.value, wide, tall, realm = REALM)

        suspend fun run() {
            arrived()
            while (true) {
                _out.value = false
                out.emit(null)
                // Calls made while he was out are spent; not one for a scene still to play.
                if (requested == null) summons.tryReceive()
                val visit = nextVisit ?: (System.currentTimeMillis() + if (staysOut()) FIRST_VISIT_MS else absence()).also { nextVisit = it }
                away(visit - System.currentTimeMillis())
                look = currentLook()
                dress()
                // Only on a home screen with tiles to stand on.
                stage.first { World(it, wide, tall, realm = REALM).onTiles(except = REALM).isNotEmpty() }
                _out.value = true
                pokes.tryReceive()
                dismissals.tryReceive()
                drags.tryReceive()
                try {
                    perform { enter() }
                    letters = senses.notifications()
                    chronicle.check()
                    chronicle.unwritten()?.let { day -> perform { writeJournal(day) } }
                    // A few scenes; or, when he stays out, one after another, standing about between.
                    var left = Random.nextInt(MIN_SCENES, MAX_SCENES + 1)
                    while (staysOut() || left-- > 0) {
                        attend()
                        perform(pick())
                        if (staysOut()) perform { idle() }
                    }
                    attend()
                    perform { leave() }
                } catch (e: Gone) {
                    // Home, or off the screen, already.
                }
                nextVisit = System.currentTimeMillis() + absence()
            }
        }

        /**
         * Anything asked of him meanwhile, or going on: something to say
         * back, something to proclaim, a countdown ending, music playing, a
         * raven with a letter.
         */
        private suspend fun attend() {
            requested?.let { name ->
                requested = null
                (moments + scenes).firstOrNull { it.name == name }?.let { perform(it) }
            }
            words.tryReceive().getOrNull()?.let { text -> perform { converse(text) } }
            proclamations.tryReceive().getOrNull()?.let { text -> perform { proclaim(text) } }
            if (timerDue()) perform { timeUp() }
            if (senses.musicPlaying()) perform { dance() }
            val count = senses.notifications()
            val before = letters
            letters = count
            if (count != null && before != null && count > before) perform { raven() }
        }

        /** The home screen shows again: a friendly knight comes to say hello after a long while away. */
        private fun arrived() {
            val gap = life.seen()
            val now = System.currentTimeMillis()
            when {
                gap >= LONG_AWAY_MS -> welcome = Lines.awayLong.random()
                gap >= WELCOME_AFTER_MS && life.friendly && life.phase() != Phase.NIGHT -> welcome = Lines.welcomeBack.random()
                else -> return
            }
            nextVisit = now + WELCOME_MS
        }

        /** A countdown the timer command started has just ended, and he hasn't trumpeted it yet. */
        private fun timerDue(): Boolean {
            val ends = senses.timerEnds() ?: return false
            val now = System.currentTimeMillis()
            return ends <= now && now - ends < TIMER_LATE_MS && ends != timerTrumpeted
        }

        /**
         * Waits [ms] at home, or until he's called for, knocked for, or a
         * countdown ends. At night a knock gets "five more minutes" through
         * the door; knock again soon and he comes out, grumbling.
         */
        private suspend fun away(ms: Long) {
            var left = ms
            var lastKnock = 0L
            while (left > 0) {
                val start = System.currentTimeMillis()
                val timer = senses.timerEnds()?.let { it - start }?.takeIf { it > 0 }
                val wait = if (timer != null) minOf(left, timer) else left
                val event = withTimeoutOrNull(wait) {
                    select<String> {
                        summons.onReceive { "summon" }
                        knocks.onReceive { "knock" }
                    }
                }
                when (event) {
                    "summon" -> return
                    "knock" -> {
                        val now = System.currentTimeMillis()
                        if (life.phase() != Phase.NIGHT || now - lastKnock < KNOCK_AGAIN_MS) {
                            if (life.phase() == Phase.NIGHT) welcome = Lines.wokenUp.random()
                            return
                        }
                        lastKnock = now
                        sayThroughDoor(Lines.fiveMore.random())
                    }
                    else -> if (timerDue()) return
                }
                left -= System.currentTimeMillis() - start
            }
        }

        /** Speaks from inside his tree, when it's in view: just the words, by the door. */
        private suspend fun sayThroughDoor(text: String) {
            val w = world()
            val door = door(w) ?: return
            out.emit(Pose(door.first, door.second.feet - tall + 1, says = text))
            delay(readingTime(text))
            out.emit(null)
        }

        private fun staysOut() = settings[HeraldSettings.often] == ALWAYS

        /** Time at home until the next visit; longer at night, when he'd rather sleep. */
        private fun absence(): Long {
            val (from, to) = when (settings[HeraldSettings.often]) {
                ALWAYS -> 40_000L to 70_000L
                "often" -> 30_000L to 90_000L
                "rarely" -> 15 * 60_000L to 30 * 60_000L
                else -> 3 * 60_000L to 8 * 60_000L
            }
            val night = life.phase() == Phase.NIGHT && !staysOut()
            return Random.nextLong(from, to) * if (night) NIGHT_ABSENCE else 1
        }

        // --- Seeing ---

        /** Where he'd stand on the tile [id]: on top when there's room, else on its bottom line. */
        private fun surfaceOf(w: World, id: String): Platform? = w.platforms.filter { it.tile == id }.minByOrNull { it.feet }

        /** His tree's door, and the ground in front of it, when it's in view. */
        private fun door(w: World): Pair<Int, Platform>? {
            val t = w.tile(REALM) ?: return null
            val bottom = t.line + t.lines - 1
            val ground = w.platforms.firstOrNull { it.tile == REALM && it.feet == bottom } ?: return null
            return ground.nearest(t.column + t.columns / 2 - wide / 2) to ground
        }

        /**
         * How tall he is where he is: anywhere in his realm's tile (standing
         * by his tree, falling into it, carried over it), to the tree's
         * scale; elsewhere his full height.
         */
        private fun sizeHere(w: World): Int {
            val t = w.tile(REALM) ?: return tall
            val inside = feet in t.line + 1 until t.line + t.lines && x + wide / 2 in t.column until t.column + t.columns
            return if (inside) realmSize(t) else tall
        }

        /**
         * His box's height by his tree, so he fits through its door: the
         * door is 30 of the scene's 120 units tall (the scene gains sky in a
         * tall tile, so then fewer), and he fills [FIGURE_SHARE] of his box;
         * he's drawn to [FIT_IN_DOOR] of the door's height, rounded down.
         * Smaller or bigger than usual, as the tree is: in a big realm tile,
         * a big knight.
         */
        private fun realmSize(t: StageTile): Int {
            // The tile's picture is below its title rule, and left of the column of air on its right.
            val lines = (t.lines - 1).coerceAtLeast(1)
            val columns = (t.columns - 1).coerceAtLeast(1)
            val aspect = columns * CELL_ASPECT / lines
            val units = maxOf(SCENE_H, SCENE_W / aspect)
            val door = lines * DOOR_UNITS / units
            return (door * FIT_IN_DOOR / FIGURE_SHARE).toInt().coerceIn(MIN_SIZE, tall * MAX_GROWTH)
        }

        // --- Drawing ---

        private suspend fun show(move: Move, says: String? = null) {
            val w = world()
            out.emit(look.pose(move, right, x, feet, says, sizeHere(w), listOfNotNull(ladder) + company))
        }

        /**
         * Shows [move] for [ms]. When he's [listening], a poke meanwhile
         * ends the scene, and so does being picked up or sent home.
         */
        private suspend fun pose(move: Move, ms: Long, says: String? = null, listening: Boolean = true) {
            if (airborne == 0) settle()
            show(move, says)
            if (ms <= 0) return
            if (!listening) {
                delay(ms)
                return
            }
            val event = withTimeoutOrNull(ms) {
                select<Any> {
                    pokes.onReceive { it }
                    drags.onReceive { it }
                    dismissals.onReceive { Gone() }
                }
            }
            when (event) {
                null -> Unit
                is Drag -> throw Grabbed(event)
                is Gone -> {
                    goHome(bolt = false)
                    throw event
                }
                else -> throw Poked()
            }
        }

        /**
         * Makes sure he's somewhere he can be: in view, on something. Scrolled
         * away, he drops back in from the top; left in mid-air (a tile moved
         * or went), he falls to what's below. Walking in or out at an edge is fine.
         */
        private suspend fun settle() {
            val w = world()
            if (x < w.leftWall || x > w.rightWall) return
            if (w.inView(feet) && w.standingOn(x, feet) != null) return
            if (!w.inView(feet)) {
                val spot = w.nearest(x, feet)
                x = spot.nearest(x)
                feet = w.ceiling + tall - 1
                fallTo(w.below(x, feet).feet, quiet = true)
            } else {
                x = w.clampX(x)
                fallTo(w.below(x, feet).feet)
            }
        }

        /** Runs [scene]; if he's poked, its [Scene.caught] reaction (or a taunt); if picked up, he's carried. */
        private suspend fun perform(scene: Scene) {
            val w = world()
            val target = scene.needs?.let { id -> surfaceOf(w, id) }
            if (scene.needs != null && target == null) return
            try {
                scene.play(this, target)
            } catch (e: Poked) {
                react(scene.caught)
            } catch (e: Grabbed) {
                carried(e.drag)
            } finally {
                ladder = null
                airborne = 0
                company = emptyList()
            }
            // Doing anything at all is a little less dull.
            life.played(PLAYED_PER_SCENE)
        }

        private suspend fun perform(play: suspend Show.() -> Unit) = perform(Scene("", 1, null, { play() }))

        /** A poke: his scene's reaction the first time, a taunt after; pester him and he duels, then leaves. */
        private suspend fun react(caught: (suspend Show.() -> Unit)?) {
            var reaction = caught
            while (true) {
                val now = System.currentTimeMillis()
                poked.addLast(now)
                while (poked.isNotEmpty() && now - poked.first() > PESTER_MS) poked.removeFirst()
                memory.bump(Memory.POKED)
                life.poked()
                chronicle.poked()
                ladder = null
                airborne = 0
                try {
                    when {
                        poked.size >= (if (life.wary) WARY_LEAVE_AFTER else LEAVE_AFTER) -> {
                            pose(Moves.HUFF, 2000, Lines.huffs.random(), listening = false)
                            memory.remember("The user pestered me with pokes until I stormed home in a huff")
                            goHome(bolt = true)
                            throw Gone()
                        }
                        poked.size == DUEL_AT -> duel()
                        reaction != null -> reaction(this)
                        else -> taunt()
                    }
                    return
                } catch (e: Poked) {
                    // Poked again while reacting: react to that.
                    reaction = null
                } catch (e: Grabbed) {
                    carried(e.drag)
                    return
                }
            }
        }

        /** Held up by the user and carried about, until they let go; then he drops to whatever is under him. */
        private suspend fun carried(start: Drag) {
            var drag = start
            val protest = Lines.carried.random()
            var kick = 0
            ladder = null
            airborne++
            try {
                while (!drag.dropped) {
                    // The finger holds him by his top left; his feet hang below.
                    x = drag.column
                    feet = drag.line + tall - 1
                    show(if (kick++ / 2 % 2 == 0) Moves.DANGLE1 else Moves.DANGLE2, protest)
                    drag = withTimeoutOrNull(KICK_MS) { drags.receive() } ?: drag
                }
                val w = world()
                x = w.clampX(drag.column)
                feet = (drag.line + tall - 1).coerceIn(w.ceiling + tall - 1, w.floor)
                val landing = w.below(x, feet)
                // Dropped hard onto somewhere: he'll be nervous there from now on.
                if (fallTo(landing.feet)) life.droppedOn(landing.tile)
            } finally {
                airborne--
            }
            pokes.tryReceive()
            chronicle.carried()
            memory.bump(Memory.CARRIED)
            if (memory.count(Memory.CARRIED) % REMEMBER_EVERY == 1) memory.remember("The user picked me up and carried me off, most undignified")
            pose(Moves.HUFF, 2400, Lines.putDown.random(), listening = false)
        }

        private suspend fun taunt() {
            pose(Moves.FLINCH, 500, "Ow!")
            val line = if (brain.awake && Random.nextInt(AI_TAUNT_ODDS) == 0) {
                thinkAloud("The user just poked you. In all they have poked you ${memory.count(Memory.POKED)} times.")
            } else {
                null
            }
            pose(Moves.HUFF, readingTime(line ?: ""), line ?: Lines.taunts.random())
        }

        private suspend fun duel() {
            pose(Moves.EN_GARDE, 1400, "Enough! En garde!")
            repeat(3) {
                pose(Moves.SWING1, 150)
                pose(Moves.SWING2, 150)
                pose(Moves.SWING3, 150)
            }
            pose(Moves.EN_GARDE, 2000, "Hah! Thou art lucky I polished my sword and not my patience.")
        }

        // --- Getting about ---

        /** Out of his tree, when it's in view; else in from the side of the screen, or dropping in. */
        private suspend fun enter() {
            val w = world()
            val door = door(w)
            val dest = w.onTiles(except = REALM).random()
            if (door != null) {
                x = door.first
                feet = door.second.feet
                right = Random.nextBoolean()
                pose(Moves.STAND, 700, "*creak*")
                travelTo(dest, dest.nearest(x + Random.nextInt(-8, 9)))
            } else {
                feet = dest.feet
                val target = dest.left + Random.nextInt(dest.right - dest.left + 1)
                when {
                    dest.left <= w.leftWall && Random.nextBoolean() -> {
                        right = true
                        x = -wide
                    }
                    dest.right >= w.rightWall -> {
                        right = false
                        x = w.columns
                    }
                    else -> {
                        // Drops in from the top.
                        x = target
                        right = Random.nextBoolean()
                        feet = w.ceiling + tall - 1
                        fallTo(dest.feet)
                    }
                }
                walkTo(target)
            }
            // Wary of the user: a careful look round first.
            if (life.wary) {
                pose(Moves.PEEK, 1400, sometimes(Lines.peeks.random()))
                pose(Moves.PEEK, 900)
            }
            val greeting = welcome
            welcome = null
            if (!prefs.getBoolean(MET, false)) {
                prefs.edit().putBoolean(MET, true).apply()
                life.met()
                memory.remember("I met my liege, the user, for the first time")
                introduce()
            } else if (greeting != null) {
                pose(Moves.WAVE, readingTime(greeting), greeting)
            } else {
                pose(Moves.WAVE, 2200, if (life.wary) null else sometimes(Lines.greetings.random()))
            }
            pose(Moves.STAND, 600)
        }

        /** How he first introduces himself. */
        private suspend fun introduce() {
            pose(Moves.BOW, 3200, "Sir Alex of Quertia, hedge knight and herald, at thy service!")
            pose(Moves.WAVE, 2800, "That little tree is my realm. Small, but mine.")
            pose(Moves.STAND, 2600, "Tap me if thou must. Carry me if thou darest.")
        }

        private suspend fun leave() {
            pose(Moves.WAVE, 1800, sometimes(Lines.farewells.random()))
            goHome(bolt = false)
            throw Gone()
        }

        /**
         * Home to his tree, when it's in view: walking in and shutting the
         * door; or, if he [bolt]s, running, and slamming it. Without it in
         * view, off the nearer side of the screen.
         */
        private suspend fun goHome(bolt: Boolean) {
            val door = door(world())
            if (door == null) {
                runOff()
                return
            }
            ladder = null
            airborne = 0
            travelTo(door.second, door.first, listening = false, run = bolt)
            if (!bolt) pose(Moves.STAND, 500, "*creak*", listening = false)
            _out.value = false
            out.emit(Pose(x, feet - tall + 1, says = if (bolt) "*SLAM!*" else "*click*"))
            delay(DOOR_MS)
            out.emit(null)
        }

        /** Runs off the nearer side, fast. */
        private suspend fun runOff() {
            val w = world()
            val toLeft = x + wide / 2 < w.columns / 2
            right = !toLeft
            val end = if (toLeft) -wide else w.columns
            var step = 0
            airborne++
            try {
                while (x != end) {
                    x += if (end > x) 1 else -1
                    show(if (step++ / 2 % 2 == 0) Moves.RUN1 else Moves.RUN2)
                    delay(RUN_MS)
                }
            } finally {
                airborne--
            }
            out.emit(null)
        }

        /** Along whatever he's standing on, to [target] (as near as it goes); running, if [run]. */
        private suspend fun walkTo(target: Int, listening: Boolean = true, run: Boolean = false, steps: Pair<Move, Move>? = null, stepMs: Long = WALK_MS) {
            val on = world().standingOn(x, feet)
            // Walking in from off the screen, he keeps going until he's on.
            val goal = if (on != null && x in on.left..on.right) on.nearest(target) else target
            if (goal != x) right = goal > x
            var step = 0
            while (x != goal) {
                x += if (goal > x) 1 else -1
                val move = when {
                    steps != null -> if (step++ % 2 == 0) steps.first else steps.second
                    run -> if (step++ / 2 % 2 == 0) Moves.RUN1 else Moves.RUN2
                    else -> if (step++ % 2 == 0) Moves.WALK1 else Moves.WALK2
                }
                pose(move, if (run) RUN_MS else stepMs, listening = listening)
            }
            pose(Moves.STAND, 0)
        }

        /**
         * To [spot] on [dest]: along the same surface; else to a column both
         * share, then up his ladder, or down (a hop, if it's not far; else
         * the ladder); with nothing in common, a leap across from the end.
         */
        private suspend fun travelTo(dest: Platform, spot: Int, listening: Boolean = true, run: Boolean = false) {
            val w = world()
            val here = w.standingOn(x, feet) ?: w.below(w.clampX(x), feet)
            val goal = dest.nearest(spot)
            if (here.feet == dest.feet && here.holds(goal)) {
                walkTo(goal, listening, run)
                return
            }
            val from = maxOf(here.left, dest.left)
            val to = minOf(here.right, dest.right)
            if (from <= to) {
                walkTo(x.coerceIn(from, to), listening, run)
                when {
                    dest.feet < feet -> climb(dest.feet, listening, run)
                    dest.feet - feet <= HOP_DOWN -> hopTo(x, dest.feet, listening)
                    else -> climb(dest.feet, listening, run)
                }
            } else {
                val edge = if (dest.left > here.right) here.right else here.left
                walkTo(edge, listening, run)
                hopTo(dest.nearest(edge), dest.feet, listening)
            }
            walkTo(goal, listening, run)
        }

        /** Sets up his ladder and climbs it, up or down, to [to]; then tucks it away. */
        private suspend fun climb(to: Int, listening: Boolean = true, quick: Boolean = false) {
            val top = minOf(feet, to)
            val bottom = maxOf(feet, to)
            // A rung a line, poking out a line above the top.
            ladder = Prop(x + wide / 2 - 1, top - LADDER_OVER, List(bottom - top + 1 + LADDER_OVER) { "|=|" }, LADDER)
            pose(Moves.SET_LADDER, if (quick) 150 else 500, if (quick) null else "*clunk*", listening)
            airborne++
            try {
                var step = 0
                while (feet != to) {
                    feet += if (to > feet) 1 else -1
                    pose(if (step++ % 2 == 0) Moves.CLIMB1 else Moves.CLIMB2, if (quick) CLIMB_MS / 2 else CLIMB_MS, listening = listening)
                }
            } finally {
                airborne--
            }
            pose(Moves.STAND, if (quick) 60 else 250, listening = listening)
            ladder = null
        }

        private suspend fun hopTo(spot: Int, landing: Int, listening: Boolean = true) {
            right = spot >= x
            val fromX = x
            val fromFeet = feet
            val steps = (abs(spot - fromX) / 3 + abs(landing - fromFeet)).coerceIn(MIN_HOP, MAX_HOP)
            val height = HOP_HEIGHT + abs(landing - fromFeet) / 2
            pose(Moves.LAND, 180, listening = listening)
            airborne++
            try {
                for (i in 1..steps) {
                    val t = i / steps.toFloat()
                    x = (fromX + (spot - fromX) * t).roundToInt()
                    val base = fromFeet + (landing - fromFeet) * t
                    // Never through the ceiling.
                    feet = (base - 4 * height * t * (1 - t)).roundToInt().coerceAtLeast(world().ceiling + tall - 1)
                    pose(Moves.JUMP, HOP_MS, listening = listening)
                }
            } finally {
                airborne--
            }
            x = spot
            feet = landing
            pose(Moves.LAND, 200, listening = listening)
            pose(Moves.STAND, 250, listening = listening)
        }

        /** Down to [landing], line by line; a hard landing from high up, unless [quiet]. Returns whether it was hard. */
        private suspend fun fallTo(landing: Int, quiet: Boolean = false): Boolean {
            val from = feet
            airborne++
            try {
                while (feet < landing) {
                    feet++
                    show(Moves.FALL)
                    delay(FALL_MS)
                }
            } finally {
                airborne--
            }
            feet = landing
            if (!quiet && landing - from >= HARD_LANDING) {
                show(Moves.SPLAT, "Oof.")
                delay(SPLAT_MS)
                return true
            }
            show(Moves.LAND)
            delay(LAND_MS)
            return false
        }

        // --- Thinking ---

        /** What he sees, for his brain. */
        private fun situation(): String {
            val w = world()
            val on = w.standingOn(x, feet)?.tile?.let { id -> w.tile(id)?.title ?: id }
            val tiles = w.tiles.joinToString(", ") { it.title }
            val now = LocalDateTime.now()
            return "It is ${time()} on a ${now.dayOfWeek.name.lowercase()}. You stand on " +
                (on?.let { "the $it tile" } ?: "the bottom of the screen") + ". The tiles on screen: $tiles."
        }

        /** Asks his brain about [situation], pondering meanwhile; null if it has nothing. */
        private suspend fun thinkAloud(situation: String, answering: String? = null): String? =
            thinkAloud(situation) { if (answering != null) brain.answer(answering, situation) else brain.think(situation) }

        /** Ponders while [think] works; its answer, or null. */
        private suspend fun thinkAloud(@Suppress("UNUSED_PARAMETER") about: String, think: suspend () -> String?): String? = coroutineScope {
            val thought = async { think() }
            var i = 0
            while (thought.isActive) {
                pose(if (i % 4 < 2) Moves.PONDER else Moves.IDLE2, 400, "Hmm" + ".".repeat(i % 4))
                i++
            }
            thought.await()
        }

        /** Something said to him: he listens, thinks, and answers. */
        private suspend fun converse(text: String) {
            chronicle.talked()
            pose(Moves.LISTEN, 1000, "Hmm? What sayest thou?")
            val reply = if (brain.awake) thinkAloud(situation(), answering = text) else null
            if (!brain.awake) memory.remember("The user said \"$text\" to me; I had no wits to answer", Memory.TALKED)
            val line = reply ?: Lines.noWits.random()
            pose(Moves.STAND, readingTime(line), line)
        }

        // --- The scenes ---

        private fun pick(): Scene {
            val w = world()
            val shown = w.platforms.mapNotNull { it.tile }.toSet()
            val weighed = scenes
                .filter { (it.needs == null || it.needs in shown) && (!it.thinks || brain.awake) && it.can() }
                .map { it to (it.weight + it.extra()).coerceAtLeast(0) }
                .filter { it.second > 0 }
            var roll = Random.nextInt(weighed.sumOf { it.second }.coerceAtLeast(1))
            for ((scene, weight) in weighed) {
                roll -= weight
                if (roll < 0) return scene
            }
            return weighed.lastOrNull()?.first ?: scenes.first()
        }

        private suspend fun proclaim(text: String) {
            repeat(2) {
                pose(Moves.TRUMPET1, 260, "Toot!")
                pose(Moves.TRUMPET2, 260, "Toot toot!")
            }
            pose(Moves.TRUMPET2, 900, "Hear ye! Hear ye!")
            pose(Moves.STAND, readingTime(text), text)
        }

        private suspend fun idle() {
            repeat(Random.nextInt(2, 6)) {
                pose(Moves.STAND, 1400)
                pose(if (Random.nextBoolean()) Moves.IDLE1 else Moves.IDLE2, 1400)
            }
        }

        private fun readingTime(text: String) = (1500L + text.length * 55L).coerceAtMost(9000L)

        /** [line] now and then; else he just gets on with it, quietly. A bit of silence is fine. */
        private fun sometimes(line: String): String? = line.takeIf { Random.nextInt(100) < TALK_PERCENT }

        /** Walks off somewhere and potters about, saying nothing. */
        private suspend fun potter() {
            val tiles = world().onTiles(except = REALM)
            if (tiles.isNotEmpty()) goTo(tiles.random())
            idle()
        }

        private suspend fun mischief(moves: List<Move>, lines: List<String>, each: Long = 260) {
            val per = (MISCHIEF_FRAMES / lines.size).coerceAtLeast(1)
            lines.forEachIndexed { i, line ->
                repeat(per) { f -> pose(moves[(i * per + f) % moves.size], each, line) }
            }
        }

        /** Over to somewhere on [dest]; where he was once dropped hard, he eyes the edge. */
        private suspend fun goTo(dest: Platform) {
            travelTo(dest, dest.left + Random.nextInt(dest.right - dest.left + 1))
            if (life.feared(dest.tile) && Random.nextInt(100) < TALK_PERCENT) pose(Moves.PEEK, 1800, Lines.nervous.random())
        }

        /** A tile's scene: go to it, then [act]. */
        private fun at(id: String, name: String, weight: Int, caught: (suspend Show.() -> Unit)?, act: suspend Show.() -> Unit) =
            Scene(name, weight, id, { dest -> goTo(dest!!); act() }, caught)

        private val scenes: List<Scene> = listOf(
            // Anywhere, quietly: pottering about, gazing at the sky.
            Scene("potter about", 10, null, { potter() }),
            Scene("gaze at the sky", 4, null, {
                pose(Moves.GAZE, 2600)
                pose(Moves.STAND, 1400)
            }),
            // Anywhere, out loud.
            Scene("proclaim a saying", 3, null, { proclaim(sayings.pick().line()) }),
            Scene("proclaim a quip", 2, null, { proclaim(Lines.quips.random()) }),
            Scene("proclaim the time", 1, null, { proclaim("It is ${time()} and all is well!") }),
            // --- His day ---
            Scene("morning stretch", 0, null, {
                repeat(3) {
                    pose(Moves.STRETCH1, 700, if (it == 0) sometimes("*yaaawn*") else null)
                    pose(Moves.STRETCH2, 700)
                }
                pose(Moves.STAND, 1200, sometimes(Lines.mornings.random()))
            }, can = { life.phase() == Phase.MORNING }, extra = { 6 }),
            Scene("polish his sword", 1, null, {
                repeat(6) { pose(if (it % 2 == 0) Moves.POLISH1 else Moves.POLISH2, 350, if (it == 2) sometimes("A knight's blade must gleam.") else null) }
                pose(Moves.EN_GARDE, 1200)
            }, extra = { if (life.phase() == Phase.MORNING) 4 else 0 }),
            Scene("trumpet the weather", 0, null, { proclaim(weatherNews()) }, can = { senses.sky() != null && life.phase() == Phase.MORNING }, extra = { 6 }),
            Scene("eat a turkey leg", 0, null, { eat() }, extra = {
                life.hunger / NEED_STEP + if (life.phase() == Phase.MIDDAY) 6 else 0
            }),
            Scene("night watch patrol", 0, null, { patrol() }, can = { life.phase() == Phase.EVENING || life.phase() == Phase.NIGHT }, extra = {
                if (life.phase() == Phase.EVENING) 6 else 3
            }),
            // --- When he's bored ---
            Scene("juggle", 0, null, {
                repeat(10) { pose(if (it % 2 == 0) Moves.JUGGLE1 else Moves.JUGGLE2, 300) }
                pose(Moves.BOW, 1200, sometimes("Thank thee! I am here all week."))
                life.played(BORED_RELIEF)
            }, {
                pose(Moves.FLINCH, 1400, "My balls! Er... my juggling balls!", listening = false)
            }, extra = { life.boredom / NEED_STEP }),
            Scene("kick a pebble", 0, null, { kickPebble() }, extra = { life.boredom / (NEED_STEP + 4) }),
            // --- The weather ---
            Scene("shelter from the rain", 0, null, {
                pose(Moves.SHIELD, 3200, sometimes(Lines.rain.random()))
                pose(Moves.SHIELD, 1600)
            }, can = { senses.sky() == "rain" || senses.sky() == "storm" }, extra = { 8 }),
            Scene("shiver in the cold", 0, null, {
                repeat(8) { pose(if (it % 2 == 0) Moves.SHIVER1 else Moves.SHIVER2, 160) }
                pose(Moves.SHIVER1, 1600, sometimes(Lines.cold.random()))
            }, can = { (senses.celsius() ?: 20.0) < COLD_C || senses.sky() == "snow" }, extra = { 6 }),
            Scene("sunbathe", 0, null, {
                pose(Moves.SUNBATHE, 3000, sometimes(Lines.sunny.random()))
                pose(Moves.SUNBATHE, 2400)
                pose(Moves.STAND, 800)
            }, can = {
                senses.sky() == "clear" && life.phase() in listOf(Phase.MIDDAY, Phase.AFTERNOON) && (senses.celsius() ?: 0.0) >= WARM_C
            }, extra = { 5 }),
            // --- The battery ---
            Scene("crank the low battery", 0, null, {
                goToTile("battery")
                mischief(listOf(Moves.PUSH1, Moves.PUSH2), listOf("Crank... the... lightning!", "Hnngh! More power!"), each = 200)
                pose(Moves.HUFF, 1600, "Plug thy phone in, my liege. My arms are not what they were.")
            }, can = { senses.battery()?.let { (level, charging) -> level <= LOW_BATTERY && !charging } == true }, extra = { 8 }),
            Scene("feed the lightning (charging)", 0, null, {
                goToTile("battery")
                pose(Moves.SIT, 3000, sometimes("Eat up, little lightning."))
                pose(Moves.SIT, 2400)
            }, can = { senses.battery()?.second == true }, extra = { 3 }),
            Scene("cheer a full battery", 0, null, {
                goToTile("battery")
                repeat(3) {
                    pose(Moves.CHEER, 400)
                    pose(Moves.STAND, 250)
                }
                pose(Moves.CHEER, 1400, "Full! The lightning is full!")
                life.celebrate("full")
            }, can = { senses.battery()?.let { (level, charging) -> level >= FULL_BATTERY && charging } == true && !life.celebrated("full") }, extra = { 6 }),
            // --- The alarm coming up: he creeps past the clock ---
            at("clock", "tiptoe past the clock (alarm soon)", 0, {
                pose(Moves.FLINCH, 1400, "Shh! 'Twill ring soon!", listening = false)
            }) {
                pose(Moves.TIPTOE1, 600, "Shh...")
                val w = world()
                val on = w.standingOn(x, feet)
                if (on != null) walkTo(if (right) on.right else on.left, steps = Moves.TIPTOE1 to Moves.TIPTOE2, stepMs = TIPTOE_MS)
                pose(Moves.STAND, 1400, sometimes("The bell sleeps. Let it lie."))
            }.let { Scene(it.name, it.weight, it.needs, it.play, it.caught, can = { senses.minutesToAlarm()?.let { m -> m in 0..ALARM_SOON_MIN } == true }, extra = { 8 }) },
            // --- A game won ---
            at("games", "cheer a best score", 0, null) {
                repeat(3) {
                    pose(Moves.CHEER, 380)
                    pose(Moves.STAND, 220)
                }
                pose(Moves.BOW, 1800, "Huzzah! A champion walks among us!")
            }.let { Scene(it.name, it.weight, it.needs, it.play, it.caught, can = { bestLately() }, extra = { 8 }) },
            // --- Visitors, now and then ---
            Scene("visitor: cat", 1, null, { cat() }),
            Scene("visitor: goose", 1, null, { goose() }),
            Scene("visitor: squire", 1, null, { squire() }),
            Scene("visitor: dragon hatchling", 1, null, { dragon() }),
            // --- Chores about the realm ---
            chore("swept", Moves.SWEEP1, Moves.SWEEP2, "*swish swish*", "A clean path for my liege's visits."),
            chore("woodpile", Moves.CHOP1, Moves.CHOP2, "*THOCK*", "Firewood for the winter. Winter is always coming."),
            chore("banner", Moves.STRETCH1, Moves.STRETCH2, "Up it goes...", "The banner of Quertia flies!"),
            chore("mended", Moves.HAMMER1, Moves.HAMMER2, "*tap tap*", "There. The door I slammed is good as new."),
            // --- Special days ---
            Scene("name day feast", 0, null, {
                life.celebrate("nameday")
                proclaim("Today is my name day! Cake for everyone! Well, for me.")
                eat()
            }, can = { life.holiday() == Holiday.NAME_DAY && !life.celebrated("nameday") }, extra = { 20 }),
            Scene("Halloween fright", 0, null, {
                pose(Moves.EN_GARDE, 1600, "BOO! ...Did I frighten thee? 'Tis All Hallows' Eve!")
            }, can = { life.holiday() == Holiday.HALLOWEEN }, extra = { 4 }),
            Scene("Winterfest greeting", 0, null, {
                life.celebrate("winterfest")
                proclaim("A merry Winterfest to thee, my liege!")
            }, can = { life.holiday() == Holiday.CHRISTMAS && !life.celebrated("winterfest") }, extra = { 10 }),
            // With his wits (the AI): a thought of his own about where he is and what he remembers.
            Scene("a thought of his own (AI)", 5, null, {
                val tiles = world().onTiles(except = REALM)
                if (tiles.isNotEmpty()) goTo(tiles.random())
                val line = thinkAloud(situation())
                if (line != null) pose(Moves.STAND, readingTime(line), line)
            }, thinks = true),
            Scene("sword drill", 2, null, {
                pose(Moves.EN_GARDE, 900, "Have at thee, foul bug!")
                repeat(4) {
                    pose(Moves.SWING1, 140)
                    pose(Moves.SWING2, 140)
                    pose(Moves.SWING3, 140)
                }
                pose(Moves.STAND, 1600, Lines.afterDrill.random())
            }),
            Scene("nap", 2, null, {
                pose(Moves.STAND, 800, "*yawn*")
                dress(napping = true)
                for (z in listOf("z", "z z", "z z Z", "z z Z Z", "z z Z", "z z Z Z")) pose(Moves.NAP, 1100, z)
                life.rested()
                dress()
                pose(Moves.STAND, 1400, "Mm? I was guarding. With mine eyes shut. Very hard.")
            }, {
                dress()
                pose(Moves.FLINCH, 600, "Wha-!", listening = false)
                pose(Moves.HUFF, 2400, "I was resting mine eyes! Knights do that.", listening = false)
            }, extra = { life.tiredness / NEED_STEP + if (life.phase() == Phase.NIGHT) 4 else 0 }),
            Scene("trip and fall", 2, null, {
                // Trips, and tumbles to whatever is below.
                val w = world()
                walkTo(w.clampX(x + if (right) 3 else -3))
                pose(Moves.FLINCH, 400, "Whoa-!", listening = false)
                fallTo(w.below(x, feet + 1).feet)
                pose(Moves.STAND, 1600, Lines.afterFall.random())
            }),
            Scene("wander to a tile", 3, null, {
                val tiles = world().onTiles(except = REALM)
                if (tiles.isNotEmpty()) goTo(tiles.random())
                pose(Moves.STAND, 1200, sometimes(Lines.wanderings.random()))
            }),
            Scene("bow to thee", 1, null, {
                pose(Moves.BOW, 2000, "My liege.")
                pose(Moves.WAVE, 1800, Lines.compliments.random())
            }),
            // His tree: he waters it, and dreams of a keep.
            Scene("water his tree", 2, REALM, {
                val door = door(world()) ?: return@Scene
                travelTo(door.second, door.second.nearest(door.first + wide))
                right = false
                mischief(listOf(Moves.WATER1, Moves.WATER2), listOf("Grow, little tree. Grow.", "One day thou shalt be a mighty keep."))
                pose(Moves.STAND, 2000, Lines.realm.random())
            }, {
                pose(Moves.FLINCH, 2000, "Hands off my realm!", listening = false)
            }),
            // At a tile, up to something.
            at("clock", "nudge the clock", 4, {
                pose(Moves.FLINCH, 1400, "Thou saw NOTHING!", listening = false)
                memory.bump(Memory.CAUGHT)
                memory.remember("The user caught me nudging the clock ahead; I bolted home")
                goHome(bolt = true)
                throw Gone()
            }) {
                mischief(
                    listOf(Moves.FIDDLE1, Moves.FIDDLE2),
                    listOf("Winter is coming. Faster, if I nudge this...", "...so supper cometh sooner."),
                )
                pose(Moves.STAND, 1800, "Heh. None the wiser.")
            },
            at("music", "dance at the music tile", 4, {
                pose(Moves.FLINCH, 700, "!", listening = false)
                pose(Moves.STAND, 2600, "I was merely... inspecting the minstrel's work!", listening = false)
            }) {
                pose(Moves.LISTEN, 1800, "Hark! Is that a ballad?")
                val moves = listOf(Moves.DANCE1, Moves.DANCE2)
                repeat(12) { i -> pose(moves[i % 2], 280, if (i < 6) "~ la la laa ~" else "The Rains of Quertia! My favourite!") }
                pose(Moves.BOW, 1400, "Thank thee, thank thee.")
            },
            at("weather", "shield at the weather tile", 3, {
                pose(Moves.SHIELD, 2400, "Careful! 'Tis heavier than it looks!", listening = false)
            }) {
                pose(Moves.GAZE, 1400, "The heavens look... heavenly.")
                pose(Moves.SHIELD, 3000, "Fear not! I am the shield that guards the realms of men!")
            },
            at("battery", "push the battery", 3, {
                pose(Moves.FLINCH, 1800, "Nay! It was like this when I arrived!", listening = false)
            }) {
                mischief(listOf(Moves.PUSH1, Moves.PUSH2), listOf("Hnngh!", "More power... for my liege!"), each = 220)
                pose(Moves.STAND, 1600, "There. Probably one more percent.")
            },
            at("favorites", "sort the favourites", 3, {
                pose(Moves.FLINCH, 1800, "Touched nothing! On mine honour!", listening = false)
            }) {
                mischief(listOf(Moves.FIDDLE1, Moves.FIDDLE2), listOf("Such fine apps...", "I shall sort them. By smell."))
            },
            at("alarm", "muffle the alarm", 3, {
                pose(Moves.FLINCH, 1800, "'Twas a jest! Mostly.", listening = false)
            }) {
                mischief(listOf(Moves.FIDDLE1, Moves.FIDDLE2), listOf("Shh...", "I shall muffle the bell. Thou deservest more sleep."))
            },
            at("notifications", "peek at the notifications", 2, null) {
                pose(Moves.READ, 2600, "Ravens for thee! Shall I read them aloud?")
                pose(Moves.STAND, 1800, "...Nay. 'Tis private. I only peeked a little.")
            },
            at("todo", "read the todo list", 2, null) {
                pose(Moves.READ, 2600, "Thy quest log grows long, my liege.")
                pose(Moves.EN_GARDE, 1800, "Shall I slay one for thee? I hear there are rats in the cellar.")
            },
            at("calendar", "read the calendar", 2, null) {
                pose(Moves.READ, 2200, "Hmm. What day is it?")
                pose(Moves.STAND, 1800, "...I shall pretend I knew.")
            },
            at("sky", "gaze at the sky tile", 2, null) {
                pose(Moves.GAZE, 2600, "Night gathers, and now my watch begins.")
                pose(Moves.STAND, 1800, "...Is that the moon, or a very large cheese?")
            },
            at("news", "cry the news", 2, null) {
                pose(Moves.TRUMPET1, 600)
                pose(Moves.TRUMPET2, 1800, "Extra! Extra!")
                pose(Moves.STAND, 1800, "...Nay, that is not how heralds speak.")
            },
            at("sys", "puzzle at the system tile", 1, null) {
                pose(Moves.READ, 2600, "Such memory! Such storage! I understand none of it.")
            },
            at("net", "marvel at the network tile", 1, null) {
                pose(Moves.GAZE, 2600, "Invisible ravens fly through the air. Witchcraft, surely.")
            },
        )

        /**
         * What happens to him rather than what he chooses to do: never picked as a scene,
         * only played when it happens, or from `alex debug`.
         */
        private val moments: List<Scene> = listOf(
            Scene("moment: first meeting", 0, null, { introduce() }),
            Scene("moment: welcome back", 0, null, { Lines.welcomeBack.random().let { pose(Moves.WAVE, readingTime(it), it) } }),
            Scene("moment: away long", 0, null, { Lines.awayLong.random().let { pose(Moves.WAVE, readingTime(it), it) } }),
            Scene("moment: wary peek", 0, null, {
                pose(Moves.PEEK, 1400, Lines.peeks.random())
                pose(Moves.PEEK, 900)
            }),
            Scene("moment: raven with a letter", 0, null, { raven() }),
            Scene("moment: dance while music plays", 0, null, { dance() }),
            Scene("moment: timer finished", 0, null, { timeUp() }),
            Scene("moment: taunt (poked)", 0, null, { taunt() }),
            Scene("moment: duel (poked thrice)", 0, null, { duel() }),
            Scene("moment: storm off in a huff", 0, null, {
                pose(Moves.HUFF, 2000, Lines.huffs.random(), listening = false)
                goHome(bolt = true)
                throw Gone()
            }),
        )

        /** Every scene, then every moment: whether it could happen now, and how likely it is to be picked. */
        fun report(): List<SceneInfo> {
            val shown = world().platforms.mapNotNull { it.tile }.toSet()
            return scenes.map { scene ->
                val there = scene.needs == null || scene.needs in shown
                val now = there && (!scene.thinks || brain.awake) && scene.can()
                SceneInfo(scene.name, scene.needs, there, now, if (now) (scene.weight + scene.extra()).coerceAtLeast(0) else 0, moment = false)
            } + moments.map { SceneInfo(it.name, null, tileShown = true, canNow = true, weight = 0, moment = true) }
        }

        init {
            // Last: what `alex debug` reads is all made by now.
            show = this
        }

        /** Over to the tile [id], if it's there to stand on; else he stays where he is. */
        private suspend fun goToTile(id: String) {
            surfaceOf(world(), id)?.let { goTo(it) }
        }

        /** A roast turkey leg, bite by bite. */
        private suspend fun eat() {
            pose(Moves.STAND, 700, sometimes("Luncheon!"))
            repeat(8) { pose(if (it % 2 == 0) Moves.EAT1 else Moves.EAT2, 380, if (it == 4) sometimes("*munch munch*") else null) }
            life.ate()
            pose(Moves.STAND, 1400, sometimes(Lines.fed.random()))
        }

        /** Along a tile end to end and back, lantern held out: the night watch. */
        private suspend fun patrol() {
            dress()
            val tiles = world().onTiles(except = REALM)
            if (tiles.isNotEmpty()) goTo(tiles.random())
            val on = world().standingOn(x, feet) ?: return
            val patrol = Moves.PATROL1 to Moves.PATROL2
            walkTo(on.right, steps = patrol)
            walkTo(on.left, steps = patrol)
            pose(Moves.STAND, 1600, sometimes(Lines.patrol.random()))
        }

        /** Kicks a pebble along the tile, and after it, and again. */
        private suspend fun kickPebble() {
            val on = world().standingOn(x, feet) ?: return
            var pebble = x + if (right) wide else -1
            repeat(3) {
                company = listOf(Prop(pebble, feet, listOf("o"), PEBBLE))
                pose(Moves.KICK, 300)
                val to = (pebble + if (right) PEBBLE_KICK else -PEBBLE_KICK).coerceIn(on.left, on.right + wide - 1)
                while (pebble != to) {
                    pebble += if (to > pebble) 1 else -1
                    company = listOf(Prop(pebble, feet, listOf("o"), PEBBLE))
                    show(Moves.STAND)
                    delay(PEBBLE_MS)
                }
                walkTo((pebble - if (right) wide else -1).coerceIn(on.left, on.right))
            }
            life.played(BORED_RELIEF)
            pose(Moves.STAND, 1200, sometimes("Goal! The crowd goes wild."))
        }

        /** The day just ended goes in his journal: in his own words (the AI's, when it's set up). */
        private suspend fun writeJournal(day: DayLog) {
            pose(Moves.READ, 1200, sometimes("Dear diary..."))
            val line = if (brain.awake) thinkAloud("You are writing your journal.") { brain.journal(chronicle.facts(day)) } else null
            chronicle.write(day.date, line ?: chronicle.plainEntry(day))
            pose(Moves.READ, 1200, "*scribble scribble*")
        }

        /** "Hear ye": the weather, as the weather module told it. */
        private fun weatherNews(): String = when (senses.sky()) {
            "rain" -> "Hear ye! Rain today. Bring thy cloak, my liege!"
            "storm" -> "Hear ye! Storms gather. Stay by the hearth!"
            "snow" -> "Hear ye! Snow! Winter hath come at last!"
            "fog" -> "Hear ye! A fog lies on the land. Tread carefully."
            "clear" -> "Hear ye! Fair skies today. A fine day for a quest!"
            else -> "Hear ye! Clouds today. Neither here nor there."
        }

        /** A best score was beaten a little while ago. */
        private fun bestLately(): Boolean {
            val at = services.find<Happenings>()?.latest?.value?.get("games.best")?.at ?: return false
            return System.currentTimeMillis() - at < BEST_MS
        }

        /** A countdown just ended: frantic trumpeting. */
        private suspend fun timeUp() {
            timerTrumpeted = senses.timerEnds() ?: 0L
            repeat(4) {
                pose(Moves.TRUMPET1, 160, "TOOT!")
                pose(Moves.TRUMPET2, 160, "TOOT TOOT!")
            }
            pose(Moves.TRUMPET2, 2200, "TIME! Thy timer is done, my liege!")
        }

        /** Music plays: he dances where he stands, until it stops; then freezes, caught. */
        private suspend fun dance() {
            val moves = listOf(Moves.DANCE1, Moves.DANCE2)
            var i = 0
            val until = System.currentTimeMillis() + MAX_DANCE_MS
            while (System.currentTimeMillis() < until) {
                pose(moves[i % 2], 300, if (i < 6) sometimes("~ la la laa ~") else null)
                i++
                if (i % 4 == 0 && !senses.musicPlaying()) {
                    pose(Moves.DANCE1, 1200)
                    pose(Moves.STAND, 2200, Lines.musicStopped.random())
                    return
                }
            }
            pose(Moves.BOW, 1200)
        }

        /** A raven lands with a letter: he reads, gasps, and hurries off somewhere. */
        private suspend fun raven() {
            val landing = (x + if (right) wide else -RAVEN_WIDTH).coerceIn(0, world().columns - RAVEN_WIDTH)
            var line = world().ceiling
            while (line < feet - 1) {
                company = listOf(Prop(landing, line, listOf("\\v/"), RAVEN))
                show(Moves.LISTEN)
                delay(RAVEN_MS)
                line++
            }
            company = listOf(Prop(landing, feet, listOf(">v<"), RAVEN))
            pose(Moves.LISTEN, 900, "*caw*")
            pose(Moves.LETTER, 2000, sometimes("A letter! For my liege!"))
            pose(Moves.GASP, 1600, Lines.letters.random())
            company = emptyList()
            val tiles = world().onTiles(except = REALM)
            if (tiles.isNotEmpty()) {
                val dest = tiles.random()
                travelTo(dest, dest.nearest(x), run = true)
            }
        }

        /** A cat wanders up; he pets it; it wanders off. */
        private suspend fun cat() {
            val on = world().standingOn(x, feet) ?: return
            var cat = if (right) on.right + wide - CAT.length else on.left
            val stop = (x + if (cat > x) wide + 1 else -CAT.length - 1).coerceIn(on.left, on.right + wide - CAT.length)
            right = cat > x
            while (cat != stop) {
                cat += if (stop > cat) 1 else -1
                company = listOf(Prop(cat, feet, listOf(CAT), CAT_COLOR))
                show(Moves.STAND)
                delay(WALK_MS * 2)
            }
            pose(Moves.BOW, 1800, sometimes("Who's a good mouser? Thou art!"))
            company = listOf(Prop(cat, feet - 1, listOf("<3"), HEART), Prop(cat, feet, listOf(CAT), CAT_COLOR))
            pose(Moves.BOW, 1400, "*purr*")
            val away = if (cat > x) world().columns else -CAT.length
            while (cat != away) {
                cat += if (away > cat) 1 else -1
                company = listOf(Prop(cat, feet, listOf(CAT), CAT_COLOR))
                show(Moves.WAVE)
                delay(WALK_MS)
            }
        }

        /** A goose: it honks, and chases him along the tile and off it. */
        private suspend fun goose() {
            val on = world().standingOn(x, feet) ?: return
            val fromRight = x < (on.left + on.right) / 2
            var goose = if (fromRight) on.right + wide - GOOSE.length else on.left
            right = !fromRight
            company = listOf(Prop(goose, feet, listOf(if (fromRight) GOOSE else GOOSE_RIGHT), GOOSE_COLOR))
            pose(Moves.FLINCH, 900, "HONK!", listening = false)
            pose(Moves.FLINCH, 900, sometimes("Not the goose! Anything but the goose!"), listening = false)
            val end = if (fromRight) on.left else on.right
            var step = 0
            while (x != end) {
                x += if (end > x) 1 else -1
                goose += if (end > x) 1 else -1
                company = listOf(Prop(goose, feet, listOf(if (fromRight) GOOSE else GOOSE_RIGHT), GOOSE_COLOR))
                show(if (step++ / 2 % 2 == 0) Moves.RUN1 else Moves.RUN2, if (step < 6) "Aaah!" else null)
                delay(RUN_MS * 2)
            }
            company = emptyList()
            pose(Moves.HUFF, 1800, "I... let it win. Chivalry.")
        }

        /** His squire trots along behind him, and wanders off when asked for something. */
        private suspend fun squire() {
            val on = world().standingOn(x, feet) ?: return
            val target = if (x - on.left > on.right - x) on.left else on.right
            var step = 0
            right = target > x
            while (x != target) {
                x += if (target > x) 1 else -1
                val behind = x + if (right) -SQUIRE_GAP else wide + 1
                company = listOf(Prop(behind, feet - 1, listOf(" o", "/|\\"), SQUIRE_COLOR))
                show(if (step++ % 2 == 0) Moves.WALK1 else Moves.WALK2)
                delay(WALK_MS)
            }
            pose(Moves.STAND, 1600, "Squire! Fetch my... he's gone.")
            company = emptyList()
            pose(Moves.HUFF, 1400, sometimes("Good help is so hard to find."))
        }

        /** A dragon hatchling, as big as his realm is grand: it sneezes fire, and he shields. */
        private suspend fun dragon() {
            val art = when (realm()) {
                1 -> listOf("~<:>")
                2 -> listOf(" __", "~<:=>")
                else -> listOf("  /\\_", "~<::==>")
            }
            val width = art.maxOf { it.length }
            val at = (x + if (right) wide + 1 else -width - 1).coerceIn(0, (world().columns - width).coerceAtLeast(0))
            company = listOf(Prop(at, feet - art.size + 1, art, DRAGON_COLOR))
            pose(Moves.LISTEN, 1400, sometimes("A dragon! Well... a small one."))
            val fire = at + if (at > x) -2 else width
            company = company + Prop(fire, feet, listOf("**"), FIRE)
            pose(Moves.SHIELD, 1200, "*achoo!*")
            company = listOf(Prop(at, feet - art.size + 1, art, DRAGON_COLOR))
            pose(Moves.SHIELD, 1400, sometimes("Bless thee. Please don't do that again."))
        }

        /** A chore by his tree, once a day: [done] shows in the realm afterwards. */
        private fun chore(done: String, a: Move, b: Move, working: String, after: String) = Scene("chore: $done", 2, REALM, {
            val door = door(world()) ?: return@Scene
            travelTo(door.second, door.second.nearest(door.first - wide))
            right = true
            repeat(8) { i -> pose(if (i % 2 == 0) a else b, 380, if (i == 2) working else null) }
            life.did(done)
            pose(Moves.STAND, 1800, sometimes(after))
        }, can = { done !in life.choresToday() })

        private fun time(): String {
            val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
            return LocalDateTime.now().format(DateTimeFormatter.ofPattern(pattern, Locale.US))
        }
    }

    /** A scene as `alex debug` shows it: could it happen now, and its weight when he picks what to do. */
    class SceneInfo(val name: String, val needs: String?, val tileShown: Boolean, val canNow: Boolean, val weight: Int, val moment: Boolean)

    /**
     * Something he does, at the tile [needs] (anywhere when null; he's handed
     * where he'd stand on it), how he takes being poked at it, and whether it
     * [thinks] (needs his brain, the AI).
     */
    private class Scene(
        /** What it is, in a few words: how `alex debug` lists it. */
        val name: String,
        val weight: Int,
        val needs: String?,
        val play: suspend Show.(Platform?) -> Unit,
        val caught: (suspend Show.() -> Unit)? = null,
        val thinks: Boolean = false,
        /** Whether it can happen now (rain for sheltering, morning for stretching). */
        val can: () -> Boolean = { true },
        /** Weight on top of [weight] now: a hungry knight eats sooner. */
        val extra: () -> Int = { 0 },
    )

    private companion object {
        const val MET = "met"
        const val REALM = "realm"
        const val ALWAYS = "always"

        const val FIRST_VISIT_MS = 4_000L
        const val MIN_SCENES = 2
        const val MAX_SCENES = 4

        /** How often (in percent) a passing remark, a greeting or a goodbye is said out loud. */
        const val TALK_PERCENT = 40

        /** A need's level over this is a point of weight towards what meets it. */
        const val NEED_STEP = 10
        const val PLAYED_PER_SCENE = 6
        const val BORED_RELIEF = 40

        /** Coming back to home after this long, a friendly knight comes to say so; after this, "thou wert away long". */
        const val WELCOME_AFTER_MS = 3 * 3_600_000L
        const val LONG_AWAY_MS = 2 * 86_400_000L
        const val WELCOME_MS = 2_000L
        const val NIGHT_ABSENCE = 2
        const val KNOCK_AGAIN_MS = 60_000L
        const val TIMER_LATE_MS = 10 * 60_000L
        const val WARY_LEAVE_AFTER = 3

        const val COLD_C = 5.0
        const val WARM_C = 18.0
        const val LOW_BATTERY = 20
        const val FULL_BATTERY = 100
        const val ALARM_SOON_MIN = 60L
        const val BEST_MS = 15 * 60_000L
        const val TIPTOE_MS = 260L
        const val MAX_DANCE_MS = 30_000L

        const val PEBBLE_KICK = 5
        const val PEBBLE_MS = 70L
        val PEBBLE = 0xFFA7A9AE.toInt()
        const val RAVEN_WIDTH = 3
        const val RAVEN_MS = 90L
        val RAVEN = 0xFF5A6070.toInt()
        const val CAT = "=^.^="
        val CAT_COLOR = 0xFFFFA83A.toInt()
        val HEART = 0xFFFF79C6.toInt()
        const val GOOSE = ">(')"
        const val GOOSE_RIGHT = "(')<"
        val GOOSE_COLOR = 0xFFF4F8FF.toInt()
        const val SQUIRE_GAP = 4
        val SQUIRE_COLOR = 0xFFC08A52.toInt()
        val DRAGON_COLOR = 0xFF46C95A.toInt()
        val FIRE = 0xFFFF8A1F.toInt()
        const val MISCHIEF_FRAMES = 12

        const val WALK_MS = 130L
        const val RUN_MS = 45L
        const val HOP_MS = 70L
        const val FALL_MS = 50L
        const val LAND_MS = 200L
        const val SPLAT_MS = 900L
        const val DOOR_MS = 1100L
        const val KICK_MS = 220L
        const val CLIMB_MS = 170L
        const val MIN_HOP = 6
        const val MAX_HOP = 16
        const val HOP_HEIGHT = 3

        /** Down this many lines he hops; further, he takes his ladder. From this far, a fall hurts. */
        const val HOP_DOWN = 3
        const val HARD_LANDING = 4
        const val LADDER_OVER = 1
        val LADDER = 0xFFB9824F.toInt()

        /** His tree's scene is 200 x 120 units, its door 30 tall; a cell is half as wide as tall. */
        const val SCENE_W = 200f
        const val SCENE_H = 120f
        const val DOOR_UNITS = 30f
        const val CELL_ASPECT = 0.5f

        /** He stands this much of his box's height (plume to boots), and ducks to this much of the door. */
        const val FIGURE_SHARE = 0.72f
        const val FIT_IN_DOOR = 0.85f
        const val MIN_SIZE = 1
        const val MAX_GROWTH = 3

        /** Pokes this close together count as pestering: a duel at [DUEL_AT], gone at [LEAVE_AFTER]. */
        const val PESTER_MS = 20_000L
        const val DUEL_AT = 3
        const val LEAVE_AFTER = 5

        /** Some things are remembered only now and then, not every time. */
        const val REMEMBER_EVERY = 5
        const val AI_TAUNT_ODDS = 3
    }
}
